# T03r2 bucket 身份分组实施计划

> **历史设计参考：** 本文保留当时的拆分方案；实现/续接以[持续开发总计划](../../../.ua/DEVELOPMENT_ROADMAP.md)、[当前进度](../../../.ua/DEVELOPMENT_PROGRESS.md)和[验收约定](../../acceptance.md)为准。先核对已交付子项，旧复选框与历史失败记录不代表当前状态；完成本文片段后返回总任务执行循环。

**Goal:** bucket 查询簇按 (volumeName, relativePath) 真实身份分组，同名不同目录/卷不再在 bucket 层互相污染统计；Album 层显式按显示名合并（该消费者簇后续轮迁移），用户可见行为零变化。

**Architecture:** SystemBucket 增加 `volumeName: String?`；loadAllData 与 getAllBucketsWithInfo 查询 VOLUME_NAME，bucketMap 改用 (卷, 路径) 复合键；新增纯函数 `mergeBucketsByName(buckets)`（计数求和、封面取日期序首个非空、路径/卷取首个非空、按总计数降序），AlbumManager.refreshAlbumsFromSystem 统一经它消费 bucket。

**Tech Stack:** Kotlin / Robolectric（复用 EvidenceTestProvider）。

**Spec:** PR #9 T03 第二轮"每轮迁移一个消费者簇"（bucket 查询簇；Album/元数据/使用频率簇显式留待后续轮）。

## Global Constraints

- Album.id 仍为显示名；隐藏/屏蔽/使用频率等 name-keyed 消费者行为不变。
- 既有 157 项测试中仅基线既有 RecycleBinManagerTest.atomicReadRestoresLegacyBackup 失败。

## Review Focus

1. **同名不同路径在 bucket 层必须分开**（count/cover/path/volume 各自正确）——测试钉死。
2. **mergeBucketsByName 空输入/单输入**不变形——测试钉死。
3. **projectSnapshot 对带 volumeName 的 bucket 的 copy 保留卷名**——既有测试补断言。

---

### Task 1: bucket 复合键 + 显式合并

**Files:**
- Modify: `app/src/main/java/com/tabula/v3/data/repository/LocalImageRepository.kt`（SystemBucket.volumeName；两处查询复合键）
- Modify: `app/src/main/java/com/tabula/v3/data/repository/AlbumManager.kt`（refreshAlbumsFromSystem 经 mergeBucketsByName）
- Test: `app/src/test/java/com/tabula/v3/data/repository/BucketIdentityTest.kt`（新建；含 mergeBucketsByName 纯函数测试与 getAllBucketsWithInfo 分组测试）

- [ ] Step 1: 写失败测试（同名不同路径分开、merge 求和/封面/路径、空集合、projectSnapshot 保留卷名）。
- [ ] Step 2: RED。Step 3: 实现。Step 4: GREEN。
- [ ] Step 5: 全量单测 + assembleDebug。
- [ ] Step 6: Commit `feat: key media buckets by volume and path, merge by name explicitly for albums`
