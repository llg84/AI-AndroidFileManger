package com.example.filemanager.presentation

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import java.net.URI
import java.util.Locale
import org.junit.Rule
import org.junit.Test

class FileBrowserPaparazziTest {
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_5,
    )

    @Test
    fun fileBrowser_loading_snapshot() {
        snapshot(
            FileBrowserUiState.Loading(
                currentDir = URI("file:///storage/emulated/0/Download"),
            ),
        )
    }

    @Test
    fun fileBrowser_success_snapshot() {
        val entries = listOf(
            fakeEntry(
                uri = URI("file:///storage/emulated/0/Download/DCIM"),
                name = "DCIM",
                isDirectory = true,
                size = 0L,
            ),
            fakeEntry(
                uri = URI("file:///storage/emulated/0/Download/IMG_0001.jpg"),
                name = "IMG_0001.jpg",
                isDirectory = false,
                size = 2_048_000L,
            ),
            fakeEntry(
                uri = URI("file:///storage/emulated/0/Download/合同.pdf"),
                name = "合同.pdf",
                isDirectory = false,
                size = 980_000L,
            ),
            fakeEntry(
                uri = URI("file:///storage/emulated/0/Download/clip.mp4"),
                name = "clip.mp4",
                isDirectory = false,
                size = 12_345_678L,
            ),
            fakeEntry(
                uri = URI("file:///storage/emulated/0/Download/archive.zip"),
                name = "archive.zip",
                isDirectory = false,
                size = 4_321_000L,
            ),
        )
        snapshot(
            FileBrowserUiState.Success(
                currentDir = URI("file:///storage/emulated/0/Download"),
                allEntries = entries,
                entries = entries,
            ),
        )
    }

    @Test
    fun fileBrowser_empty_snapshot() {
        snapshot(
            FileBrowserUiState.Empty(
                currentDir = URI("file:///storage/emulated/0/Empty"),
                allEntries = emptyList(),
            ),
        )
    }

    @Test
    fun fileBrowser_error_snapshot() {
        snapshot(
            FileBrowserUiState.Error(
                currentDir = URI("http://localhost:8080/files"),
                message = "HTTP 500: Internal Server Error",
            ),
        )
    }

    private fun snapshot(state: FileBrowserUiState) {
        paparazzi.snapshot {
            MaterialTheme {
                Surface {
                    FileBrowserContent(
                        state = state,
                        preferences = FileBrowserPreferences(),
                        multiSelection = MultiSelectionState(),
                        ftpHistory = emptyList(),
                        onGoBack = {},
                        onRefresh = {},
                        onPickSafDirectory = {},
                        onGoLocalRoot = {},
                        hasAllFilesAccess = true,
                        onRequestAllFilesAccess = {},
                        onNotePendingFtpConnection = {},
                        onToggleSearch = {},
                        onSearchQueryChange = {},
                        onClearSearch = {},
                        onSetSortOption = {},
                        onOpenSettings = {},
                        onPasteIntoCurrentDir = {},
                        onClearClipboard = {},
                        onOpenDirectory = {},
                        onOpenAsText = {},
                        onCreateFolder = {},
                        onEnterMultiSelection = {},
                        onToggleSelection = {},
                        onExitMultiSelection = {},
                        onSelectAll = {},
                        onCopySelected = {},
                        onCutSelected = {},
                        onDeleteSelected = {},
                        onDismissTextViewer = {},
                        onSetRoot = {},
                    )
                }
            }
        }
    }

    private fun fakeEntry(uri: URI, name: String, isDirectory: Boolean, size: Long): FileEntryUi {
        val lower = name.lowercase(Locale.ROOT)
        return FileEntryUi(
            uri = uri,
            name = name,
            displayName = name,
            subtitle = "",
            isDirectory = isDirectory,
            size = size,
            lastModified = 0L,
            nameLower = lower,
            nameLowerNoDot = lower.removePrefix("."),
        )
    }
}
