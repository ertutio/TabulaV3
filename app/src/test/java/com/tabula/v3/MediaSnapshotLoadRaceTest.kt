package com.tabula.v3

import android.net.Uri
import com.tabula.v3.data.model.ImageFile
import com.tabula.v3.data.repository.AlbumManager
import com.tabula.v3.data.repository.FileOperationManager
import com.tabula.v3.data.repository.LocalImageRepository
import com.tabula.v3.data.repository.MemoryRecycleBinStorage
import com.tabula.v3.data.repository.RecycleBinManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * T07/TC32–35（审计轮 2）：旧扫描晚返回不覆盖新权限状态——受控竞争测试。
 *
 * 生产守卫 = mediaAccessRevision 键控的 LaunchedEffect 重启（键变化 → 取消
 * 在途扫描）。本文件驱动真实加载-提交链 [loadAndApplyMediaSnapshot]（真实
 * Dispatchers.IO），以显式握手（CompletableDeferred/Atomic*，跨线程安全，
 * 无真实 sleep）协调两个明确场景：
 *
 * 1. **合作取消**（[oldScanCooperativelyCancelledAtBlockingPointNeverCommits]）：
 *    旧加载到达阻塞点时发出 started 信号，测试等待 started 后再取消——证明
 *    取消在挂起点传播、旧任务不提交、不落 error 态。
 * 2. **晚返回**（[lateReturningOldReadReturnsButNeverCommits]）：旧加载已开始
 *    且暂时不响应取消（NonCancellable 段模拟不可中断的底层读取）；确认 started
 *    → 取消旧任务 → 新扫描完成并提交 → 释放旧读取；记录并断言旧读取**确实
 *    返回了**（结果在手上），但旧快照不能提交或覆盖新状态——提交保护 =
 *    提交边界处的取消检查（withContext 退出 + apply 前的取消传递）。
 *
 * 变异对照：将提交链的 withContext 换成 NonCancellable（移除旧结果提交保护）
 * 使晚返回用例 FAIL（旧扫描在释放后完成提交）——见提交说明。
 * 对照测试 [noRaceScanCommitsNormally] 保证无竞争提交路径本身完好。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class MediaSnapshotLoadRaceTest {
    private val context get() = RuntimeEnvironment.getApplication()

    private fun image(id: Long) = ImageFile(
        id = id,
        uri = Uri.parse("content://media/external_primary/images/media/$id"),
        displayName = "img$id",
        dateModified = 1_000L * id,
        size = 1L,
        width = 32,
        height = 32,
        bucketDisplayName = "Camera",
        volumeName = "external_primary"
    )

    private fun snapshot(vararg ids: Long) = LocalImageRepository.LoadResult(
        allImages = ids.map { image(it) },
        imagesExcludingTabula = ids.map { image(it) },
        systemBuckets = emptyList()
    )

    private class Recorder {
        val committedBy = AtomicReference<String?>(null)
        val errors = AtomicReference<String?>(null)
        val appliedImages = AtomicReference<List<Long>>(emptyList())
    }

    @Test
    fun oldScanCooperativelyCancelledAtBlockingPointNeverCommits() = runBlocking {
        val recycleBinManager = RecycleBinManager(MemoryRecycleBinStorage(), MemoryRecycleBinStorage())
        val albumManager = AlbumManager(context)
        val fileOperationManager = FileOperationManager(context)
        val startedOld = CompletableDeferred<Unit>()
        val releaseOld = CompletableDeferred<Unit>()
        val recorder = Recorder()

        // 旧扫描：合作式阻塞点（挂起在可取消的 gate 上）
        val oldScan = async {
            loadAndApplyMediaSnapshot(
                context = context,
                recycleBinManager = recycleBinManager,
                albumManager = albumManager,
                fileOperationManager = fileOperationManager,
                deletePermissionInFlight = false,
                onRecycleUnknown = {},
                onError = { recorder.errors.set("old") },
                onFinally = {},
                load = {
                    startedOld.complete(Unit)
                    releaseOld.await()   // 可取消阻塞：取消在此传播
                    Pair(snapshot(1), emptyList())
                },
                apply = { projected, _ ->
                    recorder.committedBy.set("old")
                    recorder.appliedImages.set(projected.allImages.map { it.id })
                }
            )
        }
        // 明确等待旧加载真正到达阻塞点（此前 runCurrent 无法保证这一点）
        withTimeout(10_000) { startedOld.await() }

        // 新权限状态生效：键控重启取消在途旧扫描
        oldScan.cancel()
        oldScan.join()

        // 新扫描完成并提交
        val newScan = launch {
            loadAndApplyMediaSnapshot(
                context = context,
                recycleBinManager = recycleBinManager,
                albumManager = albumManager,
                fileOperationManager = fileOperationManager,
                deletePermissionInFlight = false,
                onRecycleUnknown = {},
                onError = { recorder.errors.set("new") },
                onFinally = {},
                load = { Pair(snapshot(2), emptyList()) },
                apply = { projected, _ ->
                    recorder.committedBy.set("new")
                    recorder.appliedImages.set(projected.allImages.map { it.id })
                }
            )
        }
        newScan.join()

        assertEquals("提交只归新扫描", "new", recorder.committedBy.get())
        assertTrue("取消不得落 error 态", recorder.errors.get() == null)
        assertEquals("最终状态保持最新扫描数据", listOf(2L), recorder.appliedImages.get())
    }

    @Test
    fun lateReturningOldReadReturnsButNeverCommits() = runBlocking {
        val recycleBinManager = RecycleBinManager(MemoryRecycleBinStorage(), MemoryRecycleBinStorage())
        val albumManager = AlbumManager(context)
        val fileOperationManager = FileOperationManager(context)
        val startedOld = CompletableDeferred<Unit>()
        val releaseOld = CompletableDeferred<Unit>()
        val recorder = Recorder()
        val oldReadReturned = AtomicBoolean(false)

        // 旧扫描：底层读取已开始且暂时不响应取消（NonCancellable 段模拟）——
        // 读取完成后记录"确实返回"，结果在手上；提交边界由取消检查守护
        val oldScan = async {
            loadAndApplyMediaSnapshot(
                context = context,
                recycleBinManager = recycleBinManager,
                albumManager = albumManager,
                fileOperationManager = fileOperationManager,
                deletePermissionInFlight = false,
                onRecycleUnknown = {},
                onError = { recorder.errors.set("old") },
                onFinally = {},
                load = {
                    startedOld.complete(Unit)
                    withContext(NonCancellable) {
                        releaseOld.await()   // 不可中断的底层读取
                    }
                    oldReadReturned.set(true)
                    Pair(snapshot(1), emptyList())
                },
                apply = { projected, _ ->
                    recorder.committedBy.set("old")
                    recorder.appliedImages.set(projected.allImages.map { it.id })
                }
            )
        }
        withTimeout(10_000) { startedOld.await() }

        // 取消旧任务（其读取暂不响应）；新扫描完成并提交
        oldScan.cancel()
        val newScan = launch {
            loadAndApplyMediaSnapshot(
                context = context,
                recycleBinManager = recycleBinManager,
                albumManager = albumManager,
                fileOperationManager = fileOperationManager,
                deletePermissionInFlight = false,
                onRecycleUnknown = {},
                onError = { recorder.errors.set("new") },
                onFinally = {},
                load = { Pair(snapshot(2), emptyList()) },
                apply = { projected, _ ->
                    recorder.committedBy.set("new")
                    recorder.appliedImages.set(projected.allImages.map { it.id })
                }
            )
        }
        newScan.join()

        // 释放旧读取——它确实返回（结果在手上），但提交必须被取消检查拦截
        releaseOld.complete(Unit)
        oldScan.join()

        assertTrue("旧读取确实返回了（晚返回形态成立）", oldReadReturned.get())
        assertTrue("新扫描必须已提交", "new" == recorder.committedBy.get())
        assertFalse("旧快照不得提交或覆盖新状态", "old" == recorder.committedBy.get())
        assertTrue("取消不得落 error 态", recorder.errors.get() == null)
        assertEquals("最终状态保持最新扫描数据", listOf(2L), recorder.appliedImages.get())
    }

    @Test
    fun noRaceScanCommitsNormally() = runBlocking {
        // 对照：无竞争时加载-提交链完整走通
        val recycleBinManager = RecycleBinManager(MemoryRecycleBinStorage(), MemoryRecycleBinStorage())
        val albumManager = AlbumManager(context)
        val fileOperationManager = FileOperationManager(context)
        val recorder = Recorder()

        val scan = launch {
            loadAndApplyMediaSnapshot(
                context = context,
                recycleBinManager = recycleBinManager,
                albumManager = albumManager,
                fileOperationManager = fileOperationManager,
                deletePermissionInFlight = false,
                onRecycleUnknown = {},
                onError = { recorder.errors.set("solo") },
                onFinally = {},
                load = { Pair(snapshot(7), emptyList()) },
                apply = { projected, _ ->
                    recorder.committedBy.set("solo")
                    recorder.appliedImages.set(projected.allImages.map { it.id })
                }
            )
        }
        scan.join()

        assertEquals("solo", recorder.committedBy.get())
        assertTrue(recorder.errors.get() == null)
        assertEquals(listOf(7L), recorder.appliedImages.get())
    }
}
