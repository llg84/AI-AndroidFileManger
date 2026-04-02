package com.example.filemanager

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocalFileIoRobolectricTest {
    @Test
    fun writeAndReadInternalFile_usingContextFilesDir() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dir = context.filesDir
        assertTrue("filesDir 不存在: $dir", dir.exists())

        val f = File(dir, "jvm_smoke.txt")
        try {
            f.writeText("ok")
            assertEquals("ok", f.readText())
        } finally {
            // 避免 build/test 过程中重复运行导致残留
            f.delete()
        }
    }
}
