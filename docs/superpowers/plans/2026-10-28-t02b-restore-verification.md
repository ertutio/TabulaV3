# T02b 恢复核验（回收站一致性第二边界）实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 普通非 pending 条目恢复前也核验当前媒体状态：UNKNOWN 保留在回收站，MISSING 清除死记录且不加入图库，仅 PRESENT 继续恢复。

**Architecture:** 复用 FileOperationManager.verifyDeletionState（现有 PRESENT/MISSING/UNKNOWN 契约，含权限/卷感知）；把三态决策抽为顶层纯函数 `decideRestoreAction(uri, verification): RestoreDecision`（MainActivity.kt 顶层，纯 JVM 可测），MainActivity.onRestore 在 pending 对账之后、removeFromRecycleBin 之前接线。

**Tech Stack:** Kotlin / Robolectric（仅 Uri 需要）。

**Spec:** PR #9 T02 第二边界"恢复核验"（普通非 pending 条目恢复前也核验 PRESENT/MISSING/UNKNOWN；未知保留，缺失不加入图库）。

## Global Constraints

- 不动序列化与批量恢复事务（T02c 边界）。
- pending 对账（reconcileRecycleBinDeletion）的既有语义不变，仍先行执行。
- 既有 146 项测试中仅基线既有 RecycleBinManagerTest.atomicReadRestoresLegacyBackup 失败。

## Review Focus

1. **未知必须保留记录**：UNKNOWN 时不得 removeFromRecycleBin、不得加入图库——测试钉死。
2. **缺失清除死记录**：MISSING 时清回收站记录但绝不动 allImages/imagesForSorting——测试钉死。

---

### Task 1: RestoreDecision 决策函数 + onRestore 接线

**Files:**
- Modify: `app/src/main/java/com/tabula/v3/MainActivity.kt`（顶层 enum+fun；onRestore 接线）
- Test: `app/src/test/java/com/tabula/v3/RestoreDecisionTest.kt`（新建）

**Interfaces:**
- Produces: `enum class RestoreDecision { PROCEED_RESTORE, KEEP_UNKNOWN, DROP_MISSING }`；`fun decideRestoreAction(uri: Uri, verification: FileOperationManager.DeletionVerification): RestoreDecision`（unknown→KEEP_UNKNOWN，missing→DROP_MISSING，其余→PROCEED_RESTORE）。

- [ ] **Step 1: 写失败测试**（3 项：unknown 保留、missing 清记录、present 继续）。
- [ ] **Step 2: 确认 RED。Step 3: 实现。Step 4: GREEN。**
- [ ] **Step 5: onRestore 接线**：verifyDeletionState(listOf(image.uri)) → decideRestoreAction；KEEP_UNKNOWN → Toast 保留并 return；DROP_MISSING → removeFromRecycleBin + 更新 deletedImages + Toast，不动图库，return；PROCEED_RESTORE → 原有恢复流程不变。
- [ ] **Step 6: 全量单测 + assembleDebug**（预期 149 项，唯一失败仍为基线既有）。
- [ ] **Step 7: Commit** `fix: verify ordinary recycle-bin entries before restore`

## Self-Review

- 覆盖：两条决策边界均有测试；接线为编译级验证并如实记录（UI 回调无单测）。
- Review Focus 两项均有对应测试；类型一致（DeletionVerification 三集合语义与现有 verifyDeletionState 相同）。
