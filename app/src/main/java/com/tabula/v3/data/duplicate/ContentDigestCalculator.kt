package com.tabula.v3.data.duplicate

import android.content.ContentResolver
import com.tabula.v3.data.model.ImageFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import java.security.MessageDigest

/**
 * 副本检测用的全文摘要器（路线图 #83 第 5 项第一步）。
 *
 * 为什么单独一个类：仓库里已有的 SHA-256 计算分散在
 * SystemAlbumSyncManager（复制证据）和 AlbumManager（清理前深度复核）里，
 * 都绑定各自的业务流程。副本检测需要在任意一组照片上按需计算，且要能
 * **可取消**——用户翻一页不该被几百兆的计算卡住。
 *
 * 约束：
 * - 只读流，不写文件、不改 Metadata。
 * - 逐块计算，不把整张图读进内存（大图会 OOM）。
 * - 取消立即生效（协程取消点 + 流关闭由 use 处理）。
 * - 失败返回 [Result.failure]，调用方据此**放弃提示**而不是猜。
 */
class ContentDigestCalculator(
    private val contentResolver: ContentResolver,
    private val bufferSize: Int = DEFAULT_BUFFER_SIZE
) {

    sealed interface Result {
        data class Success(val version: DuplicateRules.ContentVersion) : Result
        data class Failed(val reason: String) : Result
    }

    /**
     * 计算一张照片的全文摘要。大小取自 [ImageFile.size]；若为 0 则读取时
     * 统计实际字节数——0 字节的"相同"没有意义，交给
     * [DuplicateRules.ContentVersion.isMeaningful] 判定。
     */
    suspend fun digest(image: ImageFile): Result = withContext(Dispatchers.IO) {
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0L
            contentResolver.openInputStream(image.uri)?.use { stream ->
                val buffer = ByteArray(bufferSize)
                while (true) {
                    // 每块之间响应取消，避免大图把用户操作卡住
                    coroutineContext.ensureActive()
                    val read = stream.read(buffer)
                    if (read <= 0) break
                    digest.update(buffer, 0, read)
                    total += read
                }
            } ?: return@withContext Result.Failed("stream unavailable")

            val size = if (image.size > 0L) image.size else total
            Result.Success(
                DuplicateRules.ContentVersion(
                    sha256 = digest.digest().joinToString("") { "%02x".format(it) },
                    size = size
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: SecurityException) {
            // 部分照片权限下读不到：放弃提示，不报错弹窗
            Result.Failed("permission denied")
        } catch (e: Exception) {
            Result.Failed(e.message ?: "digest failed")
        }
    }

    /**
     * 并行为一组照片算摘要，返回可参与比较的映射。
     *
     * 读不到的照片**不进入结果**——它们既不算"有副本"，也不阻止其他照片
     * 的判定。这比"整体失败"更符合用户预期：一张坏图不该让整页提示消失。
     */
    suspend fun digestAll(images: List<ImageFile>): Map<ImageFile, DuplicateRules.ContentVersion> {
        val results = mutableMapOf<ImageFile, DuplicateRules.ContentVersion>()
        images.forEach { image ->
            when (val result = digest(image)) {
                is Result.Success -> results[image] = result.version
                is Result.Failed -> Unit
            }
        }
        return results
    }

    companion object {
        /** 64 KiB：大图也能保持低内存占用。 */
        const val DEFAULT_BUFFER_SIZE = 64 * 1024
    }
}
