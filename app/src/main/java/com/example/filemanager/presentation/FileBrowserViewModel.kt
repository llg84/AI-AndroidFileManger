package com.example.filemanager.presentation

import android.content.SharedPreferences
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.compose.runtime.Immutable
import android.os.Build
import android.os.SystemClock
import android.system.ErrnoException
import android.system.OsConstants
import com.example.filemanager.data.vfs.VfsManager
import com.example.filemanager.domain.vfs.VirtualFile
import java.io.File
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

@Immutable
data class FileEntryUi(
    val uri: URI,
    /** 原始文件名（用于重命名输入、后缀判断等）。 */
    val name: String,
    /**
     * 列表展示名：为彻底解决中英文混排/长单词导致的异常提前换行，强制在每个字符之间插入 \u200B。
     * 注意：仅用于 UI 展示；逻辑侧一律使用 [name]。
     */
    val displayName: String,
    /** 列表副标题：包含类型/大小 + 预格式化时间字符串。 */
    val subtitle: String,
    val isDirectory: Boolean,
    val size: Long,
    val lastModified: Long,
    /** 过滤/排序用的预计算 key，避免每次 applyPreferences 反复 lowercase/trim。 */
    val nameLower: String,
    val nameLowerNoDot: String,
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

@Immutable
data class MultiSelectionState(
    val enabled: Boolean = false,
    val selectedUris: Set<URI> = emptySet(),
) {
    val selectedCount: Int get() = selectedUris.size
}

sealed class ClipboardOp {
    data class Copy(val sources: List<URI>) : ClipboardOp()
    data class Cut(val sources: List<URI>) : ClipboardOp()
}

data class TextViewerState(
    val title: String,
    val content: String,
)

@Immutable
data class CopyProgressUi(
    val currentName: String,
    val bytesCopied: Long,
    /** 若未知则为 -1。 */
    val totalBytes: Long,
    /** 例如："复制" / "创建目录" */
    val stageLabel: String,
)

@Immutable
data class FtpHistoryEntry(
    val name: String,
    val host: String,
    val port: Int,
    val username: String,
    val password: String,
    val remotePath: String,
    val lastConnectedAt: Long,
) {
    val dedupKey: String
        get() = "$host:$port|$username|${normalizeFtpRemotePath(remotePath)}"
}

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

    private val _ftpHistory = MutableStateFlow(loadFtpHistoryFromPrefs())
    val ftpHistory: StateFlow<List<FtpHistoryEntry>> = _ftpHistory.asStateFlow()

    private var pendingFtpConnection: FtpHistoryEntry? = null

    private val _multiSelection = MutableStateFlow(MultiSelectionState())
    val multiSelection: StateFlow<MultiSelectionState> = _multiSelection.asStateFlow()

    private val _copyProgress = MutableStateFlow<CopyProgressUi?>(null)
    val copyProgress: StateFlow<CopyProgressUi?> = _copyProgress.asStateFlow()

    private var pasteJob: Job? = null

    private val backStack = ArrayDeque<URI>()

    // 过滤/排序可能较重：用可取消的后台 Job 避免在主线程重复计算（例如快速输入搜索）。
    private var recomputeJob: Job? = null

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

    private fun matchesQuery(entry: FileEntryUi, rawQuery: String): Boolean {
        val q = rawQuery.trim()
        if (q.isBlank()) return true

        // 直接匹配：保留用户输入（例如包含 '.'、空格等）
        val qLower = q.lowercase(Locale.ROOT)
        if (entry.nameLower.contains(qLower)) return true

        // 隐藏文件名前缀 '.' 的“归一化”匹配：
        // - 搜索 `trash` 应能命中 `.trash`
        // - 搜索 `.trash` 也应能命中 `trash`
        val q2 = qLower.removePrefix(".")
        if (q2.isBlank()) return false
        return entry.nameLowerNoDot.contains(q2)
    }

    private fun buildLoadedState(
        dir: URI,
        allEntries: List<FileEntryUi>,
        visibleEntries: List<FileEntryUi>,
        canGoBack: Boolean,
        clipboard: ClipboardOp?,
        textViewer: TextViewerState?,
    ): FileBrowserUiState.HasDir {
        return if (visibleEntries.isEmpty()) {
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
                entries = visibleEntries,
                canGoBack = canGoBack,
                clipboard = clipboard,
                textViewer = textViewer,
            )
        }
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
                matchesQuery(entry, q)
            }

        val base = compareByDescending<FileEntryUi> { it.isDirectory }
        val comparator = when (p.sortOption) {
            SortOption.ByName -> base.thenBy { it.nameLower }
            SortOption.ByTime -> base.thenByDescending { it.lastModified }.thenBy { it.nameLower }
            SortOption.BySize -> base.thenByDescending { it.size }.thenBy { it.nameLower }
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

        val dir = s.currentDir
        val canGoBack = s.canGoBack
        val clipboard = s.clipboard
        val textViewer = s.textViewer
        val prefsSnapshot = _preferences.value

        recomputeJob?.cancel()
        recomputeJob = viewModelScope.launch {
            val visible = withContext(Dispatchers.Default) { applyPreferences(all, prefsSnapshot) }

            // 防止 stale：目录/数据源发生变化时，丢弃旧计算结果。
            val latest = _uiState.value as? FileBrowserUiState.HasDir ?: return@launch
            if (latest.currentDir != dir) return@launch
            val latestAll = when (latest) {
                is FileBrowserUiState.Success -> latest.allEntries
                is FileBrowserUiState.Empty -> latest.allEntries
                else -> return@launch
            }
            if (latestAll !== all) return@launch

            _uiState.value = buildLoadedState(
                dir = dir,
                allEntries = all,
                visibleEntries = visible,
                canGoBack = canGoBack,
                clipboard = clipboard,
                textViewer = textViewer,
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
            // 切换根目录时保留剪贴板：支持“从 FTP 复制 -> 切回本地 -> 粘贴”。
            val clipboard = (_uiState.value as? FileBrowserUiState.HasDir)?.clipboard
            backStack.clear()
            exitMultiSelection()
            setDirStateLoading(dir = uri, canGoBack = false, clipboard = clipboard)
            refresh()
        }.onFailure { e ->
            val clipboard = (_uiState.value as? FileBrowserUiState.HasDir)?.clipboard
            setDirStateError(uri, errorMessageFor(e), canGoBack = false, clipboard = clipboard)
        }
    }

    fun refresh() {
        val s = _uiState.value as? FileBrowserUiState.HasDir ?: return
        val dirUri = s.currentDir
        viewModelScope.launch {
            runCatching {
                loadDirectory(dirUri)
            }.onSuccess { list ->
                pruneSelectionIfNeeded(currentDir = dirUri, allEntries = list)
                val prefsSnapshot = _preferences.value
                val visible = withContext(Dispatchers.Default) { applyPreferences(list, prefsSnapshot) }
                _uiState.value = buildLoadedState(
                    dir = dirUri,
                    allEntries = list,
                    visibleEntries = visible,
                    canGoBack = s.canGoBack,
                    clipboard = s.clipboard,
                    textViewer = s.textViewer,
                )

                // 仅在“连接成功”这一刻落盘 FTP 历史：避免目录跳转/刷新导致频繁覆盖。
                maybePersistFtpHistoryOnSuccess(dirUri)
            }.onFailure { e ->
                // 连接失败则丢弃 pending，避免后续误落盘。
                if (dirUri.scheme == "ftp") {
                    pendingFtpConnection = null
                }
                setDirStateError(
                    dir = dirUri,
                    message = errorMessageFor(e),
                    canGoBack = s.canGoBack,
                    clipboard = s.clipboard,
                )
            }
        }
    }

    /**
     * 由 UI 在用户点击“连接(FTP)”时调用：仅暂存待保存信息。
     * 真正写入 SharedPreferences 的时机在 [refresh] 的 onSuccess（确保连接/目录加载成功）。
     */
    fun notePendingFtpConnection(entry: FtpHistoryEntry) {
        pendingFtpConnection = entry.copy(
            port = if (entry.port in 1..65535) entry.port else 21,
            remotePath = normalizeFtpRemotePath(entry.remotePath),
        )
    }

    private fun maybePersistFtpHistoryOnSuccess(currentDir: URI) {
        if (currentDir.scheme != "ftp") return
        val pending = pendingFtpConnection ?: return

        val host = currentDir.host.orEmpty()
        val port = if (currentDir.port > 0) currentDir.port else 21
        val remotePath = normalizeFtpRemotePath(currentDir.path.orEmpty())

        // 只在 host/port/path 与 pending 对齐时落盘（确保确实是“刚才那次连接”）。
        if (pending.host != host) return
        if (pending.port != port) return
        if (normalizeFtpRemotePath(pending.remotePath) != remotePath) return

        pendingFtpConnection = null

        val now = System.currentTimeMillis()
        val normalized = pending.copy(
            port = port,
            remotePath = remotePath,
            lastConnectedAt = now,
        )

        val next = upsertFtpHistory(_ftpHistory.value, normalized)
        _ftpHistory.value = next
        persistFtpHistoryToPrefs(next)
    }

    private fun loadFtpHistoryFromPrefs(): List<FtpHistoryEntry> {
        val raw = prefs.getString(PREF_KEY_FTP_HISTORY, null).orEmpty().trim()
        if (raw.isBlank()) return emptyList()

        return runCatching {
            val arr = JSONArray(raw)
            buildList {
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val host = obj.optString("host").orEmpty().trim()
                    if (host.isBlank()) continue

                    val port = obj.optInt("port", 21).coerceIn(1, 65535)
                    val name = obj.optString("name").orEmpty()
                    val username = obj.optString("username").orEmpty()
                    val password = obj.optString("password").orEmpty()
                    val remotePath = normalizeFtpRemotePath(obj.optString("remotePath").orEmpty())
                    val last = obj.optLong("lastConnectedAt", 0L)

                    add(
                        FtpHistoryEntry(
                            name = name,
                            host = host,
                            port = port,
                            username = username,
                            password = password,
                            remotePath = remotePath,
                            lastConnectedAt = last,
                        ),
                    )
                }
            }
                .distinctBy { it.dedupKey }
                .sortedByDescending { it.lastConnectedAt }
        }.getOrDefault(emptyList())
    }

    private fun persistFtpHistoryToPrefs(list: List<FtpHistoryEntry>) {
        val arr = JSONArray()
        list.forEach { h ->
            arr.put(
                JSONObject().apply {
                    put("name", h.name)
                    put("host", h.host)
                    put("port", h.port)
                    put("username", h.username)
                    put("password", h.password)
                    put("remotePath", normalizeFtpRemotePath(h.remotePath))
                    put("lastConnectedAt", h.lastConnectedAt)
                },
            )
        }
        prefs.edit().putString(PREF_KEY_FTP_HISTORY, arr.toString()).apply()
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
        exitMultiSelection()
        setDirStateLoading(dir = uri, canGoBack = backStack.isNotEmpty(), clipboard = clipboard)
        refresh()
    }

    fun goBack(): Boolean {
        val prev = backStack.removeLastOrNull() ?: return false
        val clipboard = (_uiState.value as? FileBrowserUiState.HasDir)?.clipboard
        exitMultiSelection()
        setDirStateLoading(dir = prev, canGoBack = backStack.isNotEmpty(), clipboard = clipboard)
        refresh()
        return true
    }

    fun enterMultiSelection(initialUri: URI) {
        _multiSelection.update { s ->
            if (s.enabled) {
                if (initialUri in s.selectedUris) s else s.copy(selectedUris = s.selectedUris + initialUri)
            } else {
                MultiSelectionState(enabled = true, selectedUris = setOf(initialUri))
            }
        }
    }

    fun toggleSelection(uri: URI) {
        _multiSelection.update { s ->
            val enabled = s.enabled
            val current = s.selectedUris
            val next = if (uri in current) current - uri else current + uri
            when {
                !enabled -> MultiSelectionState(enabled = true, selectedUris = setOf(uri))
                next.isEmpty() -> MultiSelectionState(enabled = false, selectedUris = emptySet())
                else -> s.copy(selectedUris = next)
            }
        }
    }

    fun exitMultiSelection() {
        _multiSelection.value = MultiSelectionState(enabled = false, selectedUris = emptySet())
    }

    fun selectAllVisible() {
        val s = _uiState.value as? FileBrowserUiState.HasDir ?: return
        val visibleUris: Set<URI> = when (s) {
            is FileBrowserUiState.Success -> s.entries.map { it.uri }.toSet()
            else -> emptySet()
        }
        _multiSelection.value = if (visibleUris.isEmpty()) {
            MultiSelectionState(enabled = false, selectedUris = emptySet())
        } else {
            MultiSelectionState(enabled = true, selectedUris = visibleUris)
        }
    }

    fun copySelectedToClipboard() {
        val selected = _multiSelection.value.selectedUris
        if (selected.isEmpty()) return
        copyToClipboard(*selected.toTypedArray())
        exitMultiSelection()
    }

    fun cutSelectedToClipboard() {
        val selected = _multiSelection.value.selectedUris
        if (selected.isEmpty()) return
        cutToClipboard(*selected.toTypedArray())
        exitMultiSelection()
    }

    fun deleteSelected() {
        val selected = _multiSelection.value.selectedUris
        if (selected.isEmpty()) return

        viewModelScope.launch {
            val s = _uiState.value as? FileBrowserUiState.HasDir ?: return@launch
            val dir = s.currentDir
            setDirStateLoading(dir = dir, canGoBack = s.canGoBack, clipboard = s.clipboard)
            runCatching {
                for (uri in selected) {
                    vfs.delete(uri)
                }
            }.onFailure { e ->
                setDirStateError(dir, errorMessageFor(e), canGoBack = s.canGoBack, clipboard = s.clipboard)
                return@launch
            }
            exitMultiSelection()
            refresh()
        }
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
        pasteJob?.cancel()
        pasteJob = viewModelScope.launch {
            setDirStateLoading(dir = destDirUri, canGoBack = s.canGoBack, clipboard = op)
            try {
                withContext(Dispatchers.IO) {
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
                            // 只在“搬运完成”后再清空剪贴板。
                            withContext(Dispatchers.Main.immediate) { clearClipboard() }
                        }
                    }
                }
                _copyProgress.value = null
                refresh()
            } catch (e: CancellationException) {
                _copyProgress.value = null
                setDirStateError(destDirUri, "操作已取消", canGoBack = s.canGoBack, clipboard = s.clipboard)
            } catch (e: Exception) {
                _copyProgress.value = null
                setDirStateError(destDirUri, e.message ?: e.toString(), canGoBack = s.canGoBack, clipboard = s.clipboard)
            }
        }
    }

    fun cancelPaste() {
        pasteJob?.cancel()
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
            runCatching {
                val name = displayName.trim()
                require(name.isNotBlank()) { "文件夹名称不能为空" }

                withContext(Dispatchers.IO) {
                    if (parent.scheme == "file") {
                        createLocalDirectoryUsingFile(parent, name)
                    } else {
                        vfs.createChildDirectory(parent, name)
                    }
                }
            }
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
                    val prefsSnapshot = _preferences.value
                    val visible = withContext(Dispatchers.Default) { applyPreferences(list, prefsSnapshot) }
                    _uiState.value = buildLoadedState(
                        dir = s.currentDir,
                        allEntries = list,
                        visibleEntries = visible,
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
            val locale = Locale.getDefault()
            // SimpleDateFormat 创建、Date 分配都较重：这里在 IO 线程单次缓存，避免列表滚动时重组阶段做格式化。
            val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm", locale)
            val scratchDate = Date()

            children.map { vf ->
                val isDir = vf.isDirectory()
                val name = vf.name
                val lastModified = runCatching { vf.lastModified() }.getOrDefault(0L)
                val size = if (isDir) 0L else runCatching { vf.length() }.getOrDefault(0L)

                // 极客级“暴力”断行：把文件名变成「字符 + \u200B + 字符 + \u200B ...」，让 Compose 可在任意字符间断行。
                val displayName = name.forceCharLineBreakForCompose()

                val time = if (lastModified <= 0L) {
                    ""
                } else {
                    runCatching {
                        scratchDate.time = lastModified
                        formatter.format(scratchDate)
                    }.getOrDefault("")
                }

                val subtitle = buildString {
                    append(if (isDir) "目录" else formatFileSize(size))
                    if (time.isNotBlank()) {
                        append(" · ")
                        append(time)
                    }
                }

                val nameLower = name.lowercase(Locale.ROOT)
                FileEntryUi(
                    uri = vf.uri,
                    name = name,
                    displayName = displayName,
                    subtitle = subtitle,
                    isDirectory = isDir,
                    size = size,
                    lastModified = lastModified,
                    nameLower = nameLower,
                    nameLowerNoDot = nameLower.removePrefix("."),
                )
            }
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
            _copyProgress.value = CopyProgressUi(
                currentName = targetDirName,
                bytesCopied = 0L,
                totalBytes = -1L,
                stageLabel = "创建目录",
            )
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

        val totalBytes = runCatching { src.length() }.getOrDefault(-1L)
        _copyProgress.value = CopyProgressUi(
            currentName = src.name,
            bytesCopied = 0L,
            totalBytes = totalBytes,
            stageLabel = "复制",
        )

        // 限流 UI 更新：避免每个 buffer 都触发 Compose 重组。
        var lastUiUpdateAt = 0L
        var lastUiBytes = 0L
        val minBytesStep = 256L * 1024L
        val minTimeStepMs = 80L

        vfs.copy(
            sourceFile = src,
            destFile = destFile,
            progressListener = { bytesCopied ->
                val now = SystemClock.elapsedRealtime()
                val shouldUpdate =
                    bytesCopied - lastUiBytes >= minBytesStep ||
                        now - lastUiUpdateAt >= minTimeStepMs ||
                        (totalBytes > 0 && bytesCopied >= totalBytes)
                if (shouldUpdate) {
                    lastUiBytes = bytesCopied
                    lastUiUpdateAt = now
                    _copyProgress.value = CopyProgressUi(
                        currentName = src.name,
                        bytesCopied = bytesCopied,
                        totalBytes = totalBytes,
                        stageLabel = "复制",
                    )
                }
            },
            cancellationSignal = pasteJob,
        )

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

    private fun pruneSelectionIfNeeded(currentDir: URI, allEntries: List<FileEntryUi>) {
        val ms = _multiSelection.value
        if (!ms.enabled || ms.selectedUris.isEmpty()) return

        // 只在“仍处于当前目录”时修剪，避免目录切换过程中误删选择。
        val s = _uiState.value as? FileBrowserUiState.HasDir ?: return
        if (s.currentDir != currentDir) return

        val existing = allEntries.asSequence().map { it.uri }.toHashSet()
        val pruned = ms.selectedUris.filterTo(LinkedHashSet()) { it in existing }
        _multiSelection.value = if (pruned.isEmpty()) {
            MultiSelectionState(enabled = false, selectedUris = emptySet())
        } else {
            ms.copy(selectedUris = pruned)
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
private const val PREF_KEY_FTP_HISTORY = "ftp_history_json"

private const val FTP_HISTORY_MAX = 20

private fun normalizeFtpRemotePath(raw: String): String {
    val p0 = raw.trim().ifBlank { "/" }
    val p1 = if (p0.startsWith('/')) p0 else "/$p0"
    return if (p1.endsWith('/')) p1 else "$p1/"
}

private fun upsertFtpHistory(existing: List<FtpHistoryEntry>, incoming: FtpHistoryEntry): List<FtpHistoryEntry> {
    val key = incoming.dedupKey
    val kept = existing.filterNot { it.dedupKey == key }
    return (listOf(incoming) + kept)
        .sortedByDescending { it.lastConnectedAt }
        .take(FTP_HISTORY_MAX)
}

/**
 * 在每个字符之间强制插入 \u200B（Zero Width Space）。
 * 这样 Compose 的排版引擎会把长单词视作可拆分的单字符序列，从而避免“整段英文/混排提前换行”。
 */
private fun String.forceCharLineBreakForCompose(): String {
    if (isEmpty()) return this
    return this.toList().joinToString("\u200B")
}

private fun createLocalDirectoryUsingFile(parentUri: URI, name: String) {
    val parentFile = File(parentUri)
    require(parentFile.isDirectory) { "目标不是本地目录：$parentUri" }
    val target = File(parentFile, name)
    if (target.exists()) {
        throw IllegalStateException("已存在同名文件/文件夹：$name")
    }
    if (!target.mkdir()) {
        throw IllegalStateException("创建文件夹失败：$name")
    }
}

private fun formatFileSize(bytes: Long): String {
    val b = bytes.coerceAtLeast(0L)
    if (b < 1024L) return "$b B"

    // 仅按 1024 进制转换到 KB/MB/GB（大于等于 1GB 也按 GB 展示）。
    val units = arrayOf("KB", "MB", "GB")
    var value = b.toDouble()
    var unitIndex = -1
    while (value >= 1024.0 && unitIndex < units.lastIndex) {
        value /= 1024.0
        unitIndex++
    }
    val unit = units[unitIndex]

    // 小于 10 时保留 1 位小数（例如 1.2 MB）；否则取整（例如 450 KB）。
    val number = if (value < 10.0) {
        String.format(Locale.ROOT, "%.1f", value).removeSuffix(".0")
    } else {
        String.format(Locale.ROOT, "%.0f", value)
    }
    return "$number $unit"
}
