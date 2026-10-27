package com.example.syncnote

import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import java.io.File

/**
 * 路径相关工具：从仓库地址解析仓库名、把 SAF 树 URI 还原成真实目录。
 */
object PathResolver {

    /** 仓库名允许的字符集，用于挡住 `.`、`..`、带斜杠等会造成目录穿越的名字。 */
    private val SAFE_NAME = Regex("^[A-Za-z0-9._-]+$")

    /**
     * 从仓库地址解析出仓库名。
     *
     * https://github.com/wangminghuang/Notes.git -> Notes
     * git@github.com:wangminghuang/Notes.git     -> Notes
     */
    fun parseRepoName(rawUrl: String): String {
        val url = rawUrl.trim()
        require(url.isNotEmpty()) { "请输入仓库地址" }

        val rawPath: String = if (!url.contains("://") && url.contains(':')) {
            // SCP 风格：git@github.com:owner/repo.git
            url.substringAfter(':')
        } else {
            url.substringBefore('#').substringBefore('?').let { Uri.parse(it).path.orEmpty() }
        }

        val segments = rawPath.split('/').filter { it.isNotBlank() }
        require(segments.size >= 2) {
            "链接不完整，应形如 https://github.com/用户名/仓库名"
        }

        val name = segments.last().removeSuffix(".git")
        require(name.isNotEmpty()) { "无法从链接中解析出仓库名" }
        require(name != "." && name != ".." && SAFE_NAME.matches(name)) {
            "仓库名包含非法字符：$name"
        }
        return name
    }

    /** 目标目录 = 用户选中的父目录 + 仓库名，只加这一层。 */
    fun targetDir(parent: File, repoName: String): File = File(parent, repoName)

    /**
     * 把 SAF 返回的目录树 URI 还原成真实路径。
     *
     * 形如 content://com.android.externalstorage.documents/tree/primary%3Atmp
     * 的 docId 是 "primary:tmp"，映射到 /storage/emulated/0/tmp。
     *
     * @return 解析失败返回 null（例如某些厂商 ROM 或外接存储卷）
     */
    fun treeUriToDir(uri: Uri): File? {
        if (uri.scheme == "file") return uri.path?.let { File(it) }

        val docId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull()
            ?: return null

        val volume = docId.substringBefore(':', "")
        val relative = docId.substringAfter(':', "").trim('/')

        val base = when {
            volume.equals("primary", ignoreCase = true) -> Environment.getExternalStorageDirectory()
            volume.equals("home", ignoreCase = true) ->
                File(Environment.getExternalStorageDirectory(), Environment.DIRECTORY_DOCUMENTS)
            volume.isEmpty() -> return null
            else -> File("/storage/$volume")
        }

        return if (relative.isEmpty()) base else File(base, relative)
    }
}
