package com.example.filemanager.presentation

import android.content.SharedPreferences
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import android.os.Build
import android.system.ErrnoException
import android.system.OsConstants
import com.example.filemanager.data.vfs.VfsManager
import com.example.filemanager.domain.vfs.VirtualFile
import java.net.URI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class FileEntryUi(
    val uri: URI,
    val name: String,
    val isDirectory: Boolean,
    val size: Long,
    val lastModified: Long,
)

enum class SortOption(val label: String) {
    ByName("按名称"),
    ByTime("按时间"),
    BySize("按大小"),
    ;

    companion object {
        fun fromNameOrDefault(name: String?): SortOption {
            return entries.firstOrNull { it.name == name } ?: ByName
        }
    }
}

data class FileBrowserPreferences(
    val isSearchExpanded: Boolean = false,
    val searchQuery: String = "",
    val sortOption: SortOption = SortOption.ByName,
    val showHiddenFiles: Boolean = false,
)

sealed class ClipboardOp {
    data class Copy(val sources: List<URI>) : ClipboardOp()
    data class Cut(val sources: List<URI>) : ClipboardOp()
}

data class TextViewerState(
    val title: String,
    val content: String,
)

sealed interface FileBrowserUiState {
    data object NoSelection : FileBrowserUiState

    sealed interface HasDir : FileBrowserUiState {
        val currentDir: URI
        val canGoBack: Boolean
        val clipboard: ClipboardOp?
        val textViewer: TextViewerState?
    }

    data class Loading(
        override val currentDir: URI,
        override val canGoBack: Boolean = false,
        override val clipboard: ClipboardOp? = null,
        override val textViewer: TextViewerState? = null,
    ) : HasDir

    data class Success(
        override val currentDir: URI,
        val allEntries: List<FileEntryUi>,
        val entries: List<FileEntryUi>,
        override val canGoBack: Boolean = false,
        override val clipboard: ClipboardOp? = null,
        override val textViewer: TextViewerState? = null,
    ) : HasDir

    data class Empty(
        override val currentDir: URI,
        val allEntries: List<FileEntryUi>,
        override val canGoBack: Boolean = false,
        override val clipboard: ClipboardOp? = null,
        override val textViewer: TextViewerState? = null,
    ) : HasDir

    data class Error(
        override val currentDir: URI,
        val message: String,
        override val canGoBack: Boolean = false,
        override val clipboard: ClipboardOp? = null,
        override val textViewer: TextViewerState? = null,
    ) : HasDir
}

class FileBrowserViewModel(
    private val vfs: VfsManager,
    private val prefs: SharedPreferences,
) : ViewModel() {

    private val _uiState = MutableStateFlow<FileBrowserUiState>(FileBrowserUiState.NoSelection)
    val uiState: StateFlow<FileBrowserUiState> = _uiState.asStateFlow()

    private val _preferences = MutableStateFlow(loadPreferences())
    val preferences: StateFlow<FileBrowserPreferences> = _preferences.asStateFlow()

    private val backStack = ArrayDeque<URI>()

    private fun loadPreferences(): FileBrowserPreferences {
        return FileBrowserPreferences(
            isSearchExpanded = false,
            searchQuery = "",
            sortOption = SortOption.fromNameOrDefault(prefs.getString(PREF_KEY_SORT_OPTION, null)),
            showHiddenFiles = prefs.getBoolean(PREF_KEY_SHOW_HIDDEN_FILES, false),
        )
    }

    private fun persistSortOption(option: SortOption) {
        prefs.edit().putString(PREF_KEY_SORT_OPTION, option.name).apply()
    }

    private fun persistShowHiddenFiles(enabled: Boolean) {
        prefs.edit().putBoolean(PREF_KEY_SHOW_HIDDEN_FILES, enabled).apply()
    }

    private fun matchesQuery(name: String, rawQuery: String): Boolean {
        val q = rawQuery.trim()
        if (q.isBlank()) return true

        // 直接匹配：保留用户输入（例如包含 '.'、空格等）
        if (name.contains(q, ignoreCase = true)) return true

        // 隐藏文件名前缀 '.' 的“归一化”匹配：
        // - 搜索 `trash` 应能命中 `.trash`
        // - 搜索 `.trash` 也应能命中 `trash`（用户有时会带点输入）
        val q2 = q.removePrefix(".").lowercase()
        if (q2.isBlank()) return false
        val n2 = name.removePrefix(".").lowercase()
        return n2.contains(q2)
    }

    private fun applyPreferences(all: List<FileEntryUi>, p: FileBrowserPreferences): List<FileEntryUi> {
        val q = p.searchQuery.trim()
        val filtered = all.asSequence()
            .filter { entry ->
                // 仅在“未搜索”状态下，才受「显示隐藏文件」开关影响。
                // 用户主动搜索时，应允许命中隐藏文件（例如搜索 `trash` 命中 `.trash`）。
                q.isNotBlank() || p.showHiddenFiles || !entry.name.startsWith('.')
            }
            .filter { entry ->
                matchesQuery(entry.name, q)
            }

        val base = compareByDescending<FileEntryUi> { it.isDirectory }
        val comparator = when (p.sortOption) {
            SortOption.ByName -> base.thenBy { it.name.lowercase() }
            SortOption.ByTime -> base.thenByDescending { it.lastModified }.thenBy { it.name.lowercase() }
            SortOption.BySize -> base.thenByDescending { it.size }.thenBy { it.name.lowercase() }
        }

        return filtered.sortedWith(comparator).toList()
    }

    private fun recomputeVisibleEntries() {
        val s = _uiState.value as? FileBrowserUiState.HasDir ?: return
        val all = when (s) {
            is FileBrowserUiState.Success -> s.allEntries
            is FileBrowserUiState.Empty -> s.allEntries
            else -> return
        }
        val visible = applyPreferences(all, _preferences.value)
        _uiState.value = if (visible.isEmpty()) {
            FileBrowserUiState.Empty(
                currentDir = s.currentDir,
                allEntries = all,
                canGoBack = s.canGoBack,
                clipboard = s.clipboard,
                textViewer = s.textViewer,
            )
        } else {
            FileBrowserUiState.Success(
                currentDir = s.currentDir,
                allEntries = all,
                entries = visible,
                canGoBack = s.canGoBack,
                clipboard = s.clipboard,
                textViewer = s.textViewer,
            )
        }
    }

    private fun FileBrowserUiState.HasDir.copyCommon(
        canGoBack: Boolean = this.canGoBack,
        clipboard: ClipboardOp? = this.clipboard,
        textViewer: TextViewerState? = this.textViewer,
    ): FileBrowserUiState.HasDir {
        return when (this) {
            is FileBrowserUiState.Loading -> copy(canGoBack = canGoBack, clipboard = clipboard, textViewer = textViewer)
            is FileBrowserUiState.Success -> copy(canGoBack = canGoBack, clipboard = clipboard, textViewer = textViewer)
            is FileBrowserUiState.Empty -> copy(canGoBack = canGoBack, clipboard = clipboard, textViewer = textViewer)
            is FileBrowserUiState.Error -> copy(canGoBack = canGoBack, clipboard = clipboard, textViewer = textViewer)
        }
    }

    private fun setDirStateLoading(dir: URI, canGoBack: Boolean, clipboard: ClipboardOp? = null) {
        _uiState.value = FileBrowserUiState.Loading(
            currentDir = dir,
            canGoBack = canGoBack,
            clipboard = clipboard,
            textViewer = null,
        )
    }

    private fun setDirStateLoaded(
        dir: URI,
        allEntries: List<FileEntryUi>,
        canGoBack: Boolean,
        clipboard: ClipboardOp?,
        textViewer: TextViewerState? = null,
    ) {
        val visible = applyPreferences(allEntries, _preferences.value)
        _uiState.value = if (visible.isEmpty()) {
            FileBrowserUiState.Empty(
                currentDir = dir,
                allEntries = allEntries,
                canGoBack = canGoBack,
                clipboard = clipboard,
                textViewer = textViewer,
            )
        } else {
            FileBrowserUiState.Success(
                currentDir = dir,
                allEntries = allEntries,
                entries = visible,
                canGoBack = canGoBack,
                clipboard = clipboard,
                textViewer = textViewer,
            )
        }
    }

    private fun setDirStateError(dir: URI, message: String, canGoBack: Boolean, clipboard: ClipboardOp?) {
        _uiState.value = FileBrowserUiState.Error(
            currentDir = dir,
            message = message,
            canGoBack = canGoBack,
            clipboard = clipboard,
            textViewer = null,
        )
    }

    private fun Throwable.isPermissionDenied(): Boolean {
        if (this is SecurityException) return true

        // java.nio.file.AccessDeniedException 仅在 API 26+ 可用，避免在 24/25 上直接引用导致 ClassNotFound。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (this.javaClass.name == "java.nio.file.AccessDeniedException") return true
        }

        // 一些底层 I/O 会以 ErrnoException(EACCES/EPERM) 的形式抛出。
        if (this is ErrnoException && (this.errno == OsConstants.EACCES || this.errno == OsConstants.EPERM)) {
            return true
        }

        return cause?.isPermissionDenied() == true
    }

    private fun errorMessageFor(e: Throwable): String {
        return if (e.isPermissionDenied()) {
            "权限被拒绝，请授权。"
        } else {
            e.message ?: e.toString()
        }
    }

    fun setRoot(uri: URI) {
        // setRoot 作为入口，必须兜底捕获（避免无权限导致的 SecurityException / AccessDeniedException 直接闪退）。
        runCatching {
            backStack.clear()
            setDirStateLoading(dir = uri, canGoBack = false)
            refresh()
        }.onFailure { e ->
            setDirStateError(uri, errorMessageFor(e), canGoBack = false, clipboard = null)
        }
    }

    fun refresh() {
        val s = _uiState.value as? FileBrowserUiState.HasDir ?: return
        val dirUri = s.currentDir
        viewModelScope.launch {
            runCatching {
                loadDirectory(dirUri)
            }.onSuccess { list ->
                setDirStateLoaded(
                    dir = dirUri,
                    allEntries = list,
                    canGoBack = s.canGoBack,
                    clipboard = s.clipboard,
                    textViewer = s.textViewer,
                )
            }.onFailure { e ->
                setDirStateError(
                    dir = dirUri,
                    message = errorMessageFor(e),
                    canGoBack = s.canGoBack,
                    clipboard = s.clipboard,
                )
            }
        }
    }

    fun toggleSearch() {
        _preferences.update { p ->
            if (p.isSearchExpanded) {
                p.copy(isSearchExpanded = false, searchQuery = "")
            } else {
                p.copy(isSearchExpanded = true)
            }
        }
        recomputeVisibleEntries()
    }

    fun setSearchQuery(query: String) {
        _preferences.update { it.copy(searchQuery = query) }
        recomputeVisibleEntries()
    }

    fun clearSearch() {
        _preferences.update { it.copy(searchQuery = "") }
        recomputeVisibleEntries()
    }

    fun setSortOption(option: SortOption) {
        _preferences.update { it.copy(sortOption = option) }
        persistSortOption(option)
        recomputeVisibleEntries()
    }

    fun setShowHiddenFiles(enabled: Boolean) {
        _preferences.update { it.copy(showHiddenFiles = enabled) }
        persistShowHiddenFiles(enabled)
        recomputeVisibleEntries()
    }

    fun openDirectory(uri: URI) {
        val current = (_uiState.value as? FileBrowserUiState.HasDir)?.currentDir
        if (current != null) backStack.addLast(current)

        val clipboard = (_uiState.value as? FileBrowserUiState.HasDir)?.clipboard
        setDirStateLoading(dir = uri, canGoBack = backStack.isNotEmpty(), clipboard = clipboard)
        refresh()
    }

    fun goBack(): Boolean {
        val prev = backStack.removeLastOrNull() ?: return false
        val clipboard = (_uiState.value as? FileBrowserUiState.HasDir)?.clipboard
        setDirStateLoading(dir = prev, canGoBack = backStack.isNotEmpty(), clipboard = clipboard)
        refresh()
        return true
    }

    fun clearClipboard() {
        _uiState.update { s ->
            when (s) {
                FileBrowserUiState.NoSelection -> s
                is FileBrowserUiState.HasDir -> s.copyCommon(clipboard = null)
            }
        }
    }

    fun copyToClipboard(vararg uris: URI) {
        _uiState.update { s ->
            when (s) {
                FileBrowserUiState.NoSelection -> s
                is FileBrowserUiState.HasDir -> s.copyCommon(clipboard = ClipboardOp.Copy(uris.toList()))
            }
        }
    }

    fun cutToClipboard(vararg uris: URI) {
        _uiState.update { s ->
            when (s) {
                FileBrowserUiState.NoSelection -> s
                is FileBrowserUiState.HasDir -> s.copyCommon(clipboard = ClipboardOp.Cut(uris.toList()))
            }
        }
    }

    fun pasteIntoCurrentDir() {
        val s = _uiState.value as? FileBrowserUiState.HasDir ?: return
        val op = s.clipboard ?: return
        val destDirUri = s.currentDir
        viewModelScope.launch {
            setDirStateLoading(dir = destDirUri, canGoBack = s.canGoBack, clipboard = op)
            try {
                when (op) {
                    is ClipboardOp.Copy -> {
                        for (src in op.sources) {
                            copyNode(src, destDirUri, deleteSource = false)
                        }
                    }
                    is ClipboardOp.Cut -> {
                        for (src in op.sources) {
                            copyNode(src, destDirUri, deleteSource = true)
                        }
                        clearClipboard()
                    }
                }
                refresh()
            } catch (e: CancellationException) {
                setDirStateError(destDirUri, "操作已取消", canGoBack = s.canGoBack, clipboard = s.clipboard)
            } catch (e: Exception) {
                setDirStateError(destDirUri, e.message ?: e.toString(), canGoBack = s.canGoBack, clipboard = s.clipboard)
            }
        }
    }

    fun delete(uri: URI) {
        viewModelScope.launch {
            val s = _uiState.value as? FileBrowserUiState.HasDir ?: return@launch
            val dir = s.currentDir
            setDirStateLoading(dir = dir, canGoBack = s.canGoBack, clipboard = s.clipboard)
            runCatching { vfs.delete(uri) }
                .onFailure { e ->
                    setDirStateError(dir, e.message ?: e.toString(), canGoBack = s.canGoBack, clipboard = s.clipboard)
                    return@launch
                }
            refresh()
        }
    }

    fun rename(uri: URI, newName: String) {
        viewModelScope.launch {
            val s = _uiState.value as? FileBrowserUiState.HasDir ?: return@launch
            val dir = s.currentDir
            setDirStateLoading(dir = dir, canGoBack = s.canGoBack, clipboard = s.clipboard)
            runCatching { vfs.renameTo(uri, newName) }
                .onFailure { e ->
                    setDirStateError(dir, e.message ?: e.toString(), canGoBack = s.canGoBack, clipboard = s.clipboard)
                    return@launch
                }
            refresh()
        }
    }

    fun createFolder(displayName: String) {
        val s = _uiState.value as? FileBrowserUiState.HasDir ?: return
        val parent = s.currentDir
        viewModelScope.launch {
            setDirStateLoading(dir = parent, canGoBack = s.canGoBack, clipboard = s.clipboard)
            runCatching { vfs.createChildDirectory(parent, displayName) }
                .onFailure { e ->
                    setDirStateError(parent, e.message ?: e.toString(), canGoBack = s.canGoBack, clipboard = s.clipboard)
                    return@launch
                }
            refresh()
        }
    }

    fun openAsText(uri: URI) {
        viewModelScope.launch {
            runCatching {
                val file = vfs.getFile(uri)
                val title = file.name
                val content = readTextPreview(file, maxBytes = 256 * 1024)
                TextViewerState(title = title, content = content)
            }.onSuccess { tv ->
                val s = _uiState.value as? FileBrowserUiState.HasDir
                if (s == null) {
                    _uiState.value = FileBrowserUiState.NoSelection
                    return@onSuccess
                }
                // 读取文本后，为避免丢失当前目录列表，重新加载目录并把 textViewer 合并到状态里。
                runCatching {
                    loadDirectory(s.currentDir)
                }.onSuccess { list ->
                    setDirStateLoaded(
                        dir = s.currentDir,
                        allEntries = list,
                        canGoBack = s.canGoBack,
                        clipboard = s.clipboard,
                        textViewer = tv,
                    )
                }.onFailure { e ->
                    setDirStateError(
                        dir = s.currentDir,
                        message = errorMessageFor(e),
                        canGoBack = s.canGoBack,
                        clipboard = s.clipboard,
                    )
                }
            }.onFailure { e ->
                val s = _uiState.value as? FileBrowserUiState.HasDir ?: return@onFailure
                setDirStateError(s.currentDir, errorMessageFor(e), canGoBack = s.canGoBack, clipboard = s.clipboard)
            }
        }
    }

    fun dismissTextViewer() {
        _uiState.update { s ->
            when (s) {
                FileBrowserUiState.NoSelection -> s
                is FileBrowserUiState.HasDir -> s.copyCommon(textViewer = null)
            }
        }
    }

    private suspend fun loadDirectory(dirUri: URI): List<FileEntryUi> {
        return withContext(Dispatchers.IO) {
            val children = vfs.listChildren(dirUri)
            val mapped = children.map { vf ->
                val isDir = vf.isDirectory()
                FileEntryUi(
                    uri = vf.uri,
                    name = vf.name,
                    isDirectory = isDir,
                    size = if (isDir) 0L else vf.length(),
                    lastModified = vf.lastModified(),
                )
            }
            mapped
        }
    }

    private suspend fun readTextPreview(file: VirtualFile, maxBytes: Int): String {
        return withContext(Dispatchers.IO) {
            file.openInputStream().use { input ->
                val buffer = ByteArray(8 * 1024)
                val out = java.io.ByteArrayOutputStream()
                var remaining = maxBytes
                while (remaining > 0) {
                    val read = input.read(buffer, 0, minOf(buffer.size, remaining))
                    if (read <= 0) break
                    out.write(buffer, 0, read)
                    remaining -= read
                }
                val bytes = out.toByteArray()
                val truncated = remaining == 0 && input.read() != -1
                val text = runCatching { bytes.toString(Charsets.UTF_8) }
                    .getOrDefault("(非 UTF-8 文本或二进制文件)")
                if (truncated) text + "\n\n…(已截断)" else text
            }
        }
    }

    private suspend fun copyNode(sourceUri: URI, destDirUri: URI, deleteSource: Boolean) {
        val src = vfs.getFile(sourceUri)
        val destDir = vfs.getFile(destDirUri)
        require(destDir.isDirectory()) { "目标不是目录：$destDirUri" }

        if (src.isDirectory()) {
            val targetDirName = uniqueChildName(destDirUri, src.name)
            val createdDir = vfs.createChildDirectory(destDirUri, targetDirName)
            // 由于 VirtualFile.listFiles() 是 Flow，这里转为 List 便于递归
            val actualChildren = src.listFiles().toList()
            for (child in actualChildren) {
                copyNode(child.uri, createdDir.uri, deleteSource = deleteSource)
            }
            if (deleteSource) {
                vfs.delete(sourceUri)
            }
            return
        }

        // 文件复制
        val targetName = uniqueChildName(destDirUri, src.name)
        val destFile = vfs.createChildFile(destDirUri, targetName)
        vfs.copy(src, destFile)

        if (deleteSource) {
            vfs.delete(sourceUri)
        }
    }

    private suspend fun uniqueChildName(destDirUri: URI, baseName: String): String {
        val existing = vfs.listChildren(destDirUri).map { it.name }.toHashSet()
        if (baseName !in existing) return baseName

        val dot = baseName.lastIndexOf('.')
        val stem = if (dot > 0) baseName.substring(0, dot) else baseName
        val ext = if (dot > 0) baseName.substring(dot) else ""

        var i = 1
        while (true) {
            val candidate = "$stem ($i)$ext"
            if (candidate !in existing) return candidate
            i++
        }
    }
}

class FileBrowserViewModelFactory(
    private val vfs: VfsManager,
    private val prefs: SharedPreferences,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(FileBrowserViewModel::class.java))
        return FileBrowserViewModel(vfs, prefs) as T
    }
}

private const val PREF_KEY_SORT_OPTION = "sort_option"
private const val PREF_KEY_SHOW_HIDDEN_FILES = "show_hidden_files"
