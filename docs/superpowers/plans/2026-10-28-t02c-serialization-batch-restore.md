# T02c 序列化往返与批量恢复事务（回收站一致性第三边界）实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** orientation 在新旧 JSON 间往返正确；批量恢复走单一记录事务，逐项核验（复用 T02b 决策），部分失败/未知项保留在回收站。

**Architecture:** saveUnlocked 补写 orientation、loadUnlocked 用 optInt 兼容旧格式；RecycleBinScreen 新增 onRestoreBatch（单条恢复委托批量路径），MainActivity 实现 onRestoreBatch：单次 reconcile + 单次 verifyDeletionState + 逐项 decideRestoreAction + 单次 removeAllFromRecycleBin（PRESENT 与 MISSING 分组各一次事务）+ 一次状态更新与汇总 Toast。

**Tech Stack:** Kotlin / Robolectric。

**Spec:** PR #9 T02 第三边界（orientation 新旧 JSON 往返正确；批量恢复共享记录事务，部分失败/并发/重启保留未确认项）。

## Global Constraints

- 既有 149 项测试中仅基线既有 RecycleBinManagerTest.atomicReadRestoresLegacyBackup 失败。
- 不改回收站删除路径（beginDeletion/finishDeletion 既有事务不动）。

## Review Focus

1. **旧 JSON 无 orientation 键**必须解析为 0 且再保存后字段不丢——测试钉死。
2. **批量恢复未知项保留**：UNKNOWN 不进入任何 removeAllFromRecycleBin 调用——接线为编译级验证，决策逻辑复用已测 decideRestoreAction。

---

### Task 1: 序列化往返 + 批量恢复

**Files:**
- Modify: `app/src/main/java/com/tabula/v3/data/repository/RecycleBinManager.kt`（save/load orientation）
- Modify: `app/src/main/java/com/tabula/v3/ui/screens/RecycleBinScreen.kt`（onRestoreBatch 参数 + onRestoreSelected 接线）
- Modify: `app/src/main/java/com/tabula/v3/MainActivity.kt`（onRestoreBatch 处理器；onRestore 委托）
- Test: `app/src/test/java/com/tabula/v3/data/repository/RecycleBinSerializationTest.kt`（新建）

- [ ] Step 1: 写失败测试（3 项：orientation 往返、旧 JSON 加载再保存、批量删除单次事务写入）。
- [ ] Step 2: RED。Step 3: 实现序列化。Step 4: GREEN。
- [ ] Step 5: RecycleBinScreen/MainActivity 批量恢复接线（onRestore 委托 onRestoreBatch(listOf(image))，统一核验与事务路径）。
- [ ] Step 6: 全量单测 + assembleDebug。
- [ ] Step 7: Commit `fix: round-trip orientation in recycle-bin json and restore in one shared transaction`
