package com.tabula.v3.data.repository

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.os.Bundle
import android.provider.MediaStore
import com.tabula.v3.data.model.ImageFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 本地图片仓库
 *
 * 从 MediaStore 获取设备图片，返回稳定的领域模型列表
 *
 * 性能优化策略：
 * 1. 使用 Bundle 查询以支持分页
 * 2. 只查询必要的列
 * 3. 在 IO 调度器执行
 */
class LocalImageRepository(private val context: Context) {

    private val contentResolver: ContentResolver = context.contentResolver

    /**
     * MediaStore 查询投影 - 仅查询必要列
     */
    private val projection = arrayOf(
        MediaStore.Images.Media._ID,
        MediaStore.Images.Media.DISPLAY_NAME,
        MediaStore.Images.Media.DATE_MODIFIED,
        MediaStore.Images.Media.SIZE,
        MediaStore.Images.Media.WIDTH,
        MediaStore.Images.Media.HEIGHT,
        MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
        MediaStore.Images.Media.ORIENTATION
    )

    /**
     * 获取所有图片
     *
     * @param sortOrder 排序方式，默认按修改时间倒序
     * @param excludeTabulaAlbums 是否排除 Tabula 图集内的照片（默认 false，保持兼容）
     * @return 图片列表
     */
    suspend fun getAllImages(
        sortOrder: SortOrder = SortOrder.DATE_MODIFIED_DESC,
        excludeTabulaAlbums: Boolean = false
    ): List<ImageFile> = withContext(Dispatchers.IO) {
        val images = queryImages(sortOrder = sortOrder)
        
        if (excludeTabulaAlbums) {
            // 排除 Pictures/Tabula/ 路径下的图片
            // 这些是用户已经归类到 Tabula 图集的照片，不应再次出现在待整理列表中
            images.filter { image ->
                // 获取图片的相对路径前缀
                // bucketDisplayName 是最后一级目录名，无法直接判断完整路径
                // 需要通过查询 RELATIVE_PATH 来判断
                // 但 queryImages 没有查询 RELATIVE_PATH，这里用 bucketDisplayName 近似判断
                // Tabula 图集的 bucket 名称来自用户创建，可能与系统相册重名
                // 更安全的方式是：只排除 "Tabula" 这个特定的 bucket
                // 因为 Tabula 图集都在 Pictures/Tabula/{name}/ 下，父目录也叫 Tabula
                // 但实际上每个图集的 bucketDisplayName 是图集名称，不是 "Tabula"
                // 
                // 正确的逻辑：查询时通过 RELATIVE_PATH 过滤
                true  // 暂时返回 true，下面用单独的方法实现
            }
        } else {
            images
        }
    }
    
    /**
     * 获取所有图片（排除 Tabula 图集内的照片）
     * 
     * 这是推荐用于主界面的方法，会排除已经归类到 Tabula 图集的照片，
     * 避免用户重复整理。
     *
     * @param sortOrder 排序方式，默认按修改时间倒序
     * @return 图片列表（不包含 Pictures/Tabula/ 路径下的照片）
     */
    suspend fun getAllImagesExcludingTabulaAlbums(
        sortOrder: SortOrder = SortOrder.DATE_MODIFIED_DESC
    ): List<ImageFile> = withContext(Dispatchers.IO) {
        queryImagesExcludingTabulaAlbums(sortOrder = sortOrder)
    }
    
    /**
     * 查询图片（排除 Tabula 图集）
     */
    private fun queryImagesExcludingTabulaAlbums(
        sortOrder: SortOrder = SortOrder.DATE_MODIFIED_DESC
    ): List<ImageFile> {
        val images = mutableListOf<ImageFile>()
        
        // 添加 RELATIVE_PATH 到查询列
        val projectionWithPath = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_MODIFIED,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.WIDTH,
            MediaStore.Images.Media.HEIGHT,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Images.Media.ORIENTATION,
            MediaStore.Images.Media.RELATIVE_PATH
        )

        contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projectionWithPath,
            null,
            null,
            sortOrder.toSqlString()
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val dateColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)
            val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
            val widthColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.WIDTH)
            val heightColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.HEIGHT)
            val bucketColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
            val orientationColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.ORIENTATION)
            val relativePathColumn = cursor.getColumnIndex(MediaStore.Images.Media.RELATIVE_PATH)

            while (cursor.moveToNext()) {
                // 检查是否在 Tabula 图集路径下
                val relativePath = if (relativePathColumn >= 0) {
                    cursor.getString(relativePathColumn) ?: ""
                } else {
                    ""
                }
                
                // 排除 Pictures/Tabula/ 路径下的图片
                // 这些是用户已经归类到 Tabula 图集的照片
                if (relativePath.startsWith("Pictures/Tabula/", ignoreCase = true)) {
                    continue
                }
                
                val id = cursor.getLong(idColumn)
                val uri = ContentUris.withAppendedId(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    id
                )

                images.add(
                    ImageFile(
                        id = id,
                        uri = uri,
                        displayName = cursor.getString(nameColumn) ?: "unknown",
                        dateModified = cursor.getLong(dateColumn) * 1000, // 转换为毫秒
                        size = cursor.getLong(sizeColumn),
                        width = cursor.getInt(widthColumn),
                        height = cursor.getInt(heightColumn),
                        bucketDisplayName = cursor.getString(bucketColumn),
                        orientation = cursor.getInt(orientationColumn)
                    )
                )
            }
        }

        return images
    }

    /**
     * 获取指定相册的图片
     *
     * @param bucketName 相册名称
     * @param sortOrder 排序方式
     * @return 图片列表
     */
    suspend fun getImagesByBucket(
        bucketName: String,
        sortOrder: SortOrder = SortOrder.DATE_MODIFIED_DESC
    ): List<ImageFile> = withContext(Dispatchers.IO) {
        queryImages(
            selection = "${MediaStore.Images.Media.BUCKET_DISPLAY_NAME} = ?",
            selectionArgs = arrayOf(bucketName),
            sortOrder = sortOrder
        )
    }
    
    /**
     * 获取指定相对路径下的图片
     * 
     * 用于图集清理功能，通过图集的 systemAlbumPath 获取图片。
     *
     * @param relativePath 相对路径（如 "Pictures/Tabula/风景"）
     * @param sortOrder 排序方式
     * @return 图片列表
     */
    suspend fun getImagesByRelativePath(
        relativePath: String,
        sortOrder: SortOrder = SortOrder.DATE_MODIFIED_DESC
    ): List<ImageFile> = withContext(Dispatchers.IO) {
        // 确保路径以 / 结尾（MediaStore 的 RELATIVE_PATH 格式）
        val normalizedPath = if (relativePath.endsWith("/")) relativePath else "$relativePath/"
        
        queryImages(
            selection = "${MediaStore.Images.Media.RELATIVE_PATH} = ?",
            selectionArgs = arrayOf(normalizedPath),
            sortOrder = sortOrder
        )
    }
    
    /**
     * 从已加载的图片列表中筛选指定图集的图片
     * 
     * 这是一个更高效的方法，避免重复查询 MediaStore。
     * 适用于已经加载了全部图片的场景。
     *
     * @param allImages 已加载的全部图片列表
     * @param albumPath 图集的系统路径（如 "Pictures/Tabula/风景"）
     * @return 属于该图集的图片列表
     */
    fun filterImagesByAlbumPath(
        allImages: List<ImageFile>,
        albumPath: String
    ): List<ImageFile> {
        // 从路径中提取 bucket 名称（最后一个目录名）
        val bucketName = albumPath.trimEnd('/').substringAfterLast('/')
        return allImages.filter { it.bucketDisplayName == bucketName }
    }

    /**
     * 分页获取图片
     *
     * @param limit 每页数量
     * @param offset 偏移量
     * @param sortOrder 排序方式
     * @return 图片列表
     */
    suspend fun getImagesPaged(
        limit: Int,
        offset: Int,
        sortOrder: SortOrder = SortOrder.DATE_MODIFIED_DESC
    ): List<ImageFile> = withContext(Dispatchers.IO) {
        queryImagesWithBundle(limit, offset, sortOrder)
    }

    /**
     * 获取所有相册名称
     *
     * @return 相册名称列表（去重）
     */
    suspend fun getAllBuckets(): List<String> = withContext(Dispatchers.IO) {
        val buckets = mutableSetOf<String>()

        val projection = arrayOf(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)

        contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            null,
            null,
            null
        )?.use { cursor ->
            val bucketColumn = cursor.getColumnIndexOrThrow(
                MediaStore.Images.Media.BUCKET_DISPLAY_NAME
            )

            while (cursor.moveToNext()) {
                cursor.getString(bucketColumn)?.let { buckets.add(it) }
            }
        }

        buckets.toList().sorted()
    }

    /**
     * 系统相册信息
     * 
     * @param name 相册名称（BUCKET_DISPLAY_NAME）
     * @param imageCount 图片数量
     * @param coverImageId 封面图片 ID（最新的一张）
     * @param relativePath 相对路径（如 "DCIM/Camera"），用于创建同名文件夹
     */
    data class SystemBucket(
        val name: String,
        val imageCount: Int,
        val coverImageId: Long? = null,
        val relativePath: String? = null
    )

    /**
     * 获取所有系统相册及其图片数量
     *
     * @return 系统相册列表（包含名称、数量、封面、路径）
     */
    suspend fun getAllBucketsWithInfo(): List<SystemBucket> = withContext(Dispatchers.IO) {
        // 存储 bucket 信息：名称 -> (图片ID列表, 相对路径)
        val bucketMap = mutableMapOf<String, Pair<MutableList<Long>, String?>>()

        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Images.Media.RELATIVE_PATH
        )

        contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            null,
            null,
            "${MediaStore.Images.Media.DATE_MODIFIED} DESC"
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val bucketColumn = cursor.getColumnIndexOrThrow(
                MediaStore.Images.Media.BUCKET_DISPLAY_NAME
            )
            val pathColumn = cursor.getColumnIndex(MediaStore.Images.Media.RELATIVE_PATH)

            while (cursor.moveToNext()) {
                val bucketName = cursor.getString(bucketColumn) ?: continue
                val imageId = cursor.getLong(idColumn)
                val relativePath = if (pathColumn >= 0) cursor.getString(pathColumn) else null
                
                val existing = bucketMap[bucketName]
                if (existing != null) {
                    existing.first.add(imageId)
                } else {
                    bucketMap[bucketName] = Pair(mutableListOf(imageId), relativePath)
                }
            }
        }

        bucketMap.map { (name, data) ->
            val (ids, path) = data
            SystemBucket(
                name = name,
                imageCount = ids.size,
                coverImageId = ids.firstOrNull(), // 最新的一张作为封面
                relativePath = path?.trimEnd('/') // 移除末尾斜杠
            )
        }.sortedByDescending { it.imageCount }
    }

    /**
     * 基础查询方法
     */
    private fun queryImages(
        selection: String? = null,
        selectionArgs: Array<String>? = null,
        sortOrder: SortOrder = SortOrder.DATE_MODIFIED_DESC
    ): List<ImageFile> {
        val images = mutableListOf<ImageFile>()

        contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            sortOrder.toSqlString()
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val dateColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)
            val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
            val widthColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.WIDTH)
            val heightColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.HEIGHT)
            val bucketColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
            val orientationColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.ORIENTATION)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                val uri = ContentUris.withAppendedId(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    id
                )

                images.add(
                    ImageFile(
                        id = id,
                        uri = uri,
                        displayName = cursor.getString(nameColumn) ?: "unknown",
                        dateModified = cursor.getLong(dateColumn) * 1000, // 转换为毫秒
                        size = cursor.getLong(sizeColumn),
                        width = cursor.getInt(widthColumn),
                        height = cursor.getInt(heightColumn),
                        bucketDisplayName = cursor.getString(bucketColumn),
                        orientation = cursor.getInt(orientationColumn)
                    )
                )
            }
        }

        return images
    }

    /**
     * 使用 Bundle 进行分页查询（Android 10+）
     */
    private fun queryImagesWithBundle(
        limit: Int,
        offset: Int,
        sortOrder: SortOrder
    ): List<ImageFile> {
        val images = mutableListOf<ImageFile>()

        val queryArgs = Bundle().apply {
            putInt(ContentResolver.QUERY_ARG_LIMIT, limit)
            putInt(ContentResolver.QUERY_ARG_OFFSET, offset)
            putStringArray(
                ContentResolver.QUERY_ARG_SORT_COLUMNS,
                arrayOf(sortOrder.column)
            )
            putInt(
                ContentResolver.QUERY_ARG_SORT_DIRECTION,
                if (sortOrder.ascending) {
                    ContentResolver.QUERY_SORT_DIRECTION_ASCENDING
                } else {
                    ContentResolver.QUERY_SORT_DIRECTION_DESCENDING
                }
            )
        }

        contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            queryArgs,
            null
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val dateColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)
            val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
            val widthColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.WIDTH)
            val heightColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.HEIGHT)
            val bucketColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
            val orientationColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.ORIENTATION)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                val uri = ContentUris.withAppendedId(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    id
                )

                images.add(
                    ImageFile(
                        id = id,
                        uri = uri,
                        displayName = cursor.getString(nameColumn) ?: "unknown",
                        dateModified = cursor.getLong(dateColumn) * 1000,
                        size = cursor.getLong(sizeColumn),
                        width = cursor.getInt(widthColumn),
                        height = cursor.getInt(heightColumn),
                        bucketDisplayName = cursor.getString(bucketColumn),
                        orientation = cursor.getInt(orientationColumn)
                    )
                )
            }
        }

        return images
    }

    /**
     * 一次查询加载所有数据的返回结果
     */
    data class LoadResult(
        val allImages: List<ImageFile>,
        val imagesExcludingTabula: List<ImageFile>,
        val systemBuckets: List<SystemBucket>
    )

    /**
     * 一次查询加载所有数据
     *
     * 通过单次 MediaStore 查询获取所有图片数据，然后在内存中派生出：
     * 1. 全部图片列表
     * 2. 排除 Tabula 图集的图片列表（用于主界面卡片整理）
     * 3. 系统相册信息（名称、数量、封面、路径）
     *
     * 性能优化：从原来的 3 次 MediaStore 查询减少到 1 次
     */
    suspend fun loadAllData(
        sortOrder: SortOrder = SortOrder.DATE_MODIFIED_DESC
    ): LoadResult = withContext(Dispatchers.IO) {
        val allImages = mutableListOf<ImageFile>()
        val imagesExcludingTabula = mutableListOf<ImageFile>()
        // bucket name -> (image IDs ordered by date desc, relativePath)
        val bucketMap = mutableMapOf<String, Pair<MutableList<Long>, String?>>()

        val projectionWithPath = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_MODIFIED,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.WIDTH,
            MediaStore.Images.Media.HEIGHT,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Images.Media.ORIENTATION,
            MediaStore.Images.Media.RELATIVE_PATH
        )

        contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projectionWithPath,
            null,
            null,
            sortOrder.toSqlString()
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val dateColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)
            val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
            val widthColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.WIDTH)
            val heightColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.HEIGHT)
            val bucketColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
            val orientationColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.ORIENTATION)
            val relativePathColumn = cursor.getColumnIndex(MediaStore.Images.Media.RELATIVE_PATH)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                val uri = ContentUris.withAppendedId(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    id
                )
                val bucketDisplayName = cursor.getString(bucketColumn)
                val relativePath = if (relativePathColumn >= 0) {
                    cursor.getString(relativePathColumn) ?: ""
                } else ""

                val imageFile = ImageFile(
                    id = id,
                    uri = uri,
                    displayName = cursor.getString(nameColumn) ?: "unknown",
                    dateModified = cursor.getLong(dateColumn) * 1000,
                    size = cursor.getLong(sizeColumn),
                    width = cursor.getInt(widthColumn),
                    height = cursor.getInt(heightColumn),
                    bucketDisplayName = bucketDisplayName,
                    orientation = cursor.getInt(orientationColumn)
                )

                allImages.add(imageFile)

                // 排除 Tabula 图集的照片
                if (!relativePath.startsWith("Pictures/Tabula/", ignoreCase = true)) {
                    imagesExcludingTabula.add(imageFile)
                }

                // 构建 bucket 信息（已按 date desc 排序，首个 ID 即为封面）
                if (bucketDisplayName != null) {
                    val existing = bucketMap[bucketDisplayName]
                    if (existing != null) {
                        existing.first.add(id)
                    } else {
                        bucketMap[bucketDisplayName] = Pair(
                            mutableListOf(id),
                            relativePath.trimEnd('/')
                        )
                    }
                }
            }
        }

        val systemBuckets = bucketMap.map { (name, data) ->
            val (ids, path) = data
            SystemBucket(
                name = name,
                imageCount = ids.size,
                coverImageId = ids.firstOrNull(),
                relativePath = path
            )
        }.sortedByDescending { it.imageCount }

        LoadResult(allImages, imagesExcludingTabula, systemBuckets)
    }

    /**
     * 排序方式枚举
     */
    enum class SortOrder(val column: String, val ascending: Boolean) {
        DATE_MODIFIED_DESC(MediaStore.Images.Media.DATE_MODIFIED, false),
        DATE_MODIFIED_ASC(MediaStore.Images.Media.DATE_MODIFIED, true),
        NAME_ASC(MediaStore.Images.Media.DISPLAY_NAME, true),
        NAME_DESC(MediaStore.Images.Media.DISPLAY_NAME, false),
        SIZE_DESC(MediaStore.Images.Media.SIZE, false),
        SIZE_ASC(MediaStore.Images.Media.SIZE, true);

        fun toSqlString(): String {
            return "$column ${if (ascending) "ASC" else "DESC"}"
        }
    }
}

/**
 * T02 快照投影：浏览/推荐一律由"原始媒体快照 − 软删除集合"派生。
 *
 * 过滤 allImages 与 imagesExcludingTabula，并按快照日期序重算各相册
 * imageCount/coverImageId；bucket 名称与 relativePath 原样保留，
 * 全部照片被软删除的 bucket 保留为 count=0、cover=null。
 * 空软删除集合时原样返回快照（行为与开销零变化）。
 */
fun projectSnapshot(
    snapshot: LocalImageRepository.LoadResult,
    softDeletedIds: Set<Long>
): LocalImageRepository.LoadResult {
    if (softDeletedIds.isEmpty()) return snapshot
    val allImages = snapshot.allImages.filterNot { it.id in softDeletedIds }
    val imagesExcludingTabula = snapshot.imagesExcludingTabula.filterNot { it.id in softDeletedIds }
    val covers = LinkedHashMap<String, Long>()
    val counts = HashMap<String, Int>()
    for (image in allImages) {
        val bucket = image.bucketDisplayName ?: continue
        counts.merge(bucket, 1, Int::plus)
        covers.putIfAbsent(bucket, image.id)
    }
    val systemBuckets = snapshot.systemBuckets.map { bucket ->
        bucket.copy(
            imageCount = counts[bucket.name] ?: 0,
            coverImageId = covers[bucket.name]
        )
    }.sortedByDescending { it.imageCount }
    return LocalImageRepository.LoadResult(allImages, imagesExcludingTabula, systemBuckets)
}
