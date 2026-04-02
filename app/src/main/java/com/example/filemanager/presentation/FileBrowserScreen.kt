package com.example.filemanager.presentation

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.TextSnippet
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.semantics.semantics
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

private val FolderBlue = Color(0xFF007AFF)
private val ImageOrange = Color(0xFFFF9500)
private val DocumentGreen = Color(0xFF34C759)
private val VideoIndigo = Color(0xFF5856D6)
private val AudioPurple = Color(0xFFAF52DE)
private val ArchiveYellow = Color(0xFFFFCC00)
private val ApkTeal = Color(0xFF32ADE6)

private val IosGroupedBackground = Color(0xFFF2F2F7)
private val IosGroupedBorder = Color(0xFFE5E5EA)

private enum class MainTab(val label: String, val icon: ImageVector) {
    Recent(label = "最近", icon = Icons.Filled.History),
    Browse(label = "浏览", icon = Icons.Filled.Folder),
    Network(label = "网络", icon = Icons.Filled.Public),
}

private enum class NetworkProtocol(val label: String) {
    SMB("SMB"),
    FTP("FTP"),
    NFS("NFS"),
}

private data class NetworkServerConfig(
    val protocol: NetworkProtocol,
    val name: String,
    val host: String,
    val port: Int?,
    val username: String,
    val password: String,
    // SMB 可选域：domain;user:password
    val domain: String,
    // FTP: 初始目录（例如 / 或 /pub/）
    // SMB: share + 子路径（例如 public 或 public/Movies/）
    val remotePath: String,
)

private fun NetworkServerConfig.toRootUriOrNull(): URI? {
    return when (protocol) {
        NetworkProtocol.NFS -> null
        NetworkProtocol.FTP -> buildFtpRootUri(host, port, username, password, remotePath)
        NetworkProtocol.SMB -> buildSmbRootUri(host, port, domain, username, password, remotePath)
    }
}

private fun parsePortOrNullOrThrow(raw: String, protocolLabel: String): Int? {
    val t = raw.trim()
    if (t.isBlank()) return null
    val p = t.toIntOrNull() ?: throw IllegalArgumentException("端口格式不正确：$t")
    require(p in 1..65535) { "$protocolLabel 端口范围应为 1~65535：$p" }
    return p
}

private fun requireValidRootUri(uri: URI, protocol: NetworkProtocol) {
    when (protocol) {
        NetworkProtocol.FTP -> {
            require(uri.scheme == "ftp") { "FTP URI scheme 不正确：${uri.scheme}" }
            require(!uri.host.isNullOrBlank()) { "FTP URI 缺少 host" }
            // 期望根目录至少为 "/"，并建议以 "/" 结尾。
            val p = uri.path.orEmpty().ifBlank { "/" }
            require(p.startsWith('/')) { "FTP URI path 不正确：$p" }
        }
        NetworkProtocol.SMB -> {
            require(uri.scheme == "smb") { "SMB URI scheme 不正确：${uri.scheme}" }
            require(!uri.host.isNullOrBlank()) { "SMB URI 缺少 host" }
            require(uri.path.orEmpty().trim('/').isNotBlank()) { "SMB URI 缺少 share/path" }
        }
        NetworkProtocol.NFS -> Unit
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileBrowserScreen(
    viewModel: FileBrowserViewModel,
    onPickSafDirectory: () -> Unit,
    onGoLocalRoot: () -> Unit,
    hasAllFilesAccess: Boolean,
    onRequestAllFilesAccess: () -> Unit,
) {
    val state by viewModel.uiState.collectAsState()
    val prefs by viewModel.preferences.collectAsState()

    var settingsVisible by remember { mutableStateOf(false) }

    if (settingsVisible) {
        SettingsScreen(
            preferences = prefs,
            onBack = { settingsVisible = false },
            onShowHiddenChanged = { viewModel.setShowHiddenFiles(it) },
        )
        return
    }

    FileBrowserContent(
        state = state,
        preferences = prefs,
        onGoBack = { viewModel.goBack() },
        onRefresh = { viewModel.refresh() },
        onPickSafDirectory = onPickSafDirectory,
        onGoLocalRoot = onGoLocalRoot,
        hasAllFilesAccess = hasAllFilesAccess,
        onRequestAllFilesAccess = onRequestAllFilesAccess,
        onToggleSearch = { viewModel.toggleSearch() },
        onSearchQueryChange = { viewModel.setSearchQuery(it) },
        onClearSearch = { viewModel.clearSearch() },
        onSetSortOption = { viewModel.setSortOption(it) },
        onOpenSettings = { settingsVisible = true },
        onPasteIntoCurrentDir = { viewModel.pasteIntoCurrentDir() },
        onClearClipboard = { viewModel.clearClipboard() },
        onOpenDirectory = { viewModel.openDirectory(it) },
        onOpenAsText = { viewModel.openAsText(it) },
        onCreateFolder = { viewModel.createFolder(it) },
        onDelete = { viewModel.delete(it) },
        onRename = { uri, name -> viewModel.rename(uri, name) },
        onCopy = { viewModel.copyToClipboard(it) },
        onCut = { viewModel.cutToClipboard(it) },
        onDismissTextViewer = { viewModel.dismissTextViewer() },
        onSetRoot = { viewModel.setRoot(it) },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileBrowserContent(
    state: FileBrowserUiState,
    preferences: FileBrowserPreferences,
    onGoBack: () -> Unit,
    onRefresh: () -> Unit,
    onPickSafDirectory: () -> Unit,
    onGoLocalRoot: () -> Unit,
    hasAllFilesAccess: Boolean,
    onRequestAllFilesAccess: () -> Unit,
    onToggleSearch: () -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onClearSearch: () -> Unit,
    onSetSortOption: (SortOption) -> Unit,
    onOpenSettings: () -> Unit,
    onPasteIntoCurrentDir: () -> Unit,
    onClearClipboard: () -> Unit,
    onOpenDirectory: (URI) -> Unit,
    onOpenAsText: (URI) -> Unit,
    onCreateFolder: (String) -> Unit,
    onDelete: (URI) -> Unit,
    onRename: (URI, String) -> Unit,
    onCopy: (URI) -> Unit,
    onCut: (URI) -> Unit,
    onDismissTextViewer: () -> Unit,
    onSetRoot: (URI) -> Unit,
) {
    val currentDir: URI? = (state as? FileBrowserUiState.HasDir)?.currentDir
    val canGoBack: Boolean = (state as? FileBrowserUiState.HasDir)?.canGoBack == true
    val clipboard: ClipboardOp? = (state as? FileBrowserUiState.HasDir)?.clipboard
    val textViewer: TextViewerState? = (state as? FileBrowserUiState.HasDir)?.textViewer

    val entries: List<FileEntryUi> = when (state) {
        FileBrowserUiState.NoSelection -> emptyList()
        is FileBrowserUiState.Loading -> emptyList()
        is FileBrowserUiState.Success -> state.entries
        is FileBrowserUiState.Empty -> emptyList()
        is FileBrowserUiState.Error -> emptyList()
    }

    var createFolderDialog by remember { mutableStateOf(false) }
    var createFolderName by remember { mutableStateOf("") }

    var pendingDelete by remember { mutableStateOf<URI?>(null) }
    var pendingRename by remember { mutableStateOf<URI?>(null) }
    var renameText by remember { mutableStateOf("") }

    var selectedTab by remember { mutableStateOf(MainTab.Browse) }

    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    fun showSnack(message: String) {
        coroutineScope.launch { snackbarHostState.showSnackbar(message) }
    }

    var moreMenuExpanded by remember { mutableStateOf(false) }
    var sortMenuExpanded by remember { mutableStateOf(false) }

    // Network tab: 服务器配置（当前为内存态，后续可接 DataStore/Room 持久化）。
    val savedServers = remember { mutableStateListOf<NetworkServerConfig>() }
    var serverDialogVisible by remember { mutableStateOf(false) }
    var serverProtocol by remember { mutableStateOf(NetworkProtocol.SMB) }
    var serverName by remember { mutableStateOf("") }
    var serverHost by remember { mutableStateOf("") }
    var serverPort by remember { mutableStateOf("") }
    var serverDomain by remember { mutableStateOf("") }
    var serverUser by remember { mutableStateOf("") }
    var serverPass by remember { mutableStateOf("") }
    var serverRemotePath by remember { mutableStateOf("") }

    fun openServerDialog(protocol: NetworkProtocol) {
        serverProtocol = protocol
        serverName = ""
        serverHost = ""
        serverPort = when (protocol) {
            NetworkProtocol.SMB -> "445"
            NetworkProtocol.FTP -> "21"
            NetworkProtocol.NFS -> "2049"
        }
        serverDomain = ""
        serverUser = ""
        serverPass = ""
        serverRemotePath = when (protocol) {
            NetworkProtocol.SMB -> "public/"
            NetworkProtocol.FTP -> "/"
            NetworkProtocol.NFS -> "/"
        }
        serverDialogVisible = true
    }

    val isNetworkDir: Boolean = currentDir?.scheme == "smb" || currentDir?.scheme == "ftp" || currentDir?.scheme == "nfs"

    val supportsFilter: Boolean =
        (selectedTab == MainTab.Browse && currentDir != null) || (selectedTab == MainTab.Network && isNetworkDir && currentDir != null)

    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    val topBarTitle = when (selectedTab) {
        MainTab.Browse -> currentDir?.let { friendlyTitleForBrowseDir(it) } ?: selectedTab.label
        else -> selectedTab.label
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Box(
                        modifier = Modifier.fillMaxHeight(),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        if (supportsFilter && preferences.isSearchExpanded) {
                            androidx.compose.material3.OutlinedTextField(
                                value = preferences.searchQuery,
                                onValueChange = onSearchQueryChange,
                                placeholder = { Text("搜索当前列表") },
                                singleLine = true,
                                trailingIcon = {
                                    if (preferences.searchQuery.isNotBlank()) {
                                        IconButton(onClick = onClearSearch) {
                                            Icon(Icons.Filled.Close, contentDescription = "清空搜索")
                                        }
                                    }
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("search_input"),
                            )
                        } else {
                            Text(
                                text = topBarTitle,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                            )
                        }
                    }
                },
                navigationIcon = {
                    if ((selectedTab == MainTab.Browse || (selectedTab == MainTab.Network && isNetworkDir)) && canGoBack) {
                        IconButton(onClick = onGoBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                        }
                    }
                },
                actions = {
                    Row(
                        modifier = Modifier.fillMaxHeight(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        if (supportsFilter) {
                            IconButton(onClick = onToggleSearch) {
                                val icon = if (preferences.isSearchExpanded) Icons.Filled.Close else Icons.Filled.Search
                                val cd = if (preferences.isSearchExpanded) "关闭搜索" else "搜索"
                                Icon(icon, contentDescription = cd)
                            }

                            Box {
                                IconButton(onClick = { sortMenuExpanded = true }) {
                                    Icon(Icons.Filled.FilterList, contentDescription = "排序")
                                }
                                DropdownMenu(
                                    expanded = sortMenuExpanded,
                                    onDismissRequest = { sortMenuExpanded = false },
                                ) {
                                    SortOption.entries.forEach { option ->
                                        val suffix = if (preferences.sortOption == option) " ✓" else ""
                                        DropdownMenuItem(
                                            text = { Text(option.label + suffix) },
                                            onClick = {
                                                sortMenuExpanded = false
                                                onSetSortOption(option)
                                            },
                                        )
                                    }
                                }
                            }
                        }

                        Box {
                            IconButton(onClick = { moreMenuExpanded = true }) {
                                Icon(Icons.Filled.MoreVert, contentDescription = "更多")
                            }
                            DropdownMenu(
                                expanded = moreMenuExpanded,
                                onDismissRequest = { moreMenuExpanded = false },
                            ) {
                                if (selectedTab == MainTab.Browse) {
                                    if (!hasAllFilesAccess) {
                                        DropdownMenuItem(
                                            text = { Text("开启全盘访问") },
                                            leadingIcon = { Icon(Icons.Filled.Security, contentDescription = null) },
                                            onClick = {
                                                moreMenuExpanded = false
                                                onRequestAllFilesAccess()
                                            },
                                        )
                                    }
                                    if (clipboard != null) {
                                        DropdownMenuItem(
                                            text = { Text("粘贴") },
                                            leadingIcon = { Icon(Icons.Filled.Save, contentDescription = null) },
                                            onClick = {
                                                moreMenuExpanded = false
                                                onPasteIntoCurrentDir()
                                            },
                                        )
                                        DropdownMenuItem(
                                            text = { Text("清空剪贴板") },
                                            leadingIcon = { Icon(Icons.Filled.Close, contentDescription = null) },
                                            onClick = {
                                                moreMenuExpanded = false
                                                onClearClipboard()
                                            },
                                        )
                                    }
                                    DropdownMenuItem(
                                        text = { Text("刷新") },
                                        leadingIcon = { Icon(Icons.Filled.Refresh, contentDescription = null) },
                                        onClick = {
                                            moreMenuExpanded = false
                                            onRefresh()
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text("回到本地") },
                                        leadingIcon = { Icon(Icons.Filled.Folder, contentDescription = null) },
                                        onClick = {
                                            moreMenuExpanded = false
                                            onGoLocalRoot()
                                        },
                                    )
                                } else if (selectedTab == MainTab.Network) {
                                    DropdownMenuItem(
                                        text = { Text("添加服务器") },
                                        leadingIcon = { Icon(Icons.Filled.Add, contentDescription = null) },
                                        onClick = {
                                            moreMenuExpanded = false
                                            openServerDialog(NetworkProtocol.SMB)
                                        },
                                    )
                                    if (isNetworkDir) {
                                        DropdownMenuItem(
                                            text = { Text("返回网络主页") },
                                            onClick = {
                                                moreMenuExpanded = false
                                                // 当前阶段：Network Tab 主页只影响展示逻辑；目录仍保留在状态里。
                                                showSnack("网络主页：占位")
                                            },
                                        )
                                    }
                                } else {
                                    DropdownMenuItem(
                                        text = { Text("暂无更多操作") },
                                        onClick = {
                                            moreMenuExpanded = false
                                            showSnack("更多：占位")
                                        },
                                    )
                                }

                                DropdownMenuItem(
                                    text = { Text("设置") },
                                    leadingIcon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                                    onClick = {
                                        moreMenuExpanded = false
                                        onOpenSettings()
                                    },
                                )
                            }
                        }
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        floatingActionButton = {
            when (selectedTab) {
                MainTab.Browse -> {
                    if (currentDir != null) {
                        FloatingActionButton(
                            onClick = {
                                createFolderName = ""
                                createFolderDialog = true
                            },
                        ) {
                            Icon(Icons.Filled.CreateNewFolder, contentDescription = "新建文件夹")
                        }
                    }
                }
                MainTab.Network -> {
                    FloatingActionButton(
                        onClick = { openServerDialog(NetworkProtocol.SMB) },
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = "添加服务器")
                    }
                }
                else -> Unit
            }
        },
        bottomBar = {
            NavigationBar {
                MainTab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = selectedTab == tab,
                        onClick = { selectedTab = tab },
                        icon = { Icon(tab.icon, contentDescription = null) },
                        label = { Text(tab.label) },
                    )
                }
            }
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(padding),
        ) {
            when (selectedTab) {
                MainTab.Browse -> {
                    if (currentDir == null) {
                        NoSelectionContent(
                            onPickSafDirectory = onPickSafDirectory,
                            onGoLocalRoot = onGoLocalRoot,
                        )
                    } else {
                        when (state) {
                            is FileBrowserUiState.Success -> {
                                LazyColumn(
                                    modifier = Modifier.fillMaxSize(),
                                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    item {
                                        Text(
                                            text = friendlyTitleForBrowseDir(currentDir),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }

                                    items(
                                        items = entries,
                                        key = { it.uri.toString() },
                                    ) { item ->
                                        FlatFileRow(
                                            item = item,
                                            onClick = {
                                                if (item.isDirectory) onOpenDirectory(item.uri)
                                                else onOpenAsText(item.uri)
                                            },
                                            onDelete = { pendingDelete = item.uri },
                                            onRename = {
                                                pendingRename = item.uri
                                                renameText = item.name
                                            },
                                            onCopy = { onCopy(item.uri) },
                                            onCut = { onCut(item.uri) },
                                        )
                                    }
                                    item { Spacer(modifier = Modifier.height(80.dp)) }
                                }
                            }

                            is FileBrowserUiState.Empty -> {
                                EmptyContent(preferences.searchQuery)
                            }

                            is FileBrowserUiState.Error -> {
                                ErrorContent(
                                    message = state.message,
                                    onRetry = onRefresh,
                                )
                            }

                            is FileBrowserUiState.Loading -> {
                                FileListSkeleton()
                            }

                            FileBrowserUiState.NoSelection -> Unit
                        }
                    }
                }

                MainTab.Recent -> {
                    SimplePlaceholderScreen(title = "最近")
                }

                MainTab.Network -> {
                    if (isNetworkDir && currentDir != null) {
                        when (state) {
                            is FileBrowserUiState.Success -> {
                                LazyColumn(
                                    modifier = Modifier.fillMaxSize(),
                                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    item {
                                        Text(
                                            text = currentDir.toString(),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    items(
                                        items = entries,
                                        key = { it.uri.toString() },
                                    ) { item ->
                                        FlatFileRow(
                                            item = item,
                                            onClick = {
                                                if (item.isDirectory) onOpenDirectory(item.uri)
                                                else onOpenAsText(item.uri)
                                            },
                                            onDelete = { pendingDelete = item.uri },
                                            onRename = {
                                                pendingRename = item.uri
                                                renameText = item.name
                                            },
                                            onCopy = { onCopy(item.uri) },
                                            onCut = { onCut(item.uri) },
                                        )
                                    }
                                    item { Spacer(modifier = Modifier.height(80.dp)) }
                                }
                            }

                            is FileBrowserUiState.Empty -> EmptyContent(preferences.searchQuery)

                            is FileBrowserUiState.Error -> {
                                ErrorContent(
                                    message = state.message,
                                    onRetry = onRefresh,
                                )
                            }

                            is FileBrowserUiState.Loading -> FileListSkeleton()
                            FileBrowserUiState.NoSelection -> Unit
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            item {
                                Text(
                                    text = "连接到网络服务器",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onBackground,
                                )
                            }

                            item {
                                ElevatedCard(
                                    onClick = { openServerDialog(NetworkProtocol.SMB) },
                                    shape = RoundedCornerShape(14.dp),
                                    colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface),
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(14.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Column {
                                            Text("SMB（局域网共享）", style = MaterialTheme.typography.bodyLarge)
                                            Text("添加/连接 SMB 服务器", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                        Text("+", style = MaterialTheme.typography.titleLarge)
                                    }
                                }
                            }

                            item {
                                ElevatedCard(
                                    onClick = { openServerDialog(NetworkProtocol.FTP) },
                                    shape = RoundedCornerShape(14.dp),
                                    colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface),
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(14.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Column {
                                            Text("FTP", style = MaterialTheme.typography.bodyLarge)
                                            Text("添加/连接 FTP 服务器", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                        Text("+", style = MaterialTheme.typography.titleLarge)
                                    }
                                }
                            }

                            item {
                                ElevatedCard(
                                    onClick = { openServerDialog(NetworkProtocol.NFS) },
                                    shape = RoundedCornerShape(14.dp),
                                    colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface),
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(14.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Column {
                                            Text("NFS", style = MaterialTheme.typography.bodyLarge)
                                            Text("入口已就绪（协议实现待接入）", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                        Text("+", style = MaterialTheme.typography.titleLarge)
                                    }
                                }
                            }

                            if (savedServers.isNotEmpty()) {
                                item {
                                    Text(
                                        text = "已保存",
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                items(
                                    items = savedServers,
                                    key = { "${it.protocol}-${it.host}-${it.port}-${it.remotePath}-${it.username}" },
                                ) { cfg ->
                                    ElevatedCard(
                                        onClick = {
                                            val uri = cfg.toRootUriOrNull()
                                            if (uri == null) {
                                                showSnack("NFS 暂未接入：仅保留配置框架")
                                                return@ElevatedCard
                                            }
                                            onSetRoot(uri)
                                            showSnack("已连接：${cfg.protocol.label} ${cfg.host}")
                                        },
                                        shape = RoundedCornerShape(14.dp),
                                        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface),
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(14.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = cfg.name.ifBlank { cfg.host },
                                                    style = MaterialTheme.typography.bodyLarge,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                                Text(
                                                    text = "${cfg.protocol.label} · ${cfg.host}:${cfg.port ?: "-"} · ${cfg.remotePath}",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                            }
                                            Text("连接", color = MaterialTheme.colorScheme.primary)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (serverDialogVisible) {
        val protocolTagPrefix = when (serverProtocol) {
            NetworkProtocol.SMB -> "smb"
            NetworkProtocol.FTP -> "ftp"
            NetworkProtocol.NFS -> "nfs"
        }

        AlertDialog(
            onDismissRequest = { serverDialogVisible = false },
            title = { Text("添加服务器（${serverProtocol.label}）") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(
                            onClick = { openServerDialog(NetworkProtocol.SMB) },
                            modifier = Modifier.testTag("smb_protocol_button"),
                        ) { Text("SMB") }
                        TextButton(
                            onClick = { openServerDialog(NetworkProtocol.FTP) },
                            modifier = Modifier.testTag("ftp_protocol_button"),
                        ) { Text("FTP") }
                        TextButton(
                            onClick = { openServerDialog(NetworkProtocol.NFS) },
                            modifier = Modifier.testTag("nfs_protocol_button"),
                        ) { Text("NFS") }
                    }

                    androidx.compose.material3.OutlinedTextField(
                        value = serverName,
                        onValueChange = { serverName = it },
                        modifier = Modifier.testTag("${protocolTagPrefix}_name_input"),
                        label = { Text("名称（可选）") },
                        singleLine = true,
                    )
                    androidx.compose.material3.OutlinedTextField(
                        value = serverHost,
                        onValueChange = { serverHost = it },
                        modifier = Modifier.testTag("${protocolTagPrefix}_host_input"),
                        label = { Text("主机") },
                        singleLine = true,
                    )
                    androidx.compose.material3.OutlinedTextField(
                        value = serverPort,
                        onValueChange = { serverPort = it.filter { ch -> ch.isDigit() } },
                        modifier = Modifier.testTag("${protocolTagPrefix}_port_input"),
                        label = { Text("端口") },
                        singleLine = true,
                    )
                    if (serverProtocol == NetworkProtocol.SMB) {
                        androidx.compose.material3.OutlinedTextField(
                            value = serverDomain,
                            onValueChange = { serverDomain = it },
                            modifier = Modifier.testTag("smb_domain_input"),
                            label = { Text("域（可选）") },
                            singleLine = true,
                        )
                    }
                    androidx.compose.material3.OutlinedTextField(
                        value = serverUser,
                        onValueChange = { serverUser = it },
                        modifier = Modifier.testTag("${protocolTagPrefix}_username_input"),
                        label = { Text("用户名（可选）") },
                        singleLine = true,
                    )
                    androidx.compose.material3.OutlinedTextField(
                        value = serverPass,
                        onValueChange = { serverPass = it },
                        modifier = Modifier.testTag("${protocolTagPrefix}_password_input"),
                        label = { Text("密码（可选）") },
                        singleLine = true,
                    )

                    val pathLabel = when (serverProtocol) {
                        NetworkProtocol.SMB -> "共享路径（例如 public/Movies/）"
                        NetworkProtocol.FTP -> "初始目录（例如 / 或 /pub/）"
                        NetworkProtocol.NFS -> "导出路径（例如 /export/）"
                    }
                    androidx.compose.material3.OutlinedTextField(
                        value = serverRemotePath,
                        onValueChange = { serverRemotePath = it },
                        modifier = Modifier.testTag("${protocolTagPrefix}_path_input"),
                        label = { Text(pathLabel) },
                        singleLine = true,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        runCatching {
                            val host = serverHost.trim()
                            require(host.isNotBlank()) { "请输入主机" }

                            val remotePath = serverRemotePath.trim().ifBlank {
                                when (serverProtocol) {
                                    NetworkProtocol.SMB -> ""
                                    NetworkProtocol.FTP -> "/"
                                    NetworkProtocol.NFS -> "/"
                                }
                            }
                            if (serverProtocol == NetworkProtocol.SMB && remotePath.trimStart('/').isBlank()) {
                                throw IllegalArgumentException("请输入共享路径（share/）")
                            }

                            val port = parsePortOrNullOrThrow(serverPort, serverProtocol.label)
                            val cfg = NetworkServerConfig(
                                protocol = serverProtocol,
                                name = serverName.trim(),
                                host = host,
                                port = port,
                                username = serverUser.trim(),
                                password = serverPass,
                                domain = serverDomain.trim(),
                                remotePath = remotePath,
                            )

                            if (!savedServers.contains(cfg)) savedServers.add(cfg)

                            val uri = cfg.toRootUriOrNull()
                            if (uri == null) {
                                showSnack("NFS 暂未接入：仅保留配置框架")
                                return@runCatching
                            }
                            requireValidRootUri(uri, serverProtocol)

                            // 先关闭弹窗，避免网络请求/加载耗时导致 UI 看起来“卡住”。
                            serverDialogVisible = false
                            showSnack("正在连接：${cfg.protocol.label} ${cfg.host}")
                            onSetRoot(uri)
                        }.onFailure { e ->
                            // 避免异常被静默吞掉：保留弹窗，给出可见提示。
                            showSnack(e.message ?: e.toString())
                        }
                    },
                    modifier = Modifier.testTag("${protocolTagPrefix}_connect_button"),
                ) { Text("连接") }
            },
            dismissButton = {
                TextButton(
                    onClick = { serverDialogVisible = false },
                    modifier = Modifier.testTag("${protocolTagPrefix}_cancel_button"),
                ) { Text("取消") }
            },
        )
    }

    if (createFolderDialog) {
        AlertDialog(
            onDismissRequest = { createFolderDialog = false },
            title = { Text("新建文件夹") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    androidx.compose.material3.OutlinedTextField(
                        value = createFolderName,
                        onValueChange = { createFolderName = it },
                        label = { Text("名称") },
                        singleLine = true,
                    )
                    Text(text = "将创建在当前目录下")
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        createFolderDialog = false
                        if (createFolderName.isNotBlank()) {
                            onCreateFolder(createFolderName.trim())
                        }
                    },
                ) { Text("创建") }
            },
            dismissButton = {
                TextButton(onClick = { createFolderDialog = false }) { Text("取消") }
            },
        )
    }

    pendingDelete?.let { uri ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除") },
            text = { Text("确定删除该项吗？\n$uri") },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDelete = null
                        onDelete(uri)
                    },
                ) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            },
        )
    }

    pendingRename?.let { uri ->
        AlertDialog(
            onDismissRequest = { pendingRename = null },
            title = { Text("重命名") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    androidx.compose.material3.OutlinedTextField(
                        value = renameText,
                        onValueChange = { renameText = it },
                        label = { Text("新名称") },
                        singleLine = true,
                    )
                    Text(text = uri.toString(), maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingRename = null
                        val name = renameText.trim()
                        if (name.isNotBlank()) onRename(uri, name)
                    },
                ) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { pendingRename = null }) { Text("取消") }
            },
        )
    }

    textViewer?.let { tv ->
        AlertDialog(
            onDismissRequest = onDismissTextViewer,
            title = { Text(tv.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            text = {
                LazyColumn(modifier = Modifier.fillMaxWidth().height(380.dp)) {
                    item {
                        Text(text = tv.content)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = onDismissTextViewer) { Text("关闭") }
            },
        )
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun FlatFileRow(
    item: FileEntryUi,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onCopy: () -> Unit,
    onCut: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }

    val iconStyle = fileIconStyleFor(item)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = { menuExpanded = true },
            )
            // 让「点击」语义与子节点文本合并，便于 Robolectric/Compose UI Test 通过 text + clickAction 精准定位。
            .semantics(mergeDescendants = true) {}
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(
            imageVector = iconStyle.icon,
            contentDescription = null,
            tint = iconStyle.tint,
            modifier = Modifier.size(48.dp),
        )

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.name,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onBackground,
                style = MaterialTheme.typography.bodyLarge,
            )
            val subtitle = buildString {
                append(if (item.isDirectory) "目录" else "${item.size} B")
                val time = formatLastModified(item.lastModified)
                if (time.isNotBlank()) {
                    append(" · ")
                    append(time)
                }
            }
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // 右侧不再显示“向右箭头”指示器；保留一个极小的锚点用于 DropdownMenu 定位。
        Box(modifier = Modifier.size(1.dp)) {
            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                DropdownMenuItem(
                    text = { Text("复制") },
                    leadingIcon = { Icon(Icons.Filled.ContentCopy, contentDescription = null) },
                    onClick = {
                        menuExpanded = false
                        onCopy()
                    },
                )
                DropdownMenuItem(
                    text = { Text("剪切") },
                    leadingIcon = { Icon(Icons.Filled.ContentCut, contentDescription = null) },
                    onClick = {
                        menuExpanded = false
                        onCut()
                    },
                )
                DropdownMenuItem(
                    text = { Text("重命名") },
                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.TextSnippet, contentDescription = null) },
                    onClick = {
                        menuExpanded = false
                        onRename()
                    },
                )
                DropdownMenuItem(
                    text = { Text("删除") },
                    leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                    onClick = {
                        menuExpanded = false
                        onDelete()
                    },
                )
            }
        }
    }
}

@Composable
private fun SimplePlaceholderScreen(title: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "$title（待实现）",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private data class FileIconStyle(
    val icon: ImageVector,
    val tint: Color,
)

@Composable
private fun fileIconStyleFor(item: FileEntryUi): FileIconStyle {
    if (item.isDirectory) {
        return FileIconStyle(icon = Icons.Filled.Folder, tint = FolderBlue)
    }

    val ext = item.name.substringAfterLast('.', missingDelimiterValue = "").lowercase()
    return when {
        ext in imageExtensions -> FileIconStyle(icon = Icons.Filled.Image, tint = ImageOrange)
        ext in videoExtensions -> FileIconStyle(icon = Icons.Filled.Movie, tint = VideoIndigo)
        ext in audioExtensions -> FileIconStyle(icon = Icons.Filled.MusicNote, tint = AudioPurple)
        ext in archiveExtensions -> FileIconStyle(icon = Icons.Filled.Archive, tint = ArchiveYellow)
        ext == "pdf" -> FileIconStyle(icon = Icons.Filled.PictureAsPdf, tint = DocumentGreen)
        ext == "apk" -> FileIconStyle(icon = Icons.Filled.Android, tint = ApkTeal)
        ext in textExtensions -> FileIconStyle(icon = Icons.AutoMirrored.Filled.TextSnippet, tint = DocumentGreen)
        else -> FileIconStyle(icon = Icons.Filled.Description, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private val imageExtensions = setOf(
    "png",
    "jpg",
    "jpeg",
    "webp",
    "gif",
    "bmp",
    "heic",
    "heif",
)

private val videoExtensions = setOf(
    "mp4",
    "mov",
    "mkv",
    "avi",
    "webm",
    "m4v",
)

private val audioExtensions = setOf(
    "mp3",
    "wav",
    "aac",
    "m4a",
    "flac",
    "ogg",
)

private val archiveExtensions = setOf(
    "zip",
    "rar",
    "7z",
    "tar",
    "gz",
    "bz2",
)

private val textExtensions = setOf(
    "txt",
    "md",
    "json",
    "xml",
    "csv",
    "log",
    "kt",
    "kts",
    "java",
    "py",
    "js",
    "ts",
    "html",
    "css",
    "yml",
    "yaml",
)

private fun prettyTitleForDir(uri: URI): String {
    val path = uri.path
    if (!path.isNullOrBlank()) {
        val trimmed = path.trimEnd('/')
        val last = trimmed.substringAfterLast('/', missingDelimiterValue = trimmed)
        if (last.isNotBlank()) return last
    }
    return uri.host ?: uri.toString()
}

private fun friendlyTitleForBrowseDir(uri: URI): String {
    return if (isLocalStorageRoot(uri)) "本地" else prettyTitleForDir(uri)
}

private fun isLocalStorageRoot(uri: URI): Boolean {
    if (uri.scheme != "file") return false
    val p = uri.path?.trimEnd('/') ?: return false
    // Environment.getExternalStorageDirectory() 在多数设备上为 /storage/emulated/0
    return p == "/storage/emulated/0"
}

private fun buildFtpRootUri(
    host: String,
    port: Int?,
    username: String,
    password: String,
    remotePath: String,
): URI {
    val userInfo = if (username.isBlank()) null else "${username}:${password}"
    val p0 = remotePath.trim().ifBlank { "/" }
    val p1 = if (p0.startsWith('/')) p0 else "/$p0"
    val path = if (p1.endsWith('/')) p1 else "$p1/"
    return URI("ftp", userInfo, host, port ?: -1, path, null, null)
}

private fun buildSmbRootUri(
    host: String,
    port: Int?,
    domain: String,
    username: String,
    password: String,
    remotePath: String,
): URI {
    val user = username.trim()
    val dom = domain.trim()
    val userInfo = if (user.isBlank()) {
        null
    } else {
        val prefix = if (dom.isBlank()) user else "$dom;$user"
        "$prefix:$password"
    }

    // jcifs-ng 期望目录以 '/' 结尾；SMB path 通常为 /share/path/
    val raw = remotePath.trim().trimStart('/').ifBlank { "" }
    val p1 = "/$raw"
    val path = if (p1.endsWith('/')) p1 else "$p1/"
    return URI("smb", userInfo, host, port ?: -1, path, null, null)
}

private fun formatLastModified(epochMillis: Long): String {
    if (epochMillis <= 0L) return ""
    return runCatching {
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        fmt.format(Date(epochMillis))
    }.getOrDefault("")
}

@Composable
private fun NoSelectionContent(
    onPickSafDirectory: () -> Unit,
    onGoLocalRoot: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "请先选择一个目录（建议使用 SAF 选择 SD 卡/OTG/下载目录）。",
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = onPickSafDirectory) { Text("选择目录") }
        TextButton(onClick = onGoLocalRoot) { Text("尝试打开本地根目录") }
    }
}

@Composable
private fun EmptyContent(searchQuery: String) {
    val q = searchQuery.trim()
    val message = if (q.isBlank()) "空文件夹" else "没有匹配 \"$q\" 的结果"
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = message,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.titleMedium,
        )
    }
}

@Composable
private fun ErrorContent(
    message: String,
    onRetry: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        contentAlignment = Alignment.Center,
    ) {
        ElevatedCard(
            shape = RoundedCornerShape(12.dp),
            elevation = CardDefaults.elevatedCardElevation(defaultElevation = 0.dp),
            colors = CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.75f),
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            ),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    imageVector = Icons.Filled.ErrorOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
                Text(text = "加载失败", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
                TextButton(onClick = onRetry) { Text("重试") }
            }
        }
    }
}

@Composable
private fun FileListSkeleton() {
    val transition = rememberInfiniteTransition(label = "skeleton")
    val alpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.9f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "alpha",
    )

    val blockColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = alpha)

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(8) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                SkeletonBlock(
                    width = 48.dp,
                    height = 48.dp,
                    color = blockColor,
                    shape = RoundedCornerShape(6.dp),
                )
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SkeletonBlock(
                        width = 180.dp,
                        height = 14.dp,
                        color = blockColor,
                        shape = RoundedCornerShape(6.dp),
                    )
                    SkeletonBlock(
                        width = 110.dp,
                        height = 12.dp,
                        color = blockColor,
                        shape = RoundedCornerShape(6.dp),
                    )
                }
                SkeletonBlock(
                    width = 18.dp,
                    height = 18.dp,
                    color = blockColor,
                    shape = RoundedCornerShape(6.dp),
                )
            }
        }
        item { Spacer(modifier = Modifier.height(80.dp)) }
    }
}

@Composable
private fun SkeletonBlock(
    width: Dp,
    height: Dp,
    color: Color,
    shape: Shape,
) {
    // NOTE: Paparazzi 对部分 shimmer/brush 实现较敏感，这里用「alpha 动画 + 纯色块」模拟 Skeleton。
    Box(
        modifier = Modifier
            .size(width = width, height = height)
            .background(color = color, shape = shape),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScreen(
    preferences: FileBrowserPreferences,
    onBack: () -> Unit,
    onShowHiddenChanged: (Boolean) -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                ListItem(
                    headlineContent = { Text("显示隐藏文件") },
                    supportingContent = { Text("隐藏文件通常以 '.' 开头") },
                    trailingContent = {
                        Switch(
                            checked = preferences.showHiddenFiles,
                            onCheckedChange = onShowHiddenChanged,
                        )
                    },
                )
            }
        }
    }
}
