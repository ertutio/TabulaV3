# T03r1 媒体身份契约实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 冻结媒体身份契约：MediaItemKey(volumeName, id) 为共享模型类型，提供 URI 解析；SystemAlbumSyncManager（首个消费者簇）迁移到该契约；与 T01 账本（URI 字符串键）兼容。

**Architecture:** 新建 `data/model/MediaIdentity.kt`：`data class MediaItemKey(volumeName: String, id: Long)` + 纯函数 `mediaItemKeyFromUri(uri)`（严格解析 `content://media/<volume>/<collection>/media/<id>`；聚合卷名 "external" 与无法解析的 URI 返回 null——别名 URI 仍由调用方经 canonicalize/查询解析）。SystemAlbumSyncManager 删除私有 MediaStoreItemKey，全部改用 MediaItemKey（机械替换，行为不变）。

**Tech Stack:** Kotlin / JUnit4（纯 JVM + Robolectric for Uri）。

**Spec:** PR #9 T03 第一轮"先定义身份契约"（媒体持有卷/具体 URI/内容版本；先冻结契约，接入时兼容 T01 账本）。内容版本契约：源内容版本 = MediaStore DATE_MODIFIED（毫秒），由调用方持有；本契约只负责身份。

## Global Constraints

- 本轮不迁移 bucket/Album 身份（下一轮一簇）、不改动 T01 账本存储格式（账本键保持 URI 字符串，MediaItemKey 从其解析）。
- 行为零变化：同步管理器现有 29 项复制安全测试必须全绿。

## Review Focus

1. **聚合卷名 "external" 不是身份**——解析拒绝——测试钉死。
2. **别名 URI（带 query/不同 collection）解析不到具体卷**必须返回 null 而不是猜——测试钉死。

---

### Task 1: MediaItemKey 契约 + 同步管理器迁移

**Files:**
- Create: `app/src/main/java/com/tabula/v3/data/model/MediaIdentity.kt`
- Modify: `app/src/main/java/com/tabula/v3/data/repository/SystemAlbumSyncManager.kt`（私有 MediaStoreItemKey → MediaItemKey）
- Test: `app/src/test/java/com/tabula/v3/data/model/MediaItemKeyTest.kt`（新建）

- [ ] Step 1: 写失败测试：解析标准 images/media URI 成功；"external" 聚合卷拒绝；files collection 的具体卷 URI 可解析；非 media authority / 缺段返回 null；同卷同 id 相等、跨 id 不等。
- [ ] Step 2: RED。Step 3: 实现契约。Step 4: GREEN。
- [ ] Step 5: SystemAlbumSyncManager 迁移（删私有 data class，类型机械替换）。
- [ ] Step 6: 全量单测 + assembleDebug（预期 157 项：152+5，唯一失败仍为基线既有）。
- [ ] Step 7: Commit `feat: freeze media item identity contract and migrate sync manager to it`

## Self-Review

- 覆盖：契约类型 + 解析 + 首簇迁移；账本兼容以注释与账本键不变保证。
- Review Focus 两项均有测试。
