# TabulaV3 整合分支独立复审（integration/night-2026-11-08 @ e3b005f，基 43a8da1）

**总评：整合有效，无需修复。** 12 个合并提交齐全且逐一对应远端组件分支 tip（SHA 级验证）；全部组件内容字节级包含；唯一冲突（probe 脚本）按并集正确解决；无冲突标记残留、无组件外改动。仅 3 项 P3 备注（均不阻塞）。

## 逐项结论

1. **组件完整性 = 通过。** `git log --merges` 共 12 个合并提交，线性单链（3940ccf→…→e3b005f），每个合并的第二父 SHA 与对应远端分支 tip 逐字节相等；12 分支各文件对 tip 做 `git diff branch e3b005f -- <该组件文件>` 均为空（两处有意差异除外：probe=并集、ArchiveQueryCountTest 178 行旧版被 #59 终版 193 行取代，tip 与 #59 分支逐字节一致）。任务书"13 组件"实际=12 分支合并 + 第 13（#56/t11b-feature-cache-measure）按计划经 #60 载体交付，其唯一文件被 #60 终版取代，自身分支未并入（符合任务书"#60 含 #56 测试"）。

2. **无意外改动 = 通过。** diff 共 24 文件，全部一一映射到组件文件清单（主代码 6、gradle 1、androidTest 6、test 8、docs 2、tools 1），无 .ua/无关文件/构建产物混入；冲突标记 grep（kt/sh/md/toml，含 `<<<<<<<`/`>>>>>>>`/行首 `=======`）=0（`app/release/TabulaV3.3.3.apk` 二进制误报 1 处，非文本）。docs×2 均属组件：night-2026-11-08-B1-notes.md=#58、toolchain-compat-2026-11-08.md=#63。

3. **probe 脚本冲突解 = 通过。** tip 类列表=`CloudCleanupUiTest CloudRestoreUiTest CreateAlbumDiagnosticDeviceTest ForegroundPermissionUiTest PixelRefreshUiTest OtherAppMediaAuthDeviceTest`（expected_tests 门 8/3/2/2/2/4，并集正确）；PARTIAL 两阶段仍由独立 stage 驱动（PhaseA→宿主 force-stop→PhaseB，各 OK(1) 门）；行尾以 LF 为主，唯一 CR 为 FULL 段 `pm clear | tr -d '<CR>'` 行的字面 CR——master 历史自带（43a8da1 第 80 行同款）、两父本均在，无害；#65 的 `tr -d '\r'` 守卫（getprop/类循环 pm clear/PARTIAL pm clear）保留。P3 备注：文件顶部 PhaseB 长注释（"系统设置页重选 B+跨月夹具"）仅存在于最终合并提交（两父本均无此文本，为冲突解时手写）——已对照 PartialSelectionPhaseBTest 实际机制（launchSettingsPhotosPage/tapSettingsSelectionGear，KDoc 载明 ACTION_PICK_IMAGES 不更新持久 user-selected）与脚本跨月 touch 夹具（202610xx/202611xx）核实为准确描述，属善意改写而非"注释并留"的字面并集。

4. **CardStack 组合行为 = 通过，互不干扰。** tip 的 CardStack.kt 共 3 个 hunk、区域互不重叠：#61 两处 onDragCancel（~1506 与 ~3208，deck/draw 两个栈）各补 `preloadingManager.resume()` + `setFastMode(false)`（含 N1 注释）；#62 preloadNextCards（~1772）将 offset 1..4 四连发 preloadAround 改为单次最远有效索引 `minOf(fromIndex+4, images.size-1)`（含越界回退 no-op 语义，T06 清理注释）。两者调用 preloadingManager 的不同方法、无共享可变状态叠加；#62 分支 tip 对 tip 的 diff 恰=#61 的 8 行追加，零丢失。

5. **runner 套件保留 = 通过。** 合并后 probe 脚本（258 行）：PARTIAL 两阶段（含地面真值/哨兵/失败模式 grep，partial_status 并入 status）、FULL 四阶段（phase1/absence/continuation/readback 各 OK(1) 门+失败模式 grep，full_status 并入 status）、像素刷新 PixelRefreshUiTest（2 例）、他人媒体 OtherAppMediaAuthDeviceTest（4 例）驱动与数量门全部在位；最终合并对 FULL 段 0 行改动。

6. **生产面完整性 = 通过。** app/src/main 实际 6 文件：AlbumManager.kt（#59/T10）、CardStack.kt（#61+#62）、DeckScreen.kt（#61/N2：标志复位入 finally，1 个 hunk）、ImageFeatureCacheStore.kt+ImageFeatureState.kt（#60/T11b）、StringDragSelectState.kt（#64/A4-T03：pruneBoundsNotIn+LaunchedEffect+findItemAt 转 internal）；gradle 仅 AGP 8.9.0→8.9.1 一行。**复审单枚举漏 StringDragSelectState.kt——属 #64 组件预期内容，非意外改动**（P3 登记）。超出枚举项：无。

## 问题分级

- **P0（阻塞）：无。**
- **P1（需修复）：无。**
- **P2（应处理）：无。**
- **P3（知悉即可）**：① 顶部 PhaseB 长注释为最终合并手写、超出"注释并留"字面描述（内容已核实准确）；② 复审单第 6 项枚举漏 #64 的 StringDragSelectState（预期内容）；③ "13 组件"与 12 分支合并的口径差：#56 经 #60 载体交付、其旧度量版被终版取代。

（方法备注：仅结构与差异核验，未复跑测试；本地 430/0 与 CI 覆盖不在本次范围。tip 上另有 2026-11-09 workflow_dispatch 运行进行中，与本复审无关。）
