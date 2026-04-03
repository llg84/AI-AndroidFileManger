package com.example.filemanager.presentation

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.net.URI
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
class FileBrowserScreenInteractionTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun clickDirectoryItem_afterLoaded_triggersOnNavigate() {
        val dirItem = fakeEntry(
            uri = URI("file:///storage/emulated/0/Download/DCIM"),
            name = "DCIM",
            isDirectory = true,
            size = 0L,
        )

        val state = FileBrowserUiState.Success(
            currentDir = URI("file:///storage/emulated/0/Download"),
            allEntries = listOf(dirItem),
            entries = listOf(dirItem),
        )

        var navigatedTo: URI? = null

        composeTestRule.setContent {
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
                        onOpenDirectory = { uri -> navigatedTo = uri },
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

        // 文件夹行：语义已合并（text + clickAction），用组合 matcher 精准点击。
        composeTestRule
            .onNode(hasText("DCIM").and(hasClickAction()))
            .assertIsDisplayed()
            .performClick()

        assertEquals(dirItem.uri, navigatedTo)
    }

    @Test
    fun clickRetry_triggersCallback() {
        val state = FileBrowserUiState.Error(
            currentDir = URI("file:///storage/emulated/0/Download"),
            message = "权限被拒绝，请授权。",
        )

        var retried = false

        composeTestRule.setContent {
            MaterialTheme {
                Surface {
                    FileBrowserContent(
                        state = state,
                        preferences = FileBrowserPreferences(),
                        multiSelection = MultiSelectionState(),
                        ftpHistory = emptyList(),
                        onGoBack = {},
                        onRefresh = { retried = true },
                        onPickSafDirectory = {},
                        onGoLocalRoot = {},
                        hasAllFilesAccess = false,
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

        composeTestRule.onNodeWithText("重试").assertIsDisplayed().performClick()
        assertTrue(retried)
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
