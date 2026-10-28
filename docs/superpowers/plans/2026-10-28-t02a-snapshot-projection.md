# T02a 快照投影（回收站一致性第一边界）实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 浏览/推荐一律由"原始媒体快照 − 软删除集合"派生：加入回收站、重载、重建后视图一致，回收站照片不再在重载后重新出现在卡片流与相册计数里。

**Architecture:** 新增纯函数 `projectSnapshot(LoadResult, softDeletedIds): LoadResult`（放 LocalImageRepository.kt，无 Android 依赖、纯 JVM 可测）：过滤 allImages/imagesExcludingTabula，并按快照日期序重算各相册 count/cover（保留 bucket 名称与 relativePath）。MainActivity 两条加载路径（初始/对账加载、手动刷新）先读回收站再投影；恢复路径补推荐缓存失效（与移除路径对称）。恢复不手补相册计数（手补即漂移，等下一次加载/刷新重导出，与现有移除路径的计数时效对称）。

**Tech Stack:** Kotlin / JUnit4（纯 JVM，无需 Robolectric）。

**Spec:** PR #9 T02 第一边界"快照投影"（每次加载都由原始媒体快照减软删除集合派生浏览/推荐；TC08–TC12 中覆盖本边界的断言）。

## Global Constraints

- 不动回收站 JSON 序列化与恢复核验（T02b/T02c 边界）。
- 不引入依赖升级；保持 com.tabula.v3 包名与现有签名。
- 恢复核验（PRESENT/MISSING/UNKNOWN）不在本边界。
- 既有 141 项测试中仅基线既有 RecycleBinManagerTest.atomicReadRestoresLegacyBackup 失败，不得为凑绿改动它。

## Review Focus

1. **空软删除集合必须原样返回**（性能与行为零变化护栏）——Task 1 测试钉死。
2. **封面回退**：bucket 最新一张被软删除时，封面必须落到该 bucket 内日期序的下一张，不得指向已删除 id——测试钉死。
3. **bucket 全部被软删除**：保留 bucket 行（名称/路径），count=0、cover=null，不得凭空丢 bucket——测试钉死。

---

### Task 1: projectSnapshot 纯函数 + 两条加载路径接线 + 恢复缓存失效

**Files:**
- Modify: `app/src/main/java/com/tabula/v3/data/repository/LocalImageRepository.kt`（新增顶层纯函数）
- Modify: `app/src/main/java/com/tabula/v3/MainActivity.kt`（初始加载 ~L441、手动刷新 ~L757、恢复路径 ~L861）
- Test: `app/src/test/java/com/tabula/v3/data/repository/ProjectSnapshotTest.kt`（新建，纯 JVM）

**Interfaces:**
- Produces: `fun projectSnapshot(snapshot: LocalImageRepository.LoadResult, softDeletedIds: Set<Long>): LocalImageRepository.LoadResult`（空集合时原样返回；重算 systemBuckets：count/cover 来自快照顺序下未删除图片，relativePath 保留自快照 bucket，排序 imageCount 降序与 loadAllData 一致）。

- [ ] **Step 1: 写失败测试**（5 项）：softDeletedImagesAreExcludedFromAllProjections；coverFallsBackToNextNewestInBucket；emptySoftDeleteSetReturnsSnapshotUnchanged；fullyDeletedBucketKeepsNameWithZeroCount；bucketOrderAndPathsPreservedAfterProjection。
- [ ] **Step 2: 跑测试确认失败**（函数未定义）。
- [ ] **Step 3: 实现 projectSnapshot**（纯函数，不放 companion）。
- [ ] **Step 4: 跑测试全绿**。
- [ ] **Step 5: 接线 MainActivity**：初始加载块改为"先 loadRecycleBin 再投影，再 albumManager.initialize(projected.systemBuckets)"；手动刷新块同样投影后再 refreshAlbumsFromSystem；恢复路径补 `recommendationEngine.invalidateSimilarGroupCache()`。
- [ ] **Step 6: 编译主源码 + 全量单测**（预期 146 项：141+5，唯一失败仍为基线既有）。
- [ ] **Step 7: Commit** `fix: derive browsable media and album counts from snapshot minus soft-deleted`

## Self-Review

- 覆盖：快照投影三要点（减集合、封面回退、bucket 保留）均有测试；两条加载路径与恢复缓存失效在接线步骤内（UI 组合函数无单测，编译级验证并如实记录）。
- 类型一致：projectSnapshot 进出均为 LoadResult，albumManager.initialize/refreshAlbumsFromSystem 现有签名直接兼容。
- Review Focus 三项均有对应测试。
- 比例：一个边界一个小任务，与规格相称。
