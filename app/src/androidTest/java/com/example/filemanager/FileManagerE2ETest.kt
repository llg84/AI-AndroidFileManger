package com.example.filemanager

import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.ext.junit.rules.ActivityScenarioRule
import com.example.filemanager.presentation.EXTRA_E2E_USE_INTERNAL_ROOT
import com.example.filemanager.presentation.MainActivity
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 深层 E2E 插桩测试：
 * - 不依赖本地真机/外部存储权限
 * - 通过 intent extra 让应用以 internal storage 作为根目录，确保在 Firebase Test Lab 稳定可跑
 */
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalTestApi::class)
class FileManagerE2ETest {

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

    @Test
    fun appLaunch_showsRootScreenElements() {
        // internal storage 的最后一级目录通常是 "files"（/data/user/0/<pkg>/files）
        composeRule.waitUntilAtLeastOneExists(hasText("files"), 10_000)
        assertTrue(composeRule.onAllNodes(hasText("files")).fetchSemanticsNodes().isNotEmpty())
        assertTrue(composeRule.onAllNodes(hasContentDescription("新建文件夹")).fetchSemanticsNodes().isNotEmpty())

        // 新 UI：刷新入口收敛到右上角“更多”菜单内。
        assertTrue(composeRule.onAllNodes(hasContentDescription("更多")).fetchSemanticsNodes().isNotEmpty())
        composeRule.onNodeWithContentDescription("更多").performClick()
        composeRule.waitUntilAtLeastOneExists(hasText("刷新") and hasClickAction(), 5_000)
    }

    @Test
    fun createNavigateAndDeleteFolder_flowWorks() {
        // 1) 新建文件夹
        composeRule.onNodeWithContentDescription("新建文件夹").performClick()
        composeRule.waitUntilAtLeastOneExists(hasText("新建文件夹"), 5_000)

        composeRule.onNode(hasSetTextAction()).performTextInput("e2e")
        composeRule.onNodeWithText("创建").performClick()

        // 2) 列表出现新建的目录
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("e2e").fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(composeRule.onAllNodesWithText("e2e").fetchSemanticsNodes().isNotEmpty())

        // 3) 进入目录并返回
        composeRule.onNodeWithText("e2e").performClick()
        composeRule.waitUntilAtLeastOneExists(hasText("e2e"), 10_000)

        composeRule.onNodeWithContentDescription("返回").performClick()
        composeRule.waitUntilAtLeastOneExists(hasText("files"), 10_000)

        // 4) 通过更多菜单删除刚创建的目录
        // 新 UI：长按条目弹出操作菜单
        composeRule.onNodeWithText("e2e").performTouchInput { longClick() }
        composeRule.waitUntilAtLeastOneExists(hasText("删除") and hasClickAction(), 5_000)
        composeRule.onNode(hasText("删除") and hasClickAction()).performClick()

        // 弹窗确认
        composeRule.waitUntilAtLeastOneExists(hasText("确定删除该项吗？", substring = true), 5_000)
        composeRule.onNode(hasText("删除") and hasClickAction()).performClick()

        // 5) 确认删除生效
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("e2e").fetchSemanticsNodes().isEmpty()
        }
    }

    @Test
    fun testSearchTrash() {
        // 先创建一下 .trash 目录
        val file = java.io.File(composeRule.activity.filesDir, ".trash")
        file.mkdirs()
        
        // 确保列表显示
        composeRule.waitUntilAtLeastOneExists(hasText("files"), 10_000)
        
        // 点击搜索按钮
        composeRule.onNodeWithContentDescription("搜索").performClick()
        
        // 输入 trash
        composeRule.onNode(hasSetTextAction()).performTextInput("trash")
        
        // 等待 .trash 出现
        composeRule.waitUntilAtLeastOneExists(hasText(".trash"), 5_000)
        assertTrue(composeRule.onAllNodesWithText(".trash").fetchSemanticsNodes().isNotEmpty())
    }
}
