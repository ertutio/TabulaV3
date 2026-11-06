package com.tabula.v3.testsupport

import android.util.AtomicFile
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileOutputStream

/**
 * ShadowWindowsSafeAtomicFile 的必要契约测试（审计轮）。
 *
 * 目标契约（对齐 AOSP finishWrite/failWrite 的独立故障块语义）：
 * - sync 失败后仍尝试 close，且提交（.new→base）继续——sync/close 异常分开处理；
 * - 重复提交不抛出、不破坏已提交内容；
 * - failWrite 丢弃 .new、base 保持已提交内容（回滚）；
 * - legacy .bak 在 openRead/startWrite 时回迁替换 base（旧备份恢复）。
 *
 * 说明：本套件在 Linux CI 上同样经 shadow 运行（shadow 的替换语义与
 * rename(2) 等价）；等价性范围声明见 shadow KDoc。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class ShadowWindowsSafeAtomicFileContractTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private lateinit var baseFile: File

    @Before
    fun setUp() {
        baseFile = File(context.cacheDir, "shadow-atomic-contract.bin")
        baseFile.delete()
        File(baseFile.path + ".new").delete()
        File(baseFile.path + ".bak").delete()
    }

    @After
    fun tearDown() {
        baseFile.delete()
        File(baseFile.path + ".new").delete()
        File(baseFile.path + ".bak").delete()
    }

    @Test
    fun syncFailureStillClosesStreamAndCommits() {
        val atomic = AtomicFile(baseFile)
        val first = atomic.startWrite()
        first.write("v1".toByteArray())
        atomic.finishWrite(first)
        assertEquals("v1", baseFile.readText())

        // 先关闭流再提交：finishWrite 内 fd.sync() 命中已关闭 fd（sync 失败），
        // close 仍须被尝试，提交继续落到 base。
        var closeCalls = 0
        val second = object : FileOutputStream(baseFile.path + ".new") {
            override fun close() {
                closeCalls++
                super.close()
            }
        }
        second.write("v2".toByteArray())
        second.close()
        atomic.finishWrite(second)

        assertEquals("v2", baseFile.readText())
        assertTrue("sync 失败后仍调用 close", closeCalls >= 2)
    }

    @Test
    fun duplicateCommitDoesNotThrowAndKeepsLatestContent() {
        val atomic = AtomicFile(baseFile)
        val s1 = atomic.startWrite()
        s1.write("v1".toByteArray())
        atomic.finishWrite(s1)
        val s2 = atomic.startWrite()
        s2.write("v2".toByteArray())
        atomic.finishWrite(s2)

        // 第二次 finishWrite：.new 已被首次提交消费——不得抛出、不得破坏 base。
        atomic.finishWrite(s2)

        assertEquals("v2", baseFile.readText())
    }

    @Test
    fun failWriteDiscardsNewFileAndKeepsCommittedBase() {
        val atomic = AtomicFile(baseFile)
        val s1 = atomic.startWrite()
        s1.write("v1".toByteArray())
        atomic.finishWrite(s1)

        val s2 = atomic.startWrite()
        s2.write("garbage".toByteArray())
        atomic.failWrite(s2)

        assertEquals("v1", baseFile.readText())
        assertFalse(File(baseFile.path + ".new").exists())
    }

    @Test
    fun legacyBackupRestoresBaseOnOpenReadAndConsumedByStartWrite() {
        baseFile.writeText("stale")
        File(baseFile.path + ".bak").writeText("legacy")
        val atomic = AtomicFile(baseFile)

        // openRead：legacy .bak 回迁替换 base（旧备份恢复契约）。
        atomic.openRead().use { stream ->
            assertEquals("legacy", stream.readBytes().toString(Charsets.UTF_8))
        }
        assertEquals("legacy", baseFile.readText())
        assertFalse(File(baseFile.path + ".bak").exists())

        // 回迁后写循环正常：.bak 已消费，新提交直接落地。
        val s = atomic.startWrite()
        s.write("v2".toByteArray())
        atomic.finishWrite(s)
        assertEquals("v2", baseFile.readText())
    }
}
