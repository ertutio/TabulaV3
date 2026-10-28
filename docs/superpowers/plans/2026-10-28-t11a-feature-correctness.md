# T11a 特征检测正确性前移实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 忙碌（信号量满）、快速模式门槛、检测失败产生的"非 HDR/非动态"阴性一律视为不可信——不入缓存；缓存键含内容版本（DATE_MODIFIED 毫秒，T03 契约）与开关组合；取消语义保持（LaunchedEffect 取消不落缓存）。

**Architecture:** ImageFeatures 增 `definitive: Boolean = true`；detectHdr/detectMotionPhoto 的三类不可信路径经 onInconclusive 上报，detect() 聚合为 definitive=false；缓存抽取为纯 JVM 的 ImageFeatureCacheStore（键 = id+版本+开关，putIfReliable 只收可信结果或阳性），ImageFeatureState 的 remember/LaunchedEffect 键加入 dateModified。

**Tech Stack:** Kotlin / 纯 JVM JUnit（缓存策略）+ Robolectric（detector 不可信路径）。

**Spec:** PR #9 T11a（忙碌/失败/取消不缓存为可靠阴性，内容版本与开关失效正确；TC48/TC50 语义断言前移部分）。T11b（single-flight/有界缓存）不在本轮。

## Global Constraints

- 既有 162 项测试中仅基线既有 RecycleBinManagerTest.atomicReadRestoresLegacyBackup 失败。
- 不做 single-flight、有界缓存、EXIF/XMP 复用优化（T11b）。

## Review Focus

1. **不可信阴性绝不入缓存**（快速模式/信号量满/异常）——测试钉死。
2. **阳性与可信阴性入缓存；版本变更后旧条目失效**——测试钉死。

---

### Task 1: definitive 语义 + 版本化缓存

**Files:**
- Modify: `app/src/main/java/com/tabula/v3/data/model/ImageFeatures.kt`
- Modify: `app/src/main/java/com/tabula/v3/data/repository/ImageFeatureDetector.kt`
- Create: `app/src/main/java/com/tabula/v3/ui/util/ImageFeatureCacheStore.kt`
- Modify: `app/src/main/java/com/tabula/v3/ui/util/ImageFeatureState.kt`
- Test: `app/src/test/java/com/tabula/v3/ui/util/ImageFeatureCacheStoreTest.kt`、`app/src/test/java/com/tabula/v3/data/repository/ImageFeatureDefinitiveTest.kt`

- [ ] Step 1: 写失败测试（缓存 5 项 + detector 快速模式阴性 definitive=false）。
- [ ] Step 2: RED。Step 3: 实现。Step 4: GREEN。
- [ ] Step 5: 全量单测 + assembleDebug。
- [ ] Step 6: Commit `fix: never cache inconclusive feature negatives and version the feature cache`
