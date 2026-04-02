package com.example.filemanager.presentation

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import java.net.URI
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
        snapshot(
            FileBrowserUiState.Success(
                currentDir = URI("file:///storage/emulated/0/Download"),
                entries = listOf(
                    FileEntryUi(
                        uri = URI("file:///storage/emulated/0/Download/DCIM"),
                        name = "DCIM",
                        isDirectory = true,
                        size = 0L,
                        lastModified = 0L,
                    ),
                    FileEntryUi(
                        uri = URI("file:///storage/emulated/0/Download/IMG_0001.jpg"),
                        name = "IMG_0001.jpg",
                        isDirectory = false,
                        size = 2_048_000L,
                        lastModified = 0L,
                    ),
                    FileEntryUi(
                        uri = URI("file:///storage/emulated/0/Download/合同.pdf"),
                        name = "合同.pdf",
                        isDirectory = false,
                        size = 980_000L,
                        lastModified = 0L,
                    ),
                    FileEntryUi(
                        uri = URI("file:///storage/emulated/0/Download/clip.mp4"),
                        name = "clip.mp4",
                        isDirectory = false,
                        size = 12_345_678L,
                        lastModified = 0L,
                    ),
                    FileEntryUi(
                        uri = URI("file:///storage/emulated/0/Download/archive.zip"),
                        name = "archive.zip",
                        isDirectory = false,
                        size = 4_321_000L,
                        lastModified = 0L,
                    ),
                ),
            ),
        )
    }

    @Test
    fun fileBrowser_empty_snapshot() {
        snapshot(
            FileBrowserUiState.Empty(
                currentDir = URI("file:///storage/emulated/0/Empty"),
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
                        onGoBack = {},
                        onRefresh = {},
                        onPickSafDirectory = {},
                        onGoLocalRoot = {},
                        hasAllFilesAccess = true,
                        onRequestAllFilesAccess = {},
                        onPasteIntoCurrentDir = {},
                        onClearClipboard = {},
                        onOpenDirectory = {},
                        onOpenAsText = {},
                        onCreateFolder = {},
                        onDelete = {},
                        onRename = { _, _ -> },
                        onCopy = {},
                        onCut = {},
                        onDismissTextViewer = {},
                        onSetRoot = {},
                    )
                }
            }
        }
    }
}
