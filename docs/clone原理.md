# SyncNote 克隆与同步原理

本文只讲**机制**：点下「同步」之后，JGit 在本地磁盘和网络上到底做了什么。选型理由、UI、权限方案见 [技术方案.md](./技术方案.md)。

---

## 0. 一句话

> 同步 = 把远端的分支指针搬过来，再让本地文件强行变成那个提交的样子。

全程**不合并、不比较差异、不产生冲突**。代价是本地任何未提交改动都会被丢弃——这符合「本地不用了，以远端为准」的设定。

实现上它不是 `git pull`，而是：

```
fetch  →  reset --hard  →  clean -d
```

---

## 1. 前置：先搞清 Git 把东西存在哪

不理解这四个位置，后面所有步骤都是黑箱。

| 位置 | 物理路径 | 存什么 | 谁改它 |
|---|---|---|---|
| 对象库 | `.git/objects/` | 所有提交、树、文件内容的压缩快照，按内容哈希命名 | `clone`、`fetch` 写入 |
| 引用（refs） | `.git/refs/`、`.git/packed-refs` | 分支指针，本质是「分支名 → 提交哈希」 | `fetch`、`reset` |
| 索引（暂存区） | `.git/index` | 「下次提交要包含哪些文件、各自什么哈希」的清单 | `reset`、`checkout` |
| 工作区 | 仓库目录里的普通文件 | 你肉眼看到的那些 md 文件 | `reset`、`checkout`、你手动编辑 |

再补两个关键概念：

- **`HEAD`**（`.git/HEAD`）不是分支，只是一行文字 `ref: refs/heads/main`，指向「当前所在的分支」
- **`refs/remotes/origin/main`** 是**远端跟踪分支**——它是本地的一份副本，记录「上次和远端通信时，远端的 main 在哪」。它不代表远端实时状态，只有 `fetch` 才会更新它

这两类分支必须分清，否则会疑惑「为什么 fetch 完文件没变」。

---

## 2. `clone` 到底做了什么

### 2.1 四个步骤

`Git.cloneRepository()` 不是一次原子操作，内部展开成四步：

```
① init      在目标目录建空的 .git，写入 HEAD -> refs/heads/main（此时还没有这个分支）
② remote    写 .git/config：remote.origin.url = 仓库地址、fetch refspec
③ fetch     把远端的 refs/heads/* 抓到本地 refs/remotes/origin/*
④ checkout  从远端默认分支创建本地分支 refs/heads/main，并把提交树展开成工作区文件
```

对应到代码（`GitSync.clone()`）：

```kotlin
val command = Git.cloneRepository()
    .setURI(url)                    // ② 决定 remote.origin.url
    .setDirectory(targetDir)        // ① 在此处建 .git
    .setProgressMonitor(...)        // ③④ 的进度回调
credentials?.let { command.setCredentialsProvider(it) }
command.call().close()
```

只有第 ④ 步结束，你才能在目录里看到 md 文件。前三步走完时目录里只有 `.git`，看起来是空的。

### 2.2 网络层发生了什么

GitHub 的 HTTPS 地址走的是 **smart HTTP 协议**，一次 clone 是两次 HTTP 请求：

```
GET  /owner/repo.git/info/refs?service=git-upload-pack
     ← 服务端返回「能力广告 + 每个 ref 的当前哈希」
     例：refs/heads/main = 9f2c1a…

POST /owner/repo.git/git-upload-pack
     → 客户端发 want（我要这个哈希）+ have（我本地已经有哪些哈希，首次为空）
     ← 服务端算出差集，返回一个 packfile（压缩后的对象集合）
```

关键在 **`have` 列表**：它让服务端知道哪些对象不用重发。首次 clone 的 `have` 是空的，所以拿到全量；之后的 `fetch` 才有得省。

JGit 里这套协商由 `TransportHttp` 实现，`ProgressMonitor` 的 `beginTask / update` 回调就是解包 packfile 时按对象计数触发的——所以进度条反映的是「解包了多少对象」，不是「下载了多少字节」。

### 2.3 为什么这里没有浅克隆

JGit **5.13.3 的 `CloneCommand` 没有 `setDepth`**（方法在 6.x 才加入）。实测确认方式：

```bash
unzip -p org.eclipse.jgit-5.13.3.jar org/eclipse/jgit/api/CloneCommand.class \
  | strings | grep -i depth
# 无输出 → 没有这个方法
```

后果：首次必须下载**全部历史**，包括所有历史版本的快照。对笔记类仓库（每次提交都是小 md）通常几 MB，可以接受；对大仓库会很慢且占空间。

绕开的两条路：

| 方案 | 代价 |
|---|---|
| 升到 JGit 6.x | 依赖 Java 11 API，Android 上要额外处理 desugar，minSdk 也会被抬高 |
| 改用 zip 包下载（`codeload.github.com/o/r/zip/refs/heads/main`） | 快且小，但拿不到 `.git`，**之后无法增量更新**，只能每次全量覆盖 |

v1 保持现状：全量 clone + 后续增量。仓库大是已知限制，写在风险表里。

### 2.4 这次 clone 之后，磁盘上多了什么

```
<父目录>/Notes/
├── .git/                     ← 纯数据，别手动动它
│   ├── config                ← remote.origin.url 在这里（token 不会被写进来）
│   ├── HEAD                  ← 一行：ref: refs/heads/main
│   ├── objects/              ← 对象库，可能已是 pack 压缩形态
│   └── refs/
│       ├── heads/main        ← 本地分支
│       └── remotes/origin/   ← 远端跟踪分支 + HEAD
└── ...仓库里的真实文件
```

---

## 3. 增量同步：三步，各管一层

第二次起走 `GitSync.update()`。三步分别作用于第 1 节表格里的不同层，这是理解它的关键。

### 3.1 `fetch`：只写 `.git`，不碰工作区

```kotlin
git.fetch()
    .setRemote("origin")
    .setRefSpecs(RefSpec("+refs/heads/*:refs/remotes/origin/*"))
    .setRemoveDeletedRefs(true)
    .setProgressMonitor(...)
    .call()
```

refspec 拆开看：

```
+ refs/heads/*  :  refs/remotes/origin/*
↑        ↑              ↑
强制     远端的分支名    存到本地的哪个位置
```

- **`+` 允许非快进更新。** 不加的话，远端做过 force push（历史被改写）时 JGit 会报 `rejected - non-fast-forward`，因为「远端分支不是本地记录的后代」。加上 `+` 表示「无条件覆盖本地记录」。同步场景必须加。
- **`setRemoveDeletedRefs(true)`** 让远端删掉的分支，本地 `refs/remotes/origin/` 下对应条目也一起清掉，避免残留脏引用。
- **只写 `refs/remotes/*`**：`refs/heads/main` 不动、`HEAD` 不动、工作区文件一个都不动。所以 fetch 完成时，界面上的文件可能还是旧的——这是正常现象。

网络层用的是同一套 `have` 协商：把本地已有提交哈希发过去，服务端只回缺的对象。这就是第二次同步通常一两秒的原因。

**地址校验**（`update()` 开头）：fetch 走 `.git/config` 里存的 `remote.origin.url`，不读用户输入框。所以代码会先比对两者，归一化后（忽略协议、`userinfo`、`.git` 后缀、尾斜杠、大小写）不一致就直接报错，避免「改了地址却拉回旧仓库还显示成功」。

### 3.2 挑分支：不能写死 `main`

`fetch` 之后要确定「对齐到哪个分支」。`resolveDefaultBranch()` 是三级降级：

| 优先级 | 手段 | 说明 |
|---|---|---|
| 1 | `refs/remotes/origin/HEAD` 的哈希，反查同哈希的分支 | 正常仓库都有，最准 |
| 2 | 依次试 `main`、`master` | 兜底老仓库 |
| 3 | 取字典序第一个远端分支 | 再兜底 |
| — | 返回 `main` | 一个远端分支都没有时 |

不能直接读 `origin/HEAD` 的**符号引用**，因为 JGit 在部分情况下只留下哈希甚至完全不存在；拿哈希去 `refs` 列表里反查同一个提交，比读符号引用更稳。

### 3.3 `reset --hard`：一次改三层

```kotlin
git.reset()
    .setMode(ResetCommand.ResetType.HARD)
    .setRef("refs/remotes/origin/$branch")
    .call()
```

`--hard` 的含义是下面三件事一起做：

| 层 | 动作 |
|---|---|
| 引用 | 把当前分支（`refs/heads/main`）指到目标提交 |
| 索引 | 整个重置为目标提交的树 |
| 工作区 | 按目标树重写文件；**删除**索引里有、目标树里没有的文件 |

第三行顺带解释了「远端删掉的文件，本地为什么也会消失」——它不在新树里，hard reset 就会把它从工作区删掉。这一步不需要额外写删除逻辑。

**不做 merge 的原因**：merge 需要找共同祖先、三方合并、冲突时要求人工介入；而这里语义是「本地不要了」，merge 反而会保留本地提交，与需求相反。

用完整路径 `refs/remotes/origin/main` 而不是简写 `origin/main`，是为了避免 jgit 解析时的分支/标签歧义（同名的 tag 存在于老仓库里并不罕见）。

### 3.4 `clean -d`：收尾未跟踪文件

`reset --hard` 只管「被跟踪」的文件。你手动扔进 `Notes/` 的、以及早期版本留下但从未被跟踪的文件，它一概不管。所以补一步：

```kotlin
runCatching { git.clean().setCleanDirectories(true).call() }
```

- `-d` 等价于 `setCleanDirectories(true)`：连未跟踪的**目录**一起删，否则空目录会残留
- **没有调用 `setIgnore(true)`**：`.gitignore` 覆盖的文件属于 `ignored` 而非普通 `untracked`，默认不清理。这是刻意的——否则你本地的 token 文件、临时笔记会一起没了
- 外层 `runCatching`：clean 失败很常见（文件被别的 App 占用、权限不足），不该因此让整次同步判定为失败——此时文件内容其实已经对齐了

---

## 4. 与命令行 git 的对照

| 场景 | 本 App（JGit） | 等价命令 |
|---|---|---|
| 首次克隆 | `Git.cloneRepository()` | `git clone <url> <dir>` |
| 拉取更新 | `git.fetch()` + refspec | `git fetch origin '+refs/heads/*:refs/remotes/origin/*' --prune` |
| 对齐内容 | `git.reset()` HARD | `git reset --hard origin/main` |
| 清理残留 | `git.clean()` | `git clean -d -f` |
| 查看默认分支 | `resolveDefaultBranch()` | `git symbolic-ref refs/remotes/origin/HEAD` |

所以真机上出问题时，可以在电脑上对同一仓库执行对应命令来复现，行为是一致的。

---

## 5. 认证发生在哪一层

不在 git 协议层，在 **HTTP 传输层**：

```kotlin
UsernamePasswordCredentialsProvider("x-access-token", token)
```

流程是：JGit 先匿名请求 → GitHub 返回 `401` → JGit 取出凭据提供者，用 HTTP Basic 重发（`Authorization: Basic base64(x-access-token:token)`）→ 通过。

两个容易踩的点：

- **token 必须放密码位。** fine-grained token（`github_pat_…`）如果放在用户名位、密码留空，直接 401；classic token 侥幸能过。这就是最初认证失败的根因。
- **凭据提供者只喂给传输层，不进 URL。** 所以 `.git/config` 里的 `remote.origin.url` 天然是干净的，不需要克隆完再改写（早期版本靠事后改写来抹掉 token，已删除）。

HTTP 401 和「仓库不存在」在 GitHub 上是同一个响应的两种解释：私有仓库无权访问时返回的是 **404 而不是 403**，所以错误提示把两者合并成「仓库不存在，或 Token 无权访问」。

---

## 6. 进度条的数字从哪来

`ProgressAdapter` 实现 `ProgressMonitor`：

| 回调 | 时机 | 处理 |
|---|---|---|
| `beginTask(title, totalWork)` | 任务开始，告知总工作量（对象数） | 记下 `totalWork` |
| `update(completed)` | 每处理完一个对象 | `completed * 100 / totalWork`，封顶 94 |
| `endTask()` | 结束 | 无 |

两个刻意的设计：

- **封顶 94%**：fetch/clone 走完不代表结束，还要 reset + clean。留出余量，避免进度条卡在 100% 而界面还在忙
- **150ms 节流**：`update` 调用频率极高（每个对象一次），直接推 UI 会让 Compose 反复重组、界面卡顿。改为「百分比变化 且 距上次 ≥150ms」才回调一次

`reset` 完成后手动推 95，整体结束推 100。

---

## 7. 边界与已知风险

| 情况 | 实际行为 | 说明 |
|---|---|---|
| 目标目录存在但不是 Git 仓库 | 报错，**一个文件都不删** | 拒绝在别人的目录里 clone，防止覆盖已有数据 |
| 目标目录已关联另一个仓库 | 报错提示「已是另一个仓库的副本」 | 归一化比对 `remote.origin.url`，避免拉错源 |
| 远端 force push 过 | 正常跟到新历史 | `+` 允许非快进；旧提交变悬空对象，占空间但不回收 |
| 同步中途被杀 | 留下不完整的 `.git` | 下次点同步会走 update 分支并可能报错，需要手动删目录重来 |
| 仓库超大 | 首次很慢、占空间大 | 无 `setDepth`，见 2.3 |
| 低端机 + 大仓库 | 可能 OOM / 卡顿 | packfile 解包在内存中索引，JGit 5.13 无流式限制 |
| 仓库里有 Git LFS 文件 | 只拿到 LFS 指针文件（几行文本） | JGit 不会自动拉 LFS 对象，v1 不处理 |

---

## 8. 真机自检清单

同步成功后，可按这个顺序核对「确实按原理走通了」：

1. `<目标目录>/Notes/.git/config` 里的 `url` 是干净的 `https://github.com/…`，**不含 token**
2. `.git/refs/remotes/origin/` 下有分支文件，且与 `.git/refs/heads/` 下的本地分支哈希一致
3. 在电脑上对该仓库 `git log -1 --format=%H`，与手机 `.git/refs/heads/main` 内容相同
4. 远端删掉一个文件后重新同步，本地那个文件消失（`reset --hard` 生效）
5. 手动在目录里放一个未被 `.gitignore` 覆盖的文件，重新同步后它消失（`clean -d` 生效）
6. 放一个**被** `.gitignore` 覆盖的文件，重新同步后它仍然存在（未调 `setIgnore(true)`）
