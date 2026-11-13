# T06a Bitmap 所有权（缓存借用位图不回收）实施计划

> **历史设计参考：** 本文保留当时的拆分方案；实现/续接以[持续开发总计划](../../../.ua/DEVELOPMENT_ROADMAP.md)、[当前进度](../../../.ua/DEVELOPMENT_PROGRESS.md)和[验收约定](../../acceptance.md)为准。先核对已交付子项，旧复选框与历史失败记录不代表当前状态；完成本文片段后返回总任务执行循环。

**Goal:** 两种 CardStack 模式的 Genie/删除预加载绝不持有并回收 Coil 缓存借用的共享位图：F15 同尺寸缩放别名（createScaledBitmap 目标尺寸等于源时返回源本身）发生时强制像素级拷贝。

**Architecture:** 新增 `ui/util/OwnedBitmaps.kt` 的 `ownedScaledCopyOrNull(source, maxSize)`：入口拒绝已回收源；缩放后若结果与源同实例（F15 路径）则 `source.copy(...)` 生成独立副本，拷贝失败返回 null（调用方回退 createGenieBitmapFast 全新解码）。CardStack 4 处"缓存命中→缩放"代码块统一换用该函数；其清理路径（DisposableEffect/换图/方向取消）继续回收自有副本——现在所有权恒成立。

**Tech Stack:** Kotlin / Robolectric（Bitmap）。

**Spec:** PR #9 T06 第一子项（两种 CardStack 均不回收 Coil 借用对象；同尺寸缩放、动画完成/替换/取消/退出分别验证所有权；F14/F15）。预加载请求身份与 Disposable 生命周期为 T06b 后续轮。

## Global Constraints

- 既有 171 项测试中仅基线既有 RecycleBinManagerTest.atomicReadRestoresLegacyBackup 失败。
- 不改缓存键格式与显示质量/手势（T06b 边界）。
- createGenieBitmapFast 为全新解码（所有权本成立），不动。

## Review Focus

1. **同尺寸缩放别名**：`createScaledBitmap` 返回源本身时必须拷贝——helper 分支钉死（Robolectric 阴影可能恒返回新实例，别名路径以代码审查+helper 行为测试覆盖，如实记录）。
2. **拷贝失败不得静默回退到借用对象**：返回 null → 调用方走全新解码——测试钉死。

---

### Task 1: ownedScaledCopyOrNull + 4 处调用点替换

**Files:**
- Create: `app/src/main/java/com/tabula/v3/ui/util/OwnedBitmaps.kt`
- Modify: `app/src/main/java/com/tabula/v3/ui/components/CardStack.kt`（4 处缓存命中缩放块）
- Test: `app/src/test/java/com/tabula/v3/ui/util/OwnedBitmapsTest.kt`（新建）

- [ ] Step 1: 写失败测试（缩放新实例+尺寸正确、同尺寸独立副本、已回收源返回 null）。
- [ ] Step 2: RED。Step 3: 实现。Step 4: GREEN。
- [ ] Step 5: CardStack 4 处替换 + import。
- [ ] Step 6: 全量单测 + assembleDebug。
- [ ] Step 7: Commit `fix: never hold or recycle coil-borrowed bitmaps in card preloads`
