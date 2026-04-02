package com.example.filemanager

import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.ext.junit.rules.ActivityScenarioRule
import com.example.filemanager.presentation.EXTRA_E2E_USE_INTERNAL_ROOT
import com.example.filemanager.presentation.MainActivity
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockftpserver.fake.FakeFtpServer
import org.mockftpserver.fake.UserAccount
import org.mockftpserver.fake.filesystem.DirectoryEntry
import org.mockftpserver.fake.filesystem.FileEntry
import org.mockftpserver.fake.filesystem.UnixFakeFileSystem

/**
 * FTP 端到端 Compose UI 插桩测试：
 * - 用于替代不稳定的 adb shell + UIAutomator 脚本
 * - 只依赖 Compose 测试语义树（testTag / text / contentDescription）
 */
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalTestApi::class)
class FtpE2ETest {

    private val launchIntent: Intent = Intent(
        ApplicationProvider.getApplicationContext(),
        MainActivity::class.java,
    ).putExtra(EXTRA_E2E_USE_INTERNAL_ROOT, true)

    private val activityRule = ActivityScenarioRule<MainActivity>(launchIntent)

    @get:Rule
    val composeRule: AndroidComposeTestRule<ActivityScenarioRule<MainActivity>, MainActivity> =
        AndroidComposeTestRule(activityRule) { rule ->
            getActivityFromRule(rule)
        }

    private fun getActivityFromRule(rule: ActivityScenarioRule<MainActivity>): MainActivity {
        var activity: MainActivity? = null
        val latch = CountDownLatch(1)
        rule.scenario.onActivity {
            activity = it
            latch.countDown()
        }
        check(latch.await(10, TimeUnit.SECONDS)) { "Timed out waiting for Activity" }
        return requireNotNull(activity)
    }

    private fun waitAndClick(matcher: SemanticsMatcher, timeoutMs: Long = 15_000) {
        composeRule.waitUntil(timeoutMillis = timeoutMs) {
            composeRule.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNode(matcher).assertIsDisplayed().performClick()
    }

    private fun clickBottomTab(label: String) {
        val matcher = hasText(label) and hasClickAction()
        // NavigationBarItem 的 clickAction 在合并语义树里更稳定。
        composeRule.waitUntilAtLeastOneExists(matcher, 10_000)
        composeRule.onNode(matcher).performClick()
    }

    private fun waitAndClickEntry(name: String, timeoutMs: Long = 25_000) {
        waitAndClick(hasText(name) and hasClickAction(), timeoutMs)
    }

    @Test
    fun ftp_connect_navigate_three_levels_and_copyFile() {
        // 0) 在设备侧启动一个轻量 FTP server（避免依赖宿主机端口转发 / 外部网络）。
        val port = ServerSocket(0).use { it.localPort }
        val ftpServer = FakeFtpServer().apply {
            serverControlPort = port
            addUserAccount(UserAccount("user", "pass", "/"))

            val fs = UnixFakeFileSystem().apply {
                add(DirectoryEntry("/"))
                add(DirectoryEntry("/level1"))
                add(DirectoryEntry("/level1/level2"))
                add(DirectoryEntry("/level1/level2/level3"))
                add(FileEntry("/level1/level2/level3/hello.txt", "hello"))
            }
            fileSystem = fs
        }

        ftpServer.start()

        try {
        // 1) 启动 App，点击底部“网络” Tab。
        clickBottomTab("网络")

        // 2) 触发添加网络服务器弹窗。
        // Network Tab 的 FAB icon contentDescription = "添加服务器"
        composeRule.onNodeWithContentDescription("添加服务器").assertIsDisplayed().performClick()

        // 3) 点击 ftp_protocol_button 切换到 FTP。
        composeRule.onNodeWithTag("ftp_protocol_button").assertIsDisplayed().performClick()

        // 4) 输入 host / port。
        composeRule.onNodeWithTag("ftp_host_input").assertIsDisplayed().performTextClearance()
        composeRule.onNodeWithTag("ftp_host_input").performTextInput("127.0.0.1")

        composeRule.onNodeWithTag("ftp_port_input").assertIsDisplayed().performTextClearance()
        composeRule.onNodeWithTag("ftp_port_input").performTextInput(port.toString())

        // 4.1) 输入账号（FakeFtpServer 需要）。
        composeRule.onNodeWithTag("ftp_username_input").assertIsDisplayed().performTextClearance()
        composeRule.onNodeWithTag("ftp_username_input").performTextInput("user")
        composeRule.onNodeWithTag("ftp_password_input").assertIsDisplayed().performTextClearance()
        composeRule.onNodeWithTag("ftp_password_input").performTextInput("pass")

        // 5) 点击连接。
        composeRule.onNodeWithTag("ftp_connect_button").assertIsDisplayed().performClick()

        // 6) 模拟等待并依次点击进入 level1 / level2 / level3。
        waitAndClickEntry("level1")
        waitAndClickEntry("level2")
        waitAndClickEntry("level3")

        // 7) 模拟执行选中文件并复制：长按任意一个“文件项”，再点击「复制」。
        // FlatFileRow 的 subtitle 对文件是 "<size> B"，对目录是 "目录"。
        val anyFileRow = hasText(" B", substring = true) and hasClickAction()
        composeRule.waitUntilAtLeastOneExists(anyFileRow, 25_000)
        composeRule.onNode(anyFileRow).performTouchInput { longClick() }

        val copyAction = hasText("复制") and hasClickAction()
        composeRule.waitUntilAtLeastOneExists(copyAction, 5_000)
        composeRule.onNode(copyAction).performClick()
        } finally {
            runCatching { ftpServer.stop() }
        }
    }
}
