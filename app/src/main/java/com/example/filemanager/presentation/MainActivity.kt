package com.example.filemanager.presentation

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.graphics.Color
import com.example.filemanager.data.vfs.VfsManager
import java.net.URI

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val vfs = VfsManager.createDefault(applicationContext)

        setContent {
            // “屠紫”行动：禁用 Dynamic Color（不使用 dynamic*ColorScheme），并强制使用极客灰阶配色。
            val colorScheme = if (isSystemInDarkTheme()) GeekDarkColorScheme else GeekLightColorScheme

            MaterialTheme(colorScheme = colorScheme) {
                Surface(color = colorScheme.background) {
                    val vm: FileBrowserViewModel = viewModel(factory = FileBrowserViewModelFactory(vfs, prefs))

                    val lifecycleOwner = LocalLifecycleOwner.current
                    var hasAllFilesAccess by remember { mutableStateOf(checkAllFilesAccess()) }
                    DisposableEffect(lifecycleOwner) {
                        val observer = LifecycleEventObserver { _, event ->
                            if (event == Lifecycle.Event.ON_RESUME) {
                                hasAllFilesAccess = checkAllFilesAccess()
                            }
                        }
                        lifecycleOwner.lifecycle.addObserver(observer)
                        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
                    }

                    val pickTreeLauncher = rememberLauncherForActivityResult(
                        contract = ActivityResultContracts.OpenDocumentTree(),
                    ) { treeUri ->
                        if (treeUri != null) {
                            // 持久化授权，避免下次启动丢失。
                            runCatching {
                                contentResolver.takePersistableUriPermission(
                                    treeUri,
                                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                                )
                            }
                            prefs.edit().putString(PREF_KEY_SAF_ROOT, treeUri.toString()).apply()
                            vm.setRoot(URI(treeUri.toString()))
                        }
                    }

                    val requestReadStorageLauncher = rememberLauncherForActivityResult(
                        contract = ActivityResultContracts.RequestPermission(),
                    ) { granted ->
                        if (granted) {
                            // 只有在确实拿到权限后，才允许打开本地根目录。
                            vm.setRoot(Environment.getExternalStorageDirectory().toURI())
                        }
                    }

                    // 启动时：
                    // - E2E 测试模式：强制使用应用内部目录作为根目录（无需外部存储权限，便于 Firebase Test Lab 稳定跑通）。
                    // - 否则：优先使用上次选择的 SAF 根目录；否则尝试打开本地根目录。
                    LaunchedEffect(Unit) {
                        if (intent?.getBooleanExtra(EXTRA_E2E_USE_INTERNAL_ROOT, false) == true) {
                            vm.setRoot(filesDir.toURI())
                            return@LaunchedEffect
                        }

                        val savedSaf = prefs.getString(PREF_KEY_SAF_ROOT, null)
                        if (!savedSaf.isNullOrBlank()) {
                            vm.setRoot(URI(savedSaf))
                        } else {
                            // 没有 SAF 根目录时：
                            // - Android 11+ 若无“全盘访问”，保持空状态，引导用户选择目录或开启权限。
                            // - 否则默认打开本地 external storage 根目录。
                            if (checkAllFilesAccess()) {
                                val localRoot = Environment.getExternalStorageDirectory().toURI()
                                vm.setRoot(localRoot)
                            }
                        }
                    }

                    FileBrowserScreen(
                        viewModel = vm,
                        onPickSafDirectory = {
                            pickTreeLauncher.launch(null)
                        },
                        onGoLocalRoot = {
                            // 无权限时绝不触发对本地根目录的访问（避免真机首次启动崩溃/沙箱异常）。
                            if (hasAllFilesAccess) {
                                vm.setRoot(Environment.getExternalStorageDirectory().toURI())
                            } else {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                    startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                                } else {
                                    requestReadStorageLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
                                }
                            }
                        },
                        hasAllFilesAccess = hasAllFilesAccess,
                        onRequestAllFilesAccess = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                            }
                        },
                    )
                }
            }
        }
    }

    private fun checkAllFilesAccess(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            // Android 10 及以下：需要传统存储权限（首次安装未授权时必须为 false）。
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.READ_EXTERNAL_STORAGE,
            ) == PackageManager.PERMISSION_GRANTED
        }
    }
}

private const val PREFS_NAME = "file_manager_prefs"
private const val PREF_KEY_SAF_ROOT = "saf_root_uri"

internal const val EXTRA_E2E_USE_INTERNAL_ROOT = "e2e_use_internal_root"

// 纯灰阶/淡灰：确保 primary/secondary/containers 等不再出现默认 Material3 紫色。
private val GeekLightColorScheme = lightColorScheme(
    primary = Color(0xFF4A4A4A),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE2E2E2),
    onPrimaryContainer = Color(0xFF111111),
    secondary = Color(0xFF616161),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFDADADA),
    onSecondaryContainer = Color(0xFF111111),
    tertiary = Color(0xFF757575),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFE6E6E6),
    onTertiaryContainer = Color(0xFF111111),
    background = Color(0xFFF5F5F5),
    onBackground = Color(0xFF111111),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF111111),
    surfaceVariant = Color(0xFFEDEDED),
    onSurfaceVariant = Color(0xFF444444),
    outline = Color(0xFFB0B0B0),
)

private val GeekDarkColorScheme = darkColorScheme(
    primary = Color(0xFFE0E0E0),
    onPrimary = Color(0xFF111111),
    primaryContainer = Color(0xFF2C2C2C),
    onPrimaryContainer = Color(0xFFEDEDED),
    secondary = Color(0xFFCCCCCC),
    onSecondary = Color(0xFF111111),
    secondaryContainer = Color(0xFF343434),
    onSecondaryContainer = Color(0xFFEDEDED),
    tertiary = Color(0xFFBDBDBD),
    onTertiary = Color(0xFF111111),
    tertiaryContainer = Color(0xFF3A3A3A),
    onTertiaryContainer = Color(0xFFEDEDED),
    background = Color(0xFF111111),
    onBackground = Color(0xFFEDEDED),
    surface = Color(0xFF1A1A1A),
    onSurface = Color(0xFFEDEDED),
    surfaceVariant = Color(0xFF2A2A2A),
    onSurfaceVariant = Color(0xFFCCCCCC),
    outline = Color(0xFF6E6E6E),
)
