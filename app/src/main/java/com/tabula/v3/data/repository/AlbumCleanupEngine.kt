package com.tabula.v3.data.repository

import android.content.Context
import com.tabula.v3.data.cache.ImageHashCache
import com.tabula.v3.data.cache.toHashKey
import com.tabula.v3.data.model.Album
import com.tabula.v3.data.model.ImageFile
import com.tabula.v3.data.preferences.AppPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 图集清理批次
 * 
 * @param images 本批次的图片列表（可能包含多个小组合并）
 * @param groupIds 包含的组ID列表
 * @param groupBoundaries 组边界索引列表，例如 [0, 3, 8] 表示第一组是 0-2，第二组是 3-7
 */
data class AlbumCleanupBatch(
    val images: List<ImageFile>,
    val groupIds: List<String>,
    val groupBoundaries: List<Int>
) {
    /** 包含的组数量 */
    val groupCount: Int get() = groupIds.size
    
    /** 总图片数量 */
    val imageCount: Int get() = images.size
    
    /**
     * 获取指定索引对应的组ID
     */
    fun getGroupIdForIndex(index: Int): String? {
        // 索引越界检查
        if (index < 0 || index >= images.size) return null
        
        for (i in groupBoundaries.indices) {
            val start = groupBoundaries[i]
            val end = if (i + 1 < groupBoundaries.size) {
                groupBoundaries[i + 1]
            } else {
                images.size
            }
            // 确保 end 不超过 images.size
            val validEnd = end.coerceAtMost(images.size)
            if (index in start until validEnd) {
                return groupIds.getOrNull(i)
            }
        }
        return null
    }
    
    /**
     * 检查索引是否是组的最后一张
     */
    fun isLastInGroup(index: Int): Boolean {
        for (i in groupBoundaries.indices) {
            val end = if (i + 1 < groupBoundaries.size) groupBoundaries[i + 1] else images.size
            if (index == end - 1) {
                return true
            }
        }
        return false
    }
}

/**
 * 图集清理信息
 * 
 * 用于 UI 显示图集清理状态
 */
data class AlbumCleanupInfo(
    val album: Album,
    val state: AlbumCleanupEngine.State,
    val analysisProgress: Float,  // 0-1
    val totalGroups: Int,
    val processedGroups: Int,
    val isCompleted: Boolean
) {
    val remainingGroups: Int get() = (totalGroups - processedGroups).coerceAtLeast(0)
}

/**
 * 图集清理引擎
 * 
 * 负责图集的相似组分析、批次获取和进度管理。
 * 
 * 核心功能：
 * 1. 分析图集内图片的相似组
 * 2. 小组合并逻辑（≤10张的组接续显示）
 * 3. 进度追踪（分析进度、清理进度）
 * 4. 冷却机制（复用相似推荐的冷却逻辑）
 */
class AlbumCleanupEngine(
    private val context: Context
) {
    /**
     * 清理引擎状态
     */
    enum class State {
        IDLE,       // 空闲（未选择图集）
        ANALYZING,  // 正在分析图集
        READY,      // 分析完成，准备清理
        CLEANING,   // 清理中
        COMPLETED   // 已完成
    }
    
    companion object {
        private const val TAG = "AlbumCleanupEngine"
        
        /** 小组合并阈值：单组≤此值时，会与下一组合并显示 */
        const val SMALL_GROUP_THRESHOLD = 10
        
        /** 单批次最大图片数（避免内存过大） */
        const val MAX_BATCH_SIZE = 30
        
        @Volatile
        private var instance: AlbumCleanupEngine? = null
        
        fun getInstance(context: Context): AlbumCleanupEngine {
            return instance ?: synchronized(this) {
                instance ?: AlbumCleanupEngine(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }
    
    private val preferences = AppPreferences.getInstance(context)
    private val hashCache = ImageHashCache.getInstance(context)
    private val similarGroupDetector = SimilarGroupDetector(context, hashCache)
    
    // 当前状态
    private var currentState: State = State.IDLE
    private var currentAlbumId: String? = null
    
    // 缓存的相似组（使用 Mutex 保护，避免并发问题）
    private var cachedGroups: List<SimilarGroup>? = null
    private val cacheMutex = Mutex()
    
    /**
     * 获取当前状态
     */
    fun getState(): State = currentState
    
    /**
     * 获取当前清理的图集ID
     */
    fun getCurrentAlbumId(): String? = currentAlbumId
    
    /**
     * 分析图集
     * 
     * 对图集内的图片进行相似组检测，返回分析进度 Flow。
     * 
     * @param album 要分析的图集
     * @param images 图集内的图片列表
     * @return 分析进度 Flow（0-1）
     */
    fun analyzeAlbum(
        album: Album,
        images: List<ImageFile>
    ): Flow<Float> = flow {
        currentState = State.ANALYZING
        currentAlbumId = album.id
        preferences.currentCleanupAlbumId = album.id
        
        emit(0f)
        
        if (images.isEmpty()) {
            // 空图集，直接完成
            android.util.Log.d(TAG, "Album ${album.name} is empty, skipping analysis")
            currentState = State.COMPLETED
            preferences.saveAlbumAnalysisResult(album.id, 0, emptyList())
            emit(1f)
            return@flow
        }
        
        android.util.Log.d(TAG, "Analyzing album ${album.name} with ${images.size} images")
        
        // 阶段1：预计算哈希（占 70% 进度）
        emit(0.1f)
        similarGroupDetector.ensureHashesComputed(images)
        emit(0.7f)

        // 阶段2：检测相似组（占 25% 进度）
        val similarGroups = withContext(Dispatchers.Default) {
            similarGroupDetector.detectSimilarGroups(images)
        }
        emit(0.85f)

        // 阶段3：收集孤立图片（不在任何相似组中的图片）
        // 图集清理的核心理念：每张照片都要过一遍，相似的凑在一起显示。
        // C1/T03-HASHVOLUME：成员判定按完整媒体身份键——不同卷同数值 ID
        // 行不因纯 ID 匹配互相吞没。
        val imagesInSimilarGroups = similarGroups.flatMap { it.images }.map { it.toHashKey() }.toSet()
        val orphanImages = images.filter { it.toHashKey() !in imagesInSimilarGroups }
        
        // 为每张孤立图片创建单独的"组"
        val orphanGroups = orphanImages.map { image ->
            SimilarGroup(
                images = listOf(image),
                startTime = image.dateModified,
                endTime = image.dateModified
            )
        }
        
        // 合并相似组和孤立组
        // 排序策略：相似组优先（按大小降序），孤立组按时间排序放后面
        val allGroups = similarGroups + orphanGroups.sortedBy { it.startTime }
        emit(0.9f)
        
        // 保存分析结果
        val groupIds = allGroups.map { it.id }
        preferences.saveAlbumAnalysisResult(album.id, allGroups.size, groupIds)
        
        // 保存每组照片数量和总照片数
        val groupImageCounts = allGroups.associate { it.id to it.size }
        preferences.saveGroupImageCounts(album.id, groupImageCounts)
        val totalImages = allGroups.sumOf { it.size }
        preferences.saveAlbumTotalImages(album.id, totalImages)
        
        // 【性能优化】持久化每个组的成员列表，避免重新检测相似组。
        // C1/T03-GROUPID：成员以完整媒体身份键（卷|ID）落盘，恢复按身份解析。
        val groupImageIds = allGroups.associate { group ->
            group.id to group.images.map { it.mediaIdentityKey() }
        }
        preferences.saveGroupsImageIds(album.id, groupImageIds)
        
        cachedGroups = allGroups
        
        android.util.Log.d(TAG, "Analysis complete: ${similarGroups.size} similar groups + ${orphanGroups.size} orphan images = ${allGroups.size} total groups, $totalImages total images")
        
        currentState = if (allGroups.isEmpty()) State.COMPLETED else State.READY
        emit(1f)
    }
    
    /**
     * 获取图集的总组数
     */
    fun getTotalGroups(albumId: String): Int {
        return preferences.getAlbumTotalGroups(albumId)
    }
    
    /**
     * 获取图集的剩余组数
     */
    fun getRemainingGroups(albumId: String): Int {
        return preferences.getAlbumRemainingGroups(albumId)
    }
    
    /**
     * 获取图集的总照片数（涉及相似组的照片）
     */
    fun getTotalImages(albumId: String): Int {
        return preferences.getAlbumTotalImages(albumId)
    }
    
    /**
     * 获取图集的剩余照片数
     */
    fun getRemainingImages(albumId: String): Int {
        return preferences.getAlbumRemainingImages(albumId)
    }
    
    /**
     * 获取图集的清理进度（已处理组数/总组数）
     */
    fun getCleanupProgress(albumId: String): Float {
        val total = preferences.getAlbumTotalGroups(albumId)
        if (total <= 0) return 0f
        val processed = preferences.getAlbumProcessedGroups(albumId)
        return processed.toFloat() / total
    }
    
    /**
     * 检查图集是否已完成清理
     */
    fun isAlbumCompleted(albumId: String): Boolean {
        return preferences.isAlbumCleanupCompleted(albumId)
    }
    
    /**
     * 获取下一个清理批次
     * 
     * 实现小组合并逻辑：当一组≤10张时，会把下一组的卡片接在后面。
     * 
     * 【性能优化】优先从持久化数据恢复相似组，避免重新检测（O(n²) 操作）
     * 
     * @param albumId 图集ID
     * @param allImages 图集内的所有图片
     * @param excludeGroupIds 需要排除的组ID（用于预加载时排除当前正在处理的组）
     * @return 清理批次，如果没有更多组则返回 null
     */
    suspend fun getNextBatch(
        albumId: String,
        allImages: List<ImageFile>,
        excludeGroupIds: List<String> = emptyList()
    ): AlbumCleanupBatch? = withContext(Dispatchers.IO) {
        // 切换图集时：更新 currentAlbumId 并清空过期的缓存
        if (currentAlbumId != albumId) {
            android.util.Log.d(TAG, "Album switched: $currentAlbumId -> $albumId, invalidating cached groups")
            cachedGroups = null
            currentAlbumId = albumId
            preferences.currentCleanupAlbumId = albumId
        }
        
        // 获取或恢复相似组（使用 Mutex 保护，避免并发问题）
        val groups = cacheMutex.withLock {
            cachedGroups ?: run {
                // 【性能优化】优先从持久化数据恢复，避免重新检测
                val restored = restoreGroupsFromPrefs(albumId, allImages)
                if (restored != null) {
                    android.util.Log.d(TAG, "Restored ${restored.size} groups from preferences for album $albumId")
                    cachedGroups = restored
                    restored
                } else {
                    // 没有持久化数据，需要重新检测
                    android.util.Log.d(TAG, "No persisted data, detecting similar groups for album $albumId")
                    val detected = similarGroupDetector.detectSimilarGroups(allImages)
                    cachedGroups = detected
                    detected
                }
            }
        }
        
        if (groups.isEmpty()) {
            currentState = State.COMPLETED
            return@withContext null
        }
        
        // 获取已永久处理的组ID（图集清理模式使用永久标记）
        // C1/T03-GROUPID：先迁移旧口径永久标记（唯一映射→新身份 ID，
        // 歧义/失配原样保留），再读取标记集合。
        migrateLegacyProcessedGroupIds(albumId, groups)
        val permanentlyProcessedIds = preferences.getAlbumPermanentlyProcessedGroups(albumId)

        // 过滤出未处理的组（同时排除指定的组ID）
        val allExcludeIds = permanentlyProcessedIds + excludeGroupIds.toSet()
        val availableGroups = groups.filter { it.id !in allExcludeIds }
        
        if (availableGroups.isEmpty()) {
            currentState = State.COMPLETED
            preferences.markAlbumCleanupCompleted(albumId)
            return@withContext null
        }
        
        currentState = State.CLEANING
        
        // 构建批次（小组合并逻辑）
        val batchImages = mutableListOf<ImageFile>()
        val batchGroupIds = mutableListOf<String>()
        val batchBoundaries = mutableListOf<Int>()
        
        var currentSize = 0
        for (group in availableGroups) {
            // 检查是否超过最大批次大小
            if (currentSize > 0 && currentSize + group.size > MAX_BATCH_SIZE) {
                break
            }
            
            // 记录边界
            batchBoundaries.add(currentSize)
            batchGroupIds.add(group.id)
            batchImages.addAll(group.images)
            currentSize += group.size
            
            // 如果当前组超过阈值，不再合并
            if (group.size > SMALL_GROUP_THRESHOLD) {
                break
            }
            
            // 如果总大小已经超过阈值，不再合并
            if (currentSize > SMALL_GROUP_THRESHOLD) {
                break
            }
        }
        
        android.util.Log.d(TAG, "Created batch: ${batchImages.size} images, ${batchGroupIds.size} groups")
        
        AlbumCleanupBatch(
            images = batchImages,
            groupIds = batchGroupIds,
            groupBoundaries = batchBoundaries
        )
    }
    
    /**
     * 标记组已处理（永久标记，不会过期）
     * 
     * @param groupId 组ID
     */
    fun markGroupProcessed(groupId: String) {
        // 图集清理模式使用永久标记
        currentAlbumId?.let { albumId ->
            preferences.markAlbumGroupPermanentlyProcessed(albumId, groupId)
            android.util.Log.d(TAG, "Permanently marked group $groupId as processed for album $albumId")
            
            // 检查是否所有组都已处理
            if (preferences.getAlbumRemainingGroups(albumId) == 0) {
                currentState = State.COMPLETED
                preferences.markAlbumCleanupCompleted(albumId)
                android.util.Log.d(TAG, "Album $albumId cleanup completed")
            }
        }
    }
    
    /**
     * 批量标记组已处理（永久标记，不会过期）
     * 
     * @param groupIds 组ID列表
     */
    fun markGroupsProcessed(groupIds: List<String>) {
        // 使用批量永久标记提高效率
        currentAlbumId?.let { albumId ->
            preferences.markAlbumGroupsPermanentlyProcessed(albumId, groupIds)
            android.util.Log.d(TAG, "Permanently marked ${groupIds.size} groups as processed for album $albumId")
            
            // 检查是否所有组都已处理
            if (preferences.getAlbumRemainingGroups(albumId) == 0) {
                currentState = State.COMPLETED
                preferences.markAlbumCleanupCompleted(albumId)
                android.util.Log.d(TAG, "Album $albumId cleanup completed")
            }
        }
    }
    
    /**
     * 退出图集清理模式
     * 
     * 注意：不再清空 cachedGroups，因为有持久化数据可以恢复
     */
    fun exitCleanupMode() {
        currentState = State.IDLE
        currentAlbumId = null
        // 【性能优化】不清空 cachedGroups，下次可以从持久化数据恢复
        // cachedGroups = null
        preferences.currentCleanupAlbumId = null
    }
    
    /**
     * 从持久化数据恢复相似组
     * 
     * 【性能优化】避免重新执行 O(n²) 的相似组检测算法
     * 
     * @param albumId 图集ID
     * @param allImages 图集内的所有图片
     * @return 恢复的相似组列表，如果无法恢复返回 null
     */
    /**
     * C1/T03-GROUPID：旧口径组 ID（"startTime_首张数字 ID"）到当前身份组的
     * 唯一映射迁移。旧 ID 在当前组集合中能唯一映射（组的最早成员数字 ID 与
     * startTime 均匹配）时，**全部**以其为主键的持久化记录一致改写为新身份
     * ID——永久处理标记、组目录、组成员、组数量与 checkpoint 引用；命中多个
     * 候选组（跨卷同数值 ID）或无候选（成员已变化）时**原样保留**歧义记录，
     * 不猜测、不清空用户标记（待解析边界：用户重新处理该组时会写入新格式
     * 标记）。幂等：迁移后旧格式条目被移除，重复调用无进一步变化。
     */
    private fun migrateLegacyProcessedGroupIds(albumId: String, groups: List<SimilarGroup>) {
        // C1/T03-GROUPID 复验轮二：迁移必须覆盖**全部**以组 ID 为主键的持久化
        // 记录（processed_ / groups_ / group_images_ / group_size_ / checkpoint
        // 引用）——只改 processed_ 会使组目录与标记口径分裂：已处理数 1→0、
        // 剩余数失真、完成状态错误。唯一可解析的旧 ID 全部一致改写；歧义/
        // 失配条目原样保留（不猜选、不清空用户标记）。幂等：改写后旧键消失。
        fun uniqueTarget(legacyId: String): SimilarGroup? {
            val (start, firstId) = parseLegacyGroupId(legacyId) ?: return null
            val candidates = groups.filter { group ->
                group.startTime == start && group.images.firstOrNull()?.id == firstId
            }
            return candidates.singleOrNull()
        }

        var changed = false

        // 1) 永久处理标记
        val processed = preferences.getAlbumPermanentlyProcessedGroups(albumId)
        val legacyProcessed = processed.filter { isLegacyGroupId(it) }
        if (legacyProcessed.isNotEmpty()) {
            val migrated = processed.toMutableSet()
            for (legacyId in legacyProcessed) {
                val target = uniqueTarget(legacyId)
                if (target != null) {
                    migrated.remove(legacyId)
                    migrated.add(target.id)
                    changed = true
                    android.util.Log.d(TAG, "Migrated legacy processed marker $legacyId -> ${target.id}")
                }
                // 多候选（跨卷歧义）或无候选（成员已变化）：保留原样。
            }
            if (changed) {
                preferences.replaceAlbumPermanentlyProcessedGroups(albumId, migrated)
            }
        }

        // 2) 组目录 / 成员 / 数量 / checkpoint 引用
        val groupIds = preferences.getAlbumGroupIds(albumId)
        val legacyInGroups = groupIds.filter { isLegacyGroupId(it) }
        val mappings = legacyInGroups.mapNotNull { legacyId ->
            uniqueTarget(legacyId)?.let { legacyId to it }
        }
        if (mappings.isNotEmpty()) {
            // 复审顺序要求：**先改名成员/数量、最后改写组目录**——groups_ 中的
            // 旧 ID 条目保留到最后才消失，充当进程死后自愈的迁移触发器
            // （若先改组目录，崩溃窗口内成员/数量将永久滞留旧键，只能靠
            // 重新分析自愈）。rename 对缺失源键为无操作，重复执行安全。
            mappings.forEach { (legacyId, target) ->
                preferences.renameGroupMembersKey(albumId, legacyId, target.id)
                preferences.renameGroupSizeKey(albumId, legacyId, target.id)
                // 成员值同步改写为完整身份键（与组目录/标记一致身份）：
                // 旧裸数字 ID 按目标组成员对应；已不存在（无法对应）的成员
                // 保留原字符串，由恢复端按既有缺失语义丢弃。
                val members = preferences.getGroupImageIds(albumId, target.id)
                if (!members.isNullOrEmpty()) {
                    val rewritten = members.map { member ->
                        val memberId = member.toLongOrNull()
                        if (memberId != null) {
                            target.images.find { it.id == memberId }?.mediaIdentityKey() ?: member
                        } else {
                            member
                        }
                    }
                    preferences.saveGroupsImageIds(albumId, mapOf(target.id to rewritten))
                }
                android.util.Log.d(TAG, "Migrated legacy group keys $legacyId -> ${target.id}")
            }
            // checkpoint 中的引用同步改写（保序保 index；重复条目按原样逐条映射）。
            val checkpoint = preferences.getAlbumCleanupCheckpoint(albumId)
            if (checkpoint != null) {
                val (order, index) = checkpoint
                if (order.any { it in mappings.map { m -> m.first } }) {
                    val mapping = mappings.toMap()
                    val newOrder = order.map { mapping[it]?.id ?: it }
                    preferences.saveAlbumCleanupCheckpoint(albumId, newOrder, index)
                }
            }
            // 组目录最后改写（迁移完成标志；崩溃后旧条目仍在→下次入口续跑自愈）。
            val newGroupIds = groupIds.toMutableSet()
            mappings.forEach { (legacyId, target) ->
                newGroupIds.remove(legacyId)
                newGroupIds.add(target.id)
            }
            preferences.replaceAlbumGroupIds(albumId, newGroupIds)
            changed = true
        }
        if (changed) {
            android.util.Log.d(TAG, "Legacy group identity migration complete for $albumId")
        }
    }

    private fun restoreGroupsFromPrefs(albumId: String, allImages: List<ImageFile>): List<SimilarGroup>? {
        // 检查是否有持久化数据
        if (!preferences.hasPersistedGroupData(albumId)) {
            return null
        }

        val groupIds = preferences.getAlbumGroupIds(albumId)
        if (groupIds.isEmpty()) {
            return null
        }

        // C1/T03-GROUPID：恢复按完整媒体身份解析——新格式成员键精确匹配；
        // 旧格式裸数字 ID 在当前库中唯一时按该行解析（读时迁移），命中多行
        // （跨卷同数值 ID）时不得猜选另一卷，返回 null 触发重新检测（重算边界）。
        val imageMap = allImages.associateBy { it.mediaIdentityKey() }
        val imagesById = allImages.groupBy { it.id }

        val restoredGroups = mutableListOf<SimilarGroup>()

        for (groupId in groupIds) {
            val memberKeys = preferences.getGroupImageIds(albumId, groupId)
            if (memberKeys.isNullOrEmpty()) {
                // 如果有任何组的数据丢失，返回 null 触发重新检测
                android.util.Log.w(TAG, "Missing image IDs for group $groupId, will re-detect")
                return null
            }

            // 恢复组内成员（身份解析 + 过滤已不存在的照片）
            val groupImages = mutableListOf<ImageFile>()
            for (key in memberKeys) {
                val exact = imageMap[key]
                if (exact != null) {
                    groupImages.add(exact)
                    continue
                }
                if (key.toLongOrNull() == null) {
                    // 新格式身份键但行已不存在：成员缺失，丢弃该成员（既有语义）。
                    continue
                }
                // 旧格式裸 ID：唯一才解析；跨卷歧义不得猜测。
                val candidates = imagesById[key.toLongOrNull()]
                when {
                    candidates == null -> {
                        // 照片已被用户删除：成员缺失，丢弃（既有语义）。
                        android.util.Log.d(TAG, "Legacy member $key no longer exists; dropped")
                    }
                    candidates.size == 1 -> groupImages.add(candidates.single())
                    else -> {
                        android.util.Log.w(
                            TAG,
                            "Ambiguous legacy member $key matches ${candidates.size} rows across volumes; re-detect"
                        )
                        return null
                    }
                }
            }

            if (groupImages.isEmpty()) {
                // 组内图片全部不存在（可能被用户删除），跳过该组
                continue
            }

            // 重建 SimilarGroup
            val sortedImages = groupImages.sortedBy { it.dateModified }
            restoredGroups.add(
                SimilarGroup(
                    images = sortedImages,
                    startTime = sortedImages.first().dateModified,
                    endTime = sortedImages.last().dateModified
                )
            )
        }

        if (restoredGroups.isEmpty()) {
            return null
        }

        // 按组大小降序排列（与原始检测结果一致）
        return restoredGroups.sortedByDescending { it.images.size }
    }
    
    // ==================== 断点续传 ====================
    
    /**
     * 保存清理断点
     * 
     * @param groupIds 当前批次的组ID列表
     * @param currentIndex 当前在批次中的索引位置
     */
    fun saveCheckpoint(groupIds: List<String>, currentIndex: Int) {
        currentAlbumId?.let { albumId ->
            preferences.saveAlbumCleanupCheckpoint(albumId, groupIds, currentIndex)
            android.util.Log.d(TAG, "Saved checkpoint for album $albumId: index=$currentIndex, groups=${groupIds.size}")
        }
    }
    
    /**
     * 获取清理断点
     * 
     * @param albumId 图集ID
     * @return 断点信息 (组ID列表, 当前索引)，如果没有断点返回 null
     */
    fun getCheckpoint(albumId: String): Pair<List<String>, Int>? {
        return preferences.getAlbumCleanupCheckpoint(albumId)
    }
    
    /**
     * 清除清理断点
     * 
     * @param albumId 图集ID
     */
    fun clearCheckpoint(albumId: String) {
        preferences.clearAlbumCleanupCheckpoint(albumId)
        android.util.Log.d(TAG, "Cleared checkpoint for album $albumId")
    }
    
    /**
     * 从断点恢复批次
     * 
     * 根据保存的组ID列表重建批次数据
     * 
     * @param albumId 图集ID
     * @param allImages 图集内的所有图片
     * @return 恢复的批次和起始索引，如果无法恢复返回 null
     */
    suspend fun getCheckpointBatch(
        albumId: String,
        allImages: List<ImageFile>
    ): Pair<AlbumCleanupBatch, Int>? = withContext(Dispatchers.IO) {
        val checkpoint = getCheckpoint(albumId) ?: return@withContext null
        val (savedGroupIds, savedIndex) = checkpoint
        
        // 获取或恢复相似组（使用 Mutex 保护，避免并发问题）
        // 【性能优化】优先从持久化数据恢复
        val groups = cacheMutex.withLock {
            cachedGroups ?: run {
                val restored = restoreGroupsFromPrefs(albumId, allImages)
                if (restored != null) {
                    android.util.Log.d(TAG, "Checkpoint restore: restored ${restored.size} groups from preferences")
                    cachedGroups = restored
                    restored
                } else {
                    val detected = similarGroupDetector.detectSimilarGroups(allImages)
                    cachedGroups = detected
                    detected
                }
            }
        }
        
        if (groups.isEmpty()) return@withContext null

        // C1/T03-GROUPID：与 getNextBatch 同口径——先迁移旧口径持久化身份
        // （唯一映射→新身份 ID，歧义原样保留），升级窗口内断点记录的旧 ID
        // 才不会漏掉已迁移的标记与组目录。
        migrateLegacyProcessedGroupIds(albumId, groups)
        // 获取已永久处理的组ID
        val permanentlyProcessedIds = preferences.getAlbumPermanentlyProcessedGroups(albumId)

        // 根据保存的组ID重建批次。
        // C1/T03-GROUPID 复验轮一：新格式组 ID 精确匹配；旧口径别名仅在
        // **唯一候选**时解析——两卷的不同组共用同一旧别名时，任何 last-wins
        // 猜选都会把旧索引套到错误组上跳过照片。此时明确放弃续接并安全重算
        // （仅清断点，不清除永久处理意图、不自动标记任一候选）。
        val aliasGroups = groups.groupBy { group -> "${group.startTime}_${group.images.firstOrNull()?.id ?: 0}" }
        val hasAmbiguousSavedId = savedGroupIds.any { savedId ->
            groups.none { it.id == savedId } && (aliasGroups[savedId]?.size ?: 0) >= 2
        }
        if (hasAmbiguousSavedId) {
            android.util.Log.w(TAG, "Ambiguous legacy checkpoint ids for $albumId; abandoning resume for safe re-detection")
            clearCheckpoint(albumId)
            return@withContext null
        }
        val groupMap = groups.associateBy { it.id } +
            aliasGroups.filterValues { it.size == 1 }.mapValues { it.value.single() }
        val batchImages = mutableListOf<ImageFile>()
        val batchGroupIds = mutableListOf<String>()
        val batchBoundaries = mutableListOf<Int>()

        for (groupId in savedGroupIds) {
            val group = groupMap[groupId]
            val effectiveId = group?.id ?: groupId
            if (group != null && effectiveId !in permanentlyProcessedIds) {
                batchBoundaries.add(batchImages.size)
                batchGroupIds.add(effectiveId)
                batchImages.addAll(group.images)
            }
        }

        // 检查保存的组是否仍有未处理项（如果全部已处理，说明断点无效）
        if (batchImages.isEmpty()) {
            clearCheckpoint(albumId)
            return@withContext null
        }

        // C1/T03-GROUPID 复验轮三（断点位置有效性）：批次组成（组解析结果/
        // 成员集合/处理标记过滤）与保存时不一致——任一保存组未能原样进入
        // 批次（如成员删除使组 ID 变化被丢弃）——旧位置索引指向的图像已
        // 不可信：安全放弃断点，从正常批次入口重新开始（孤立照片经孤儿组
        // 照常进入批次，不遗漏）。仅清断点，不清除永久处理意图、不自动
        // 标记未浏览组。
        if (batchGroupIds.size != savedGroupIds.size) {
            android.util.Log.w(
                TAG,
                "Checkpoint batch composition changed for $albumId " +
                    "(${savedGroupIds.size} -> ${batchGroupIds.size} groups); abandoning stale index"
            )
            clearCheckpoint(albumId)
            return@withContext null
        }
        // 成员集不变但断点保存后分析重跑过（重进入口必先 analyzeAlbum，
        // analyzed_ 晚于断点保存时刻）时，组内按 DATE_MODIFIED 的排序可能
        // 已移动——位置同样不可确认：放弃并安全重开。正常续接（保存后未
        // 重分析）不受影响。
        val checkpointSavedAt = preferences.getAlbumCleanupCheckpointSavedAt(albumId)
        val analyzedAt = preferences.getAlbumAnalyzedAt(albumId)
        if (savedIndex > 0 && checkpointSavedAt > 0L && analyzedAt > checkpointSavedAt) {
            android.util.Log.w(
                TAG,
                "Album re-analyzed after checkpoint save for $albumId; abandoning stale index"
            )
            clearCheckpoint(albumId)
            return@withContext null
        }

        // 确保索引在有效范围内
        val validIndex = savedIndex.coerceIn(0, batchImages.size - 1)
        
        currentState = State.CLEANING
        currentAlbumId = albumId
        
        android.util.Log.d(TAG, "Restored checkpoint batch: ${batchImages.size} images, starting at index $validIndex")
        
        val batch = AlbumCleanupBatch(
            images = batchImages,
            groupIds = batchGroupIds,
            groupBoundaries = batchBoundaries
        )
        
        Pair(batch, validIndex)
    }
    
    /**
     * 重置图集清理状态
     * 
     * @param albumId 图集ID
     */
    fun resetAlbumState(albumId: String) {
        preferences.resetAlbumCleanupState(albumId)
        if (currentAlbumId == albumId) {
            cachedGroups = null
            currentState = State.IDLE
        }
    }
    
    /**
     * 获取图集清理信息（用于 UI 显示）
     * 
     * @param album 图集
     * @return 图集清理信息
     */
    fun getAlbumCleanupInfo(album: Album): AlbumCleanupInfo {
        val totalGroups = preferences.getAlbumTotalGroups(album.id)
        val processedGroups = preferences.getAlbumProcessedGroups(album.id)
        val isCompleted = preferences.isAlbumCleanupCompleted(album.id)
        
        val state = when {
            isCompleted -> State.COMPLETED
            currentAlbumId == album.id -> currentState
            totalGroups < 0 -> State.IDLE  // 未分析
            totalGroups == 0 -> State.COMPLETED  // 无相似组
            else -> State.READY
        }
        
        val progress = if (totalGroups > 0) {
            processedGroups.toFloat() / totalGroups
        } else {
            0f
        }
        
        return AlbumCleanupInfo(
            album = album,
            state = state,
            analysisProgress = if (state == State.ANALYZING) 0f else 1f,
            totalGroups = totalGroups.coerceAtLeast(0),
            processedGroups = processedGroups,
            isCompleted = isCompleted
        )
    }
}
