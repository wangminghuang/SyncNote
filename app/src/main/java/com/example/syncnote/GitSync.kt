package com.example.syncnote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.ListBranchCommand
import org.eclipse.jgit.api.ResetCommand
import org.eclipse.jgit.api.errors.InvalidRemoteException
import org.eclipse.jgit.api.errors.TransportException
import org.eclipse.jgit.errors.RepositoryNotFoundException
import org.eclipse.jgit.lib.ProgressMonitor
import org.eclipse.jgit.transport.CredentialsProvider
import org.eclipse.jgit.transport.RefSpec
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import java.io.File
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException

/** 同步结果。 */
sealed interface SyncResult {
    data class Success(val dir: File, val message: String) : SyncResult
    data class Failed(val message: String) : SyncResult
}

/**
 * 核心同步逻辑：首次 clone，后续 fetch + reset --hard 强制对齐远端。
 *
 * 约定：本地内容以远端为准，本地改动会被丢弃（用户明确要求）。
 */
object GitSync {

    private const val DEFAULT_BRANCH_CANDIDATES = "main,master"

    /**
     * GitHub 对 HTTPS 的 Basic 认证只校验密码位的 token，用户名任意。
     * 这里用 x-access-token 是为了兼容 fine-grained token —— 把 token 放在
     * 用户名位、密码留空的写法只对 classic token 有效，fine-grained 会返回 401。
     */
    private const val GITHUB_TOKEN_USER = "x-access-token"

    suspend fun sync(
        repoUrl: String,
        token: String?,
        targetDir: File,
        onProgress: (percent: Int, phase: String) -> Unit
    ): SyncResult = withContext(Dispatchers.IO) {
        val url = repoUrl.trim()
        val credentials = token
            ?.takeIf { it.isNotBlank() }
            ?.let { UsernamePasswordCredentialsProvider(GITHUB_TOKEN_USER, it) }

        try {
            if (isGitRepo(targetDir)) {
                update(url, credentials, targetDir, onProgress)
            } else {
                clone(url, credentials, targetDir, onProgress)
            }
        } catch (e: Throwable) {
            SyncResult.Failed(describe(e))
        }
    }

    // ---------------------------------------------------------------- clone

    private fun clone(
        url: String,
        credentials: CredentialsProvider?,
        targetDir: File,
        onProgress: (Int, String) -> Unit
    ): SyncResult {
        if (targetDir.exists()) {
            val leftovers = targetDir.listFiles()?.filter { it.name != ".git" }.orEmpty()
            if (leftovers.isNotEmpty()) {
                return SyncResult.Failed(
                    "目标目录已存在且不是 Git 仓库：\n${targetDir.absolutePath}\n" +
                        "里面还有 ${leftovers.size} 个文件。请换一个目录，或手动清空后重试。"
                )
            }
        }
        if (!targetDir.exists() && !targetDir.mkdirs()) {
            return SyncResult.Failed("无法创建目录：${targetDir.absolutePath}")
        }

        onProgress(0, "克隆中")
        val command = Git.cloneRepository()
            .setURI(url)
            .setDirectory(targetDir)
            .setProgressMonitor(ProgressAdapter("克隆中", onProgress))
        credentials?.let { command.setCredentialsProvider(it) }

        command.call().close()

        return SyncResult.Success(targetDir, "克隆完成")
    }

    // --------------------------------------------------------------- update

    private fun update(
        url: String,
        credentials: CredentialsProvider?,
        targetDir: File,
        onProgress: (Int, String) -> Unit
    ): SyncResult {
        val git = Git.open(targetDir)
        try {
            // fetch 走 .git/config 里的 origin 地址，不校验就会出现「改了地址仍拉旧仓库」。
            val config = git.repository.config
            val origin = config.getString("remote", "origin", "url")
            when {
                origin.isNullOrBlank() -> {
                    config.setString("remote", "origin", "url", url)
                    config.save()
                }

                normalizeRepoUrl(origin) != normalizeRepoUrl(url) ->
                    return SyncResult.Failed(
                        "该目录已是另一个仓库的副本：\n$origin\n" +
                            "请换一个目录，或删掉该目录后重新同步。"
                    )

                origin != url -> {
                    config.setString("remote", "origin", "url", url)
                    config.save()
                }
            }

            onProgress(0, "拉取中")
            val fetch = git.fetch()
                .setRemote("origin")
                .setRefSpecs(RefSpec("+refs/heads/*:refs/remotes/origin/*"))
                .setRemoveDeletedRefs(true)
                .setProgressMonitor(ProgressAdapter("拉取中", onProgress))
            credentials?.let { fetch.setCredentialsProvider(it) }
            fetch.call()

            val branch = resolveDefaultBranch(git)
            onProgress(95, "应用更新")
            git.reset()
                .setMode(ResetCommand.ResetType.HARD)
                .setRef("refs/remotes/origin/$branch")
                .call()

            // 远端已删除的文件在本地会被清掉；不含 setIgnore(true)，
            // 所以 .gitignore 忽略的文件不会被误删。
            runCatching { git.clean().setCleanDirectories(true).call() }

            onProgress(100, "完成")
            return SyncResult.Success(targetDir, "已更新到最新（$branch）")
        } finally {
            git.close()
        }
    }

    // -------------------------------------------------------------- helpers

    private fun isGitRepo(dir: File): Boolean =
        dir.isDirectory && File(dir, ".git").exists()

    /**
     * 归一化后比较，忽略协议、凭据、.git 后缀、结尾斜杠与大小写差异，
     * 避免把同一个仓库的两种写法误判成两个仓库。
     *
     * https://github.com/o/r.git 与 git@github.com:o/r 都归一为 github.com/o/r
     */
    private fun normalizeRepoUrl(raw: String): String {
        var s = raw.trim()
        s = s.substringAfter("://", s)          // 去掉 https:// 之类协议头
        s = s.substringAfterLast('@', s)        // 去掉 userinfo（git@ 或 token@）
        s = s.replace(':', '/')                 // scp 风格 host:owner/repo
        s = s.trimEnd('/')
        if (s.endsWith(".git")) s = s.dropLast(4)
        return s.lowercase()
    }

    /**
     * origin/HEAD 在某些情况下不存在，按 main → master → 字典序第一个
     * 的优先级挑一个远端分支。
     */
    private fun resolveDefaultBranch(git: Git): String {
        val repo = git.repository
        val remotePrefix = "refs/remotes/origin/"

        runCatching {
            val refs = repo.refDatabase.getRefsByPrefix(remotePrefix)
                .filter { !it.name.endsWith("/HEAD") }
            repo.exactRef("${remotePrefix}HEAD")?.objectId?.let { headId ->
                refs.firstOrNull { it.objectId == headId }?.name
                    ?.removePrefix(remotePrefix)
                    ?.let { if (it.isNotBlank()) return it }
            }
        }

        val names = runCatching {
            git.branchList().setListMode(ListBranchCommand.ListMode.REMOTE).call()
                .map { it.name.removePrefix(remotePrefix) }
                .filter { it.isNotBlank() && it != "HEAD" }
        }.getOrDefault(emptyList())

        DEFAULT_BRANCH_CANDIDATES.split(',')
            .firstOrNull { it in names }
            ?.let { return it }

        return names.sorted().firstOrNull() ?: "main"
    }

    private fun describe(e: Throwable): String {
        val chain = buildList {
            var t: Throwable? = e
            var guard = 0
            while (t != null && guard < 8) {
                add(t)
                t = t.cause
                guard++
            }
        }

        return when {
            chain.any { it is RepositoryNotFoundException } ->
                "目标目录不是有效的 Git 仓库，建议删掉该目录后重新同步"

            chain.any { it is UnknownHostException } ->
                "无法连接服务器：域名解析失败，请检查网络"

            chain.any { it is SSLHandshakeException } ->
                "TLS 握手失败：证书校验未通过，可能被网络中间设备拦截"

            chain.any { it is SSLException } ->
                "TLS 连接异常：${e.message ?: "未知"}"

            chain.any { it is InvalidRemoteException } ->
                "仓库地址无效：${e.message ?: "未知"}"

            chain.any { it is TransportException } -> {
                val msg = e.message.orEmpty()
                val authHint = listOf("not authorized", "authentication", "401", "403", "denied")
                when {
                    authHint.any { msg.contains(it, ignoreCase = true) } ->
                        "认证失败：Token 无效或权限不足。\n" +
                            "请确认 Token 的 Repository permissions 里 Contents 为 Read，" +
                            "且 Repository access 包含该仓库。\n原始信息：$msg"

                    msg.contains("not found", ignoreCase = true) ->
                        "仓库不存在，或 Token 无权访问该仓库（私有仓库会返回 404）。\n原始信息：$msg"

                    else -> "网络传输失败：$msg"
                }
            }

            else -> "${e.javaClass.simpleName}: ${e.message ?: "未知错误"}"
        }
    }

    /**
     * JGit 的进度回调极其高频，直接推 UI 会造成 Compose 重组风暴，
     * 这里按「百分比变化 + 最小间隔 150ms」节流。
     */
    private class ProgressAdapter(
        private val phase: String,
        private val onProgress: (Int, String) -> Unit
    ) : ProgressMonitor {

        private var totalWork = 0
        private var lastPercent = -1
        private var lastEmitAt = 0L

        override fun start(totalTasks: Int) = Unit

        override fun beginTask(title: String, totalWork: Int) {
            this.totalWork = totalWork
            lastPercent = -1
        }

        override fun update(completed: Int) {
            if (totalWork <= 0) return
            val percent = (completed.toLong() * 100 / totalWork).toInt().coerceIn(0, 94)
            val now = System.currentTimeMillis()
            if (percent != lastPercent && now - lastEmitAt >= 150) {
                lastPercent = percent
                lastEmitAt = now
                onProgress(percent, phase)
            }
        }

        override fun endTask() = Unit

        override fun isCancelled(): Boolean = false
    }
}
