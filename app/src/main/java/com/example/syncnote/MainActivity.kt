package com.example.syncnote

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.syncnote.ui.theme.SyncNoteTheme
import kotlinx.coroutines.launch
import java.io.File

private const val PREFS_NAME = "syncnote"
private const val KEY_URL = "url"
private const val KEY_TOKEN = "token"
private const val KEY_DIR = "dir"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SyncNoteTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    SyncScreen(modifier = Modifier.padding(innerPadding))
                }
            }
        }
    }
}

private sealed interface UiState {
    data object Idle : UiState
    data class Running(val percent: Int, val phase: String) : UiState
    data class Done(val path: String, val message: String) : UiState
    data class Error(val message: String) : UiState
}

@Composable
fun SyncScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

    var url by remember { mutableStateOf(prefs.getString(KEY_URL, "").orEmpty()) }
    var token by remember { mutableStateOf(prefs.getString(KEY_TOKEN, "").orEmpty()) }
    var parentPath by remember { mutableStateOf(prefs.getString(KEY_DIR, "").orEmpty()) }
    var state by remember { mutableStateOf<UiState>(UiState.Idle) }
    var hint by remember { mutableStateOf<String?>(null) }

    val parsed = remember(url) { runCatching { PathResolver.parseRepoName(url) } }
    val repoName = parsed.getOrNull()
    val targetPath = if (parentPath.isBlank() || repoName == null) {
        null
    } else {
        PathResolver.targetDir(File(parentPath), repoName).absolutePath
    }
    val running = state is UiState.Running

    val pickDir = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            val dir = PathResolver.treeUriToDir(uri)
            if (dir == null) {
                state = UiState.Error("无法解析所选目录（可能在外接存储上），请改选内部存储中的目录")
            } else {
                parentPath = dir.absolutePath
                prefs.edit().putString(KEY_DIR, dir.absolutePath).apply()
                state = UiState.Idle
                hint = null
            }
        }
    }

    val requestLegacyPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result.values.all { it }) {
            pickDir.launch(null)
        } else {
            hint = "未授予存储权限，无法写入文件"
        }
    }

    // API 30+ 走「所有文件访问」授权页；26~29 走传统运行时权限。
    fun ensurePermission(proceed: () -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (Environment.isExternalStorageManager()) {
                proceed()
            } else {
                hint = "请打开「所有文件访问」权限，返回后再点一次"
                openAllFilesSettings(context)
            }
        } else {
            val granted = ContextCompat.checkSelfPermission(
                context, Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
            if (granted) {
                proceed()
            } else {
                requestLegacyPermission.launch(
                    arrayOf(
                        Manifest.permission.READ_EXTERNAL_STORAGE,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE
                    )
                )
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp)
    ) {
        Text("SyncNote", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(4.dp))
        Text(
            "把 GitHub 仓库同步到手机本地目录",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(24.dp))

        OutlinedTextField(
            value = url,
            onValueChange = {
                url = it
                if (state is UiState.Error) state = UiState.Idle
            },
            label = { Text("仓库地址") },
            placeholder = { Text("https://github.com/wangminghuang/Notes.git") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = token,
            onValueChange = { token = it },
            label = { Text("Token（私有仓库必填）") },
            placeholder = { Text("ghp_xxx 或 github_pat_xxx") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(16.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(
                onClick = {
                    ensurePermission { pickDir.launch(null) }
                },
                enabled = !running
            ) {
                Text("选择目录")
            }
            Spacer(Modifier.width(12.dp))
            Text(
                text = parentPath.ifBlank { "未选择" },
                style = MaterialTheme.typography.bodySmall,
                color = if (parentPath.isBlank()) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(Modifier.height(12.dp))

        Text(
            text = "将同步到：" + (targetPath ?: "—"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(24.dp))

        Button(
            onClick = {
                val parent = if (parentPath.isBlank()) null else File(parentPath)
                when {
                    parent == null -> state = UiState.Error("请先选择父目录")
                    repoName == null -> state = UiState.Error(
                        parsed.exceptionOrNull()?.message ?: "链接格式错误"
                    )
                    else -> ensurePermission {
                        prefs.edit()
                            .putString(KEY_URL, url)
                            .putString(KEY_TOKEN, token)
                            .apply()
                        state = UiState.Running(0, "准备中")
                        scope.launch {
                            val result = GitSync.sync(
                                repoUrl = url,
                                token = token.ifBlank { null },
                                targetDir = PathResolver.targetDir(parent, repoName)
                            ) { percent, phase ->
                                state = UiState.Running(percent, phase)
                            }
                            state = when (result) {
                                is SyncResult.Success -> UiState.Done(result.dir.absolutePath, result.message)
                                is SyncResult.Failed -> UiState.Error(result.message)
                            }
                        }
                    }
                }
            },
            enabled = !running,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (running) "同步中…" else "同步")
        }

        Spacer(Modifier.height(20.dp))

        when (val s = state) {
            is UiState.Idle -> Unit
            is UiState.Running -> {
                LinearProgressIndicator(
                    progress = { s.percent / 100f },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "${s.phase}… ${s.percent}%",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            is UiState.Done -> {
                Text(
                    "同步成功",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(4.dp))
                Text(s.message, style = MaterialTheme.typography.bodySmall)
                Text(
                    s.path,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            is UiState.Error -> {
                Text(
                    "同步失败",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.error
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    s.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }

        hint?.let {
            Spacer(Modifier.height(12.dp))
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

private fun openAllFilesSettings(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
    val candidates = listOf(
        Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.parse("package:${context.packageName}")
        ),
        Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
    )
    for (intent in candidates) {
        if (runCatching { context.startActivity(intent) }.isSuccess) return
    }
}
