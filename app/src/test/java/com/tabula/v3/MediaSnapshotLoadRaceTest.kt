package com.tabula.v3

import android.net.Uri
import com.tabula.v3.data.model.ImageFile
import com.tabula.v3.data.repository.AlbumManager
import com.tabula.v3.data.repository.FileOperationManager
import com.tabula.v3.data.repository.LocalImageRepository
import com.tabula.v3.data.repository.MemoryRecycleBinStorage
import com.tabula.v3.data.repository.RecycleBinManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * T07/TC32–35（审计轮）：旧扫描晚返回不覆盖新权限状态——受控竞争测试。
 *
 * 生产守卫 = mediaAccessRevision 键控的 LaunchedEffect 重启（键变化 → 取消
 * 在途扫描）；本测试用真实加载-提交链 [loadAndApplyMediaSnapshot] 复现该
 * 生命周期：旧扫描被受控阻塞（结果已算出、尚未提交）→ 新权限状态生效
 * （键控重启=取消旧扫描）→ 新扫描完成并提交 → 释放旧扫描。断言：
 * 1. 最终提交只归新扫描（旧扫描即使晚返回结果也不经 apply 提交）；
 * 2. 被取消的旧扫描不落 error 态（CancellationException 原样传播，提交路径
 *    与错误路径互不污染）；
 * 3. 提交的数据就是新扫描的最新快照。
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

    @Test
    fun lateOldScanNeverCommitsAfterNewerScanDeterministically() = runTest {
        val recycleBinManager = RecycleBinManager(MemoryRecycleBinStorage(), MemoryRecycleBinStorage())
        val albumManager = AlbumManager(context)
        val fileOperationManager = FileOperationManager(context)

        val releaseOldScan = CompletableDeferred<Unit>()
        val committedBy = mutableListOf<String>()
        val errors = mutableListOf<String>()
        var appliedImages: List<Long> = emptyList()

        // 旧扫描：结果已算出，被 gate 阻塞在返回前（受控"晚返回"）
        val oldScan = async {
            loadAndApplyMediaSnapshot(
                context = context,
                recycleBinManager = recycleBinManager,
                albumManager = albumManager,
                fileOperationManager = fileOperationManager,
                deletePermissionInFlight = false,
                onRecycleUnknown = {},
                onError = { errors += "old" },
                onFinally = {},
                load = {
                    releaseOldScan.await()   // 受控阻塞点
                    Pair(snapshot(1), emptyList())
                },
                apply = { projected, _ -> committedBy += "old"; appliedImages = projected.allImages.map { it.id } }
            )
        }
        // 确保旧扫描已真正挂起在阻塞点（进入受控窗口）
        testScheduler.runCurrent()

        // 新权限状态生效：键控重启取消在途旧扫描（框架语义由本测试模拟）
        oldScan.cancel()

        // 新扫描立即完成并提交
        val newScan = launch {
            loadAndApplyMediaSnapshot(
                context = context,
                recycleBinManager = recycleBinManager,
                albumManager = albumManager,
                fileOperationManager = fileOperationManager,
                deletePermissionInFlight = false,
                onRecycleUnknown = {},
                onError = { errors += "new" },
                onFinally = {},
                load = { Pair(snapshot(2), emptyList()) },
                apply = { projected, _ -> committedBy += "new"; appliedImages = projected.allImages.map { it.id } }
            )
        }
        newScan.join()

        // 释放旧扫描（晚返回）
        releaseOldScan.complete(Unit)
        oldScan.join()

        assertTrue("新扫描必须已提交", committedBy.contains("new"))
        assertFalse("被取消的旧扫描不得提交", committedBy.contains("old"))
        assertTrue("取消不得落 error 态（提交/错误路径互不污染）", errors.isEmpty())
        assertEquals("最终状态保持最新扫描数据", listOf(2L), appliedImages)
    }

    @Test
    fun newScanWithoutPermissionChangeCommitsNormally() = runBlockingControl {
        // 对照：无竞争时加载-提交链完整走通（提交路径本身未被破坏）
        val recycleBinManager = RecycleBinManager(MemoryRecycleBinStorage(), MemoryRecycleBinStorage())
        val albumManager = AlbumManager(context)
        val fileOperationManager = FileOperationManager(context)
        var appliedImages: List<Long> = emptyList()
        var errorReported = false

        loadAndApplyMediaSnapshot(
            context = context,
            recycleBinManager = recycleBinManager,
            albumManager = albumManager,
            fileOperationManager = fileOperationManager,
            deletePermissionInFlight = false,
            onRecycleUnknown = {},
            onError = { errorReported = true },
            onFinally = {},
            load = { Pair(snapshot(7), emptyList()) },
            apply = { projected, _ -> appliedImages = projected.allImages.map { it.id } }
        )
        assertEquals(listOf(7L), appliedImages)
        assertFalse(errorReported)
    }
}

/** runTest 包装：与首个测试同调度器语义，普通用例用 runTest 表达。 */
private fun runBlockingControl(block: suspend kotlinx.coroutines.CoroutineScope.() -> Unit) =
    kotlinx.coroutines.test.runTest { block() }
