# T05 图集详情直连与时间单位统一实施计划

> **历史设计参考：** 本文保留当时的拆分方案；实现/续接以[持续开发总计划](../../../.ua/DEVELOPMENT_ROADMAP.md)、[当前进度](../../../.ua/DEVELOPMENT_PROGRESS.md)和[验收约定](../../acceptance.md)为准。先核对已交付子项，旧复选框与历史失败记录不代表当前状态；完成本文片段后返回总任务执行循环。

**Goal:** 图集详情直接消费仓库返回的完整 ImageFile（消除映射压平导致的逐 URI N+1 补读）；删除唯一直播秒级时间戳的 queryImageFromUri（DATE_MODIFIED 全链路毫秒）；加载错误有明确状态与重试。

**Architecture:** AlbumManager.getImageMappingsForAlbum → getAlbumDetailImages(albumId): List<ImageFile>（getImagesByBucket 直连，T03 口径：按显示名匹配的合并图集，与 Album 层合并一致）；AlbumViewScreen 删除 mappings/imageMap/missingUris/分批补读机制与 queryImageFromUri，失败置 loadError 并保留旧列表 + 重试；取消检查保留。

**Tech Stack:** Kotlin / Robolectric（EvidenceTestProvider）。

**Spec:** PR #9 T05（TC23–TC26 中本边界断言：完整模型直连、时间单位统一、错误状态、取消不覆盖）。

## Global Constraints

- 既有 161 项测试中仅基线既有 RecycleBinManagerTest.atomicReadRestoresLegacyBackup 失败。
- 不动 Album 身份（T03r3 留待独立轮）。

## Review Focus

1. **DATE_MODIFIED 毫秒契约**：仓库查询路径返回毫秒——测试钉死（秒→毫秒转换断言）。
2. **加载失败不清空旧内容**：catch 后 albumImages 保持原值——接线编译级 + 行为在 catch 中明确。

---

### Task 1: getAlbumDetailImages + AlbumViewScreen 直连

**Files:**
- Modify: `app/src/main/java/com/tabula/v3/data/repository/AlbumManager.kt`
- Modify: `app/src/main/java/com/tabula/v3/ui/screens/AlbumViewScreen.kt`
- Modify: `app/src/main/java/com/tabula/v3/MainActivity.kt`（wiring）
- Test: `app/src/test/java/com/tabula/v3/data/repository/AlbumDetailImagesTest.kt`（新建）

- [ ] Step 1: 写失败测试：getAlbumDetailImages 返回完整字段（dateModified 毫秒断言：秒值 1700000000 → 1700000000000；orientation/volume/relativePath 透传）。
- [ ] Step 2: RED。Step 3: 实现 AlbumManager 新方法。Step 4: GREEN。
- [ ] Step 5: AlbumViewScreen 直连 + 删 queryImageFromUri + loadError/retry；MainActivity wiring 更新。
- [ ] Step 6: 全量单测 + assembleDebug。
- [ ] Step 7: Commit `fix: feed album detail full repository images and unify date units`
