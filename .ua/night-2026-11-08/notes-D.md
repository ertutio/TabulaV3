# 包D 夜间进度笔记（两种卡片模式 SWIPE/DRAW 生命周期覆盖核对+具体反例）

- 日期：2026-11-08。写者：包D（wg-D 工作区）。
- 基线：origin/master `43a8da1037ca50f34c9fabd8a41c2ac505c93824`（PR51/PR52 已合并）。
- 分支：`fix/card-mode-lifecycle-2026-11-08`（独立基于 master，无堆叠）。
- 交付：**PR #58** https://github.com/ertutio/TabulaV3/pull/58 （head `c9b7cf0f487f3dbbf669e06cc5b3f4c9d51b99e6`，单提交，仅测试 2 文件 +218，OPEN 待人工评审；按约定未合并、未推 master、CI 后置）。
- 方法：先逐焦点从**实际接线代码**（CardStack 两种模式、DeckScreen、PreloadingManager、DrawCardState、ImageCard）核对既有断言，再用确定性调度核对缺口；无生产反例则按约定"登记不强行造测试"，仅对**纯逻辑层已有行为缺断言**的焦点补钉（PR31/PR35 同口径：补断言并如实登记，无人工 FAIL）。

## 一、覆盖核对矩阵（焦点 × 既有断言，均在实际代码逐处核对）

图例：✅=有直接断言钉住；🟡=间接/宽松断言（行为对但断言不严格区分）；📜=静态纪律/审计核验（无单测基建）；⛔=本单元新登记缺口（见第三节）。

### 焦点1：请求版本（键含 URI/版本/选项）

| 断言 | 状态 | 指针 |
| --- | --- | --- |
| 预载键含内容版本（DATE_MODIFIED） | ✅ | `PreloadingManagerTest#requestKeyIncludesContentVersion`、`sameCenterDetectsChangedNeighborAndVersion`（生产 `cardMemoryCacheKey`，PreloadingManager.kt:177） |
| 同 ID 不同 URI/解码尺寸 = 新请求 | ✅ | `PreloadingManagerTest#sameIdDifferentUriOrDecodeSizeIsNewRequest`（identity=键+size+hardware，PreloadingManager.kt:93） |
| 卡片显示键=预载键（两模式共用） | ✅ | `DeckViewerThumbnailKeyTest`（生产=消费键匹配断言）+ ImageCard.kt:90（remember(uri, dateModified)） |
| 清理预取键与卡片键一致 | ✅ | `CleanupPrefetchRequestSpecTest` 5 项（PR44 交付） |
| 查看器缩略图键一致 | ✅ | `DeckViewerThumbnailKeyTest` 5 项（PR34 交付） |

### 焦点2：同索引换批/换内容

| 断言 | 状态 | 指针 |
| --- | --- | --- |
| 同索引换内容不被 completed 跳过 | ✅ | `PreloadingManagerTest#sameIndexWithDifferentContentIsNotSkipped` |
| 同索引替换的旧回调不得完成新请求（token 守卫） | ✅ | `PreloadingManagerTest#oldCallbackCannotCompleteReplacementAtSameIndex`（PreloadingManager.kt:121-128 token 比对） |
| 新批次淘汰窗口外请求（dispose 旧 Disposable） | ✅ | `PreloadingManagerTest#outOfRangeDisposablesAreCancelledOnNewBatch` |
| 同索引同版本在途请求不重复入队 | ⛔→本单元补钉 | 原无直接断言（`sameCenterDetectsChangedNeighborAndVersion` 的 `awaitEnqueued(5)` 为宽松下界，不能区分重复入队）→ 新增 `inFlightRequestWithSameIdentityIsNotReenqueued`（行为已正确，无生产改动） |
| 清理批次预取批消费/整批失效重取 | 🟡 | 纯函数半边 ✅ `ForegroundRefreshSyncTest`（syncCleanupBatchToSnapshot）；LaunchedEffect 消费接线 📜（无 Compose 单测基建，审计核验） |

### 焦点3：晚回调（旧结果不回写）

| 断言 | 状态 | 指针 |
| --- | --- | --- |
| reset 后迟到成功回调不污染新代 | ✅ | `resetIgnoresLateCallbackWhenSameMediaIsReentered`、`resetCancelsOldGenerationWorkAndManagerRemainsReusableDeterministically`（TestScope 确定性） |
| dispose 后迟到回调忽略+不可重入 | ✅ | `disposeIgnoresLateCallbackAndPreventsReentry`、`disposeCancelsWorkAndPreventsSubsequentEnqueueDeterministically`（TestScope 确定性） |
| 窗口淘汰后迟到成功不复活 | ✅ | `successEvictedOutsideWindowAndLateCallbackIgnored` |
| 同索引替换旧回调 | ✅ | `oldCallbackCannotCompleteReplacementAtSameIndex`（焦点2 同源） |
| enqueue 期间同步完成回调记录 | ✅ | `synchronousCompletionDuringEnqueueIsRecorded`（PreloadingManager.kt:113-114 双分支） |
| 旧扫描晚返回不覆盖新状态（页面级） | ✅ | `MediaSnapshotLoadRaceTest` 2 握手场景+对照（PR50 交付；gate 阻塞/NonCancellable 晚返回，变异验证过） |

### 焦点4：失败重试

| 断言 | 状态 | 指针 |
| --- | --- | --- |
| 失败不入 completed、中心移动后重试 | ✅ | `failedPreloadIsRetriedOnLaterPreloadAround`、`failureRetriesWithoutMovingCenter` |
| pause 后同中心失败可经 resume 重试 | ✅ | `pauseAllowsSameCenterRetryAfterResume` |

### 焦点5：页面退出（dispose/pause/reset 生命周期）

| 断言 | 状态 | 指针 |
| --- | --- | --- |
| dispose 取消全部在途请求 | ✅ | `disposeCancelsAllRequests` |
| 防抖窗口内 pause/reset/dispose 三时序 | ✅ | PR35+审计轮 4 项确定性回归（`pauseWithinDebounceWindow...`/`resetWithinDebounceWindow...`/`disposeWithinDebounceWindow...`/`resetDuringDebounceWait...`，均 TestScope advanceTimeBy 驱动） |
| dispose 取消管理器 scope 本身（直接断言） | ⛔→本单元补钉 | PR35 审计 CONCERN-4"未直接断言 scope 取消（间接覆盖）"→ 新增 `disposeCancelsProvidedScopeDirectly`（providedScope=TestScope，dispose 后 `isActive==false`；PreloadingManager.kt:165 scope.cancel()，行为已正确，无生产改动） |
| 两种模式 onDispose→dispose 接线 | 📜 | CardStack.kt:346（SHUFFLE）、:1759（DRAW）DisposableEffect(Unit)；Compose 级无单测基建，PR35 独立审计核验 |
| 两模式 Genie 自有位图退出回收 | 📜 | CardStack.kt:1738-1755 DisposableEffect 回收 owned 位图（ownedScaledCopyOrNull 来源，借用位图全库 0 处 recycle）+ `OwnedBitmapsTest` 5 项钉所有权契约生产半边 |
| 清理断点退出强制保存 | 📜 | DeckScreen.kt:379-385 DisposableEffect+rememberUpdatedState（防抖窗口内退出不丢最后一次）；引擎侧由清理套件钉，DeckScreen 接线 Compose 级 📜 |

### 焦点6：动画结束（DRAW 状态机）

| 断言 | 状态 | 指针 |
| --- | --- | --- |
| 发牌记录完整身份+清除残留过渡态 | ⛔→本单元补钉 | DrawCardState.kt:122-141 Draw 分支原无直接断言（DrawCardRecallIdentityTest 只经 Draw 取身份，未断言过渡态清除）→ `DrawCardStateLifecycleTest#drawRecordsFullIdentityAndClearsTransientAnimationState` |
| 收牌：栈顶回牌+记录收牌前右卡 ID（边界：右卡不存在→null） | ⛔→本单元补钉 | DrawCardState.kt:143-163 Recall 分支原无直接断言 → `recallReturnsLastDealtCardAndCapturesCurrentRightCardId`、`recallWithoutRightCardSetsExitingRightCardNull` |
| AnimationComplete 清过渡态、不回写位置/栈 | ⛔→本单元补钉 | DrawCardState.kt:165-170 原无断言 → `animationCompleteClearsTransientStateButKeepsPositionAndStack`（T06 验收行"动画结束…旧结果不回写"状态机层语义） |
| 边界拒绝（最后一张不可发/栈空或顶不收）/Reset | ⛔→本单元补钉 | `drawOnLastCardIsRejectedAndStateUnchanged`、`recallWithEmptyStackOrAtTopIsNoOp`、`resetReturnsToFreshState`、`dealtStackIdentitySurvivesRepeatedDrawRecallCycles` |
| 动画执行接线（dispatch 时序/finally 复位） | 📜 | CardStack.kt:1894-1974（发牌）/1987-2073（收牌：AnimationComplete 在 finally 前 dispatch，:2045）；Compose 级无单测基建，审计核验 |

### 焦点7：scope 释放

| 断言 | 状态 | 指针 |
| --- | --- | --- |
| dispose 取消 scope（直接） | ⛔→本单元补钉 | 见焦点5 行（同测试） |
| dispose 后无后续工作 | ✅ | `disposeCancelsWorkAndPreventsSubsequentEnqueueDeterministically` |

### 焦点8：排队 vs 成功 vs 失败三态区分

| 断言 | 状态 | 指针 |
| --- | --- | --- |
| 成功不重排（同内容） | ✅ | `successfulPreloadIsNotRepeatedForSameContent` |
| 失败不记完成、可重试 | ✅ | 焦点4 两项 |
| 在途不重排 | ⛔→本单元补钉 | 见焦点2 行（新测试） |
| preloadImmediate cacheResult=false 不记完成 | 🟡 | 无生产调用者（PR35 审计 CONCERN-6，测试专用 API 维持），无专属断言；维持登记 |

## 二、登记项复核（已知遗留逐项现场确认）

| 遗留项 | 本轮复核结论 |
| --- | --- |
| ①DRAW preloadNextCards 冗余连发（CardStack.kt:1769-1783） | **仍在，确认为纯冗余、无行为缺陷**。现场推演：`executeDrawAnimation`（:1917）在状态更新（:1931）前以 fromIndex=i+1 连发 4 次 `preloadAround`（preloadRange=5，center=i+2..i+5）；各次同步执行、每次 `debounceJob?.cancel()` 取消前次，仅第 4 次（center=i+5，窗口 [i, i+10]）的防抖任务存活；随后状态更新触发 `LaunchedEffect(drawState.currentIndex, images)`（:1760-1766）再次 `preloadAround(i+1)`——同一防抖槽被替换，窗口改写为 [i-4, i+6]，且陈旧 active 请求按窗口 dispose（正确性由 manager 窗口重算保证）。即：**4 连发的有效贡献被 LaunchedEffect 确定性覆盖为 0**，唯一代价 3 个空转防抖 Job（窗口内取消，零 IO/解码）；即使重组延迟 >100ms 使第 4 次先入队，后续 LaunchedEffect 窗口重算仍收敛（多预载非缺陷）。**维持登记不清理**（与本轮"最小修复、不为清理重构"约定一致）；若后续做小提交清理，安全形态=删除 preloadNextCards 或仅保留单次调用，无行为变化。 |
| ②焦点④借用位图"调用方半边"无测试钉 | **维持登记**。生产半边（ownedScaledCopyOrNull/createGenieBitmapFast 自有副本、借用位图全库 0 recycle）由 `OwnedBitmapsTest` 5 项+审计核验；调用方纪律（调用方只回收自有副本）为静态纪律，Compose 级无基建。全库 `grep recycle()` 复核：回收站点仅 CardStack 自有位图（:1744-1753、:1864-1880）与 OwnedBitmaps 测试。 |
| ③exitingRightCard 裸 ID 比较 | **维持登记不修**。现场确认 CardStack.kt:2632 `drawState.exitingRightCard != rightCard.id`。本轮补充代码级推演：`!isRecallTransitioning`（同条件）已独立抑制整个过渡期的右卡渲染，且状态机顺序保证 AnimationComplete（清 exitingRightCard，:2045）先于 finally 的 `isRecallTransitioning=false`（:2049）——该裸 ID 比较在正常时序下是**双重冗余层**，仅跨卷同 ID 时反而在 isRecallTransitioning 已 false、exitingRightCard 尚存的窗口（理论约一帧）误抑制新右卡，自愈。与 PR33 审计登记一致（非定位缺陷）。 |
| ④deck 同 size 同 index 内容变化不触发重校验 | **维持登记（两版共有局限）**。现场确认重校验触发键=CardStack.kt:1629 `LaunchedEffect(currentIndex, images.size)`。本轮补充可达性推演：deck 批次成员变化在生产接线中是**单向剪枝**（C4/T07 删除路径，currentBatch 按 allImages 身份键剪枝+currentIndex 对齐——剪枝必变 size；新增照片不进已有批次），同 size 同 index 且 dealt 位置身份漂移需要"删除+同批插入"同时发生，实际接线不可达；快照替换（同身份最新对象）虽不触发重校验但 originalIndex 仍有效且预览/收牌现取最新行（任务 B 修复）。**理论洞保留登记，不合成不可达反例。** |

## 三、本单元新登记缺口（代码级具体反例，但接线层无单测基建——登记不修，不强行合成）

### N1：两种模式的 `onDragCancel` 不恢复预加载（也不关快速检测）

- 位置：CardStack.kt SHUFFLE `onDragStart`:1154 `preloadingManager.pause()` + `setFastMode(true)`:1155；`onDragEnd`:1431-1432 `resume()`+`setFastMode(false)`；**`onDragCancel`:1506-1514 两者皆无**。DRAW 完全同构：pause:2826/fast:2827、resume:3120/fast:3121、**onDragCancel:3203-3212 两者皆无**。
- 反例推演（确定性）：手势被系统打断（onDragCancel）→ pause 生效且永无 resume → 此后所有 `preloadAround` 在 `isPaused.get()` 早退（PreloadingManager.kt:61,64），`LaunchedEffect(currentIndex, images)` 照常调用但全部无效；同时 `ImageFeatureDetector.fastModeEnabled` 停留 true（badge 重检测阴性落 `definitive=false`，T11a 语义下不可信阴性，不污染缓存）。
- 影响：活性/性能（预载停摆+badge 检测降级），**非正确性**——卡片显示由 ImageCard 自身 Coil 请求保证；自愈途径=用户下一次完整滑动（onDragEnd→resume+fast(false)）或退出 deck（dispose）。pause/resume 本身为幂等。
- 不修理由：接线在 Composable 手势回调内，无 Compose 单测基建（与 PR50"旧扫描竞争"不同——该处可 verbatim 提取纯函数，此处 pause/resume 是两行副作用调用，无可提取行为单元）。修复形态若立项：onDragCancel 补 `resume()`+`setFastMode(false)`（与 onDragEnd 同两行），或把两行收敛进 resetDragState；属低风险小提交候选，建议与 N2 一并下轮处理。
- 建议登记验收边界：修复时可用 Robolectric Compose 规则（若引入）或设备探针；单测层面无法立有效基线 FAIL，不符合本轮"有效 FAIL 后才动生产"门槛。

### N2：清理模式预取标志 `isPreloadingNextBatch` 取消后永久卡死

- 位置：DeckScreen.kt:399-446 预取 LaunchedEffect。`:425 isPreloadingNextBatch = true` → `:431 albumCleanupEngine.getNextBatch(...)`（**suspend**，AlbumCleanupEngine.kt:316）→ `:444 isPreloadingNextBatch = false`。**无 try/finally**。
- 反例推演（确定性）：批次尾段（remaining≤5，:423）预取启动（flag=true）→ `getNextBatch` 挂起（prefs/IO）→ 用户滑动改 currentIndex（LaunchedEffect 键之一，:399）→ 协程在挂起点被取消 → `:444` 永不执行 → flag 永久 true → 门 `:401 if (isPreloadingNextBatch || preloadedNextBatch != null) return` 拦截此后**全部**预取（本 composition 生命周期内，flag 无其他复位点：grep 仅 :340 初始化/:401 门/:425 true/:444 false）。
- 影响：活性/性能——下一批退化为完成通道的实时获取（:696-699 有兜底，功能不断；批次衔接失去无缝预取）。flag 为 `remember` 状态，重进页面自愈。
- 不修理由：同 N1，接线在 LaunchedEffect 内，无 Compose 单测基建；verbatim 提取整段 effect（含两处 cleanupId 分支+enqueue 循环）为重构性改动，超出"最小修复"。修复形态若立项：`:425-:444` 包 try/finally（finally 内 flag=false），约 3 行，低风险小提交候选。
- 与既有登记的关系：PR44 曾登记"preloadNextCards 冗余连发与 R-2 判据维持登记不动"，N2 是**新发现**（R-2 判据指的是预取触发条件耦合，非取消窗口卡死）。

## 四、本单元交付（仅测试，无生产改动）

- 新增 `app/src/test/java/com/tabula/v3/ui/components/DrawCardStateLifecycleTest.kt`：8 项（DRAW 状态机动画生命周期：发牌身份+过渡态清除/收牌回牌+右卡 ID 边界/AnimationComplete 只清过渡态/边界拒绝/Reset/往返循环身份一致）。**无 baseline FAIL**——生产行为核对正确，纯断言补钉（登记声明，不冒充反例）。
- `PreloadingManagerTest` +2：`inFlightRequestWithSameIdentityIsNotReenqueued`（三态区分"在途"腿直接钉）、`disposeCancelsProvidedScopeDirectly`（CONCERN-4 闭环：scope 取消直接断言）。**无 baseline FAIL**，同上口径。该类现为 22 项（PR35 交付 19 + 审计轮 1 + 本单元 2）。
- 无生产文件改动；无已预期 FAIL 项；无 skip。
- 门禁记录：`./gradlew :app:testReleaseUnitTest --tests "com.tabula.v3.di.PreloadingManagerTest" --tests "com.tabula.v3.ui.components.DrawCardStateLifecycleTest" --tests "com.tabula.v3.ui.components.DrawCardRecallIdentityTest" --no-build-cache` → 38/38 全绿 0 skip（22+8+8，XML 实计：PreloadingManagerTest tests=22 failures=0、DrawCardStateLifecycleTest tests=8 failures=0、DrawCardRecallIdentityTest tests=8 failures=0）。全量 `:app:testReleaseUnitTest --no-build-cache` 结果见第六节。

## 六、门禁与全量（实际执行记录）

- 受影响门禁：PreloadingManagerTest 22/22、DrawCardStateLifecycleTest 8/8、DrawCardRecallIdentityTest 8/8（0 skip、0 失败，XML 实计）。
- 全量：`./gradlew :app:testReleaseUnitTest --no-build-cache`（ANDROID_HOME=C:/Users/yuxuan/android-sdk）→ **BUILD SUCCESSFUL，60 个测试类 XML 实计 403 tests / 0 failures / 0 errors / 0 skipped**（master 基线 393 + 本单元 10；testReleaseUnitTest 任务实际执行，编译任务为同工作区前次 --no-build-cache 运行的 up-to-date 输出复用，非 FROM-CACHE）。与既有"Windows 本机 0 失败"基线口径一致，无环境失败需单列。

## 五、剩余与建议

1. N1/N2 为低风险小提交候选（各 ≤5 行生产改动），建议下一包以"登记→设备/Compose 探针或人工验收"路径处理；若后续引入 Compose 单测基建（robolectric compose rule），两者都可立有效 FAIL。
2. preloadNextCards 连发清理候选维持（安全形态见第二节①）。
3. exitingRightCard/焦点④维持既有登记。
4. 真实设备/Compose 动画级验收（两种模式滑动中退出、动画中 dispose）维持既有 blocked/审读口径，不因本轮单测关闭。
