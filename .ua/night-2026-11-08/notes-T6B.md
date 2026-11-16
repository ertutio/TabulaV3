# T06 剩余场景设备级验收 notes（2026-11-14，wg-T6B 单文件单写者）

任务：T06 设备级生命周期（#74，3 例，审计 PASS）交付后的**剩余场景**设备级验收——
新 androidTest 套件 `T06RemainingDeviceTest` + PreloadingManager 行为级 DEBUG 门日志增补
+ probe 第 9 类登记（expected_tests=2）。

## 基线

- 工作树 `../wg-T6B`（主仓 C:\Users\yuxuan\.zcode\workspace\draft-cc5a13c3-330d-481c-
  871b-699e95f2900f\TabulaV3 执行 `git worktree add -b test/t06-remaining-2026-11-14
  ../wg-T6B origin/master`），基 **master f745036**（含 #70/#75/#76 合并，即全部 13 组件
  + #66/#74/#75/#76 内容）。
- 提交：49aa39f（test: T06 remaining device scenarios - same-index swap identity +
  mid-animation BACK exit）。
- local.properties：sdk.dir=C:/Users/yuxuan/android-sdk（ANDROID_HOME）。

## 场景选择登记（按可观测性选 a+c）

用户指令三场景（同索引换批 / 动画中导航或退出 / 请求取消后重入），经真实调用链逐评估：

| 场景 | 结论 | 依据 |
| --- | --- | --- |
| a 同索引换批 | **采纳**（case 1） | 调用链确定：swipe 上滑删除 → genie onComplete → DeckScreen onRemove（currentBatch 缩容、currentIndex 不变 → 同索引承接原下一张）→ LaunchedEffect(currentIndex, images) 重发 preloadAround → 防抖体窗口身份快照可日志化——**确定性信号** |
| c 动画中导航退出 | **采纳**（case 2） | BACK 于 genie 删除动画（440ms）中注入 → Activity finish → 组合销毁；**时序收敛论证**（见下）使"删除未提交"成为与竞速无关的确定结论 |
| b 请求取消后重入 | **不采纳设备级，登记理由** | 夹具微图（32px）解码毫秒级完成 → in-flight 窗口无法由真实手势时序确定性命中；100ms 防抖窗口 < 真实手势间隔（shell input ≥300ms）→ 防抖抢占/in-flight staleDispose 均非确定可达。reducer 级语义已由 PreloadingManagerTest 确定性覆盖（pauseWithinDebounceWindowHoldsEnqueueUntilResumeDeterministically / oldCallbackCannotCompleteReplacementAtSameIndex / outOfRangeDisposablesAreCancelledOnNewBatch 等），与 #74 的"设备接线 vs reducer 单测"分工口径一致 |

## 生产改动（仅 DEBUG 门日志，逐行登记）

**仅 PreloadingManager.kt，5 处，全部 `if (BuildConfig.DEBUG)` 门 + 既有
TAG=PreloadingManager（与 CardStack.debugCardStackLog 同模式）；不动 CardStack；
不动 app/src/test**（PreloadingManagerTest testReleaseUnitTest 本地绿）：

1. `preloadAround` 防抖体内、window 构建后：
   `window gen=$runGeneration idx=$currentIndex identities=[<i>=<cacheKey>,...]`
   （cacheKey=identity substringBefore('|')）——索引→身份映射快照。
2. stale 过滤后（仅 stale 非空时）：
   `staleDispose index=<i> token=<t> identity=<旧身份>`——in-flight 旧请求取消+释放。
3. `enqueue` 内 `imageLoader.enqueue(request)` 前：
   `enqueue index=<i> token=<t> identity=<完整身份>`——真实请求发出。
4. `finish` 三重守卫（active 存在 + token 匹配 + 未 disposed）**之后**：
   `finish index=<i> token=<t> success=<b> identity=<身份>`——仅活 token 回写可见；
   token 失配/已释放的旧回调在守卫处静默丢弃、不产生日志（"旧回调无法写入新批次"的
   可观测边界）。
5. `dispose` 内 disposed=true 后、清空前：`dispose active=<n> completed=<n>`——
   组合销毁整体释放。

设计动机：#74 审计结论"已有 pause/resume 日志只能证明调用发生"——本套件全部恢复
结论改以**实际任务状态/行为结果**支撑（真实新请求发出/回写/释放），pause/resume 日志
退居接线顺序锚点。

## 断言链（可观察信号）

1. **窗口身份快照**（新增 window 日志）：同索引换批的"请求身份同步更新"直接信号——
   初始 `idx=0 identities=[0=card_X,1=card_Y]` → 删除后 `idx=0 identities=[0=card_Y,
   1=card_Z]`（slot0 身份=原 slot1 的卡、被删身份消失）。
2. **真实请求发出**（新增 enqueue 日志）："实际预载请求恢复"行为级信号——从未入窗的
   第三张在换批后获得 `enqueue index=1 identity=card_Z|...`；case 2 重启后新管理器对
   全窗口逐卡重发（dispose 已清空 completed）。
3. **回写边界**（新增 finish 日志）：仅活 token 回写可见——token-身份序列完整性断言
   （每个 finish 的 token 必须有先行同 token 同身份的 enqueue）；换批标记后无被删身份
   的 enqueue 重发/finish 迟到回写（有界扫描）。
4. **生命周期释放**（新增 dispose 日志）：组合销毁释放管理器；其后到重启前无任何
   enqueue/window/staleDispose/finish（disposed 守卫 + debounce 取消 + scope cancel
   → 静默负向断言）。
5. 接线顺序锚点（既有日志）：删除手势三元组 pause ≤ dragEnd ≤ resume（#74 口径）；
   case 1 恢复顺序 resume(ts) ≤ fresh-enqueue(ts)。
6. 状态真值：deck 计数（"N 张待整理"）、回收站记录（RecycleBinManager，观察式反推被删
   名）、MediaStore 行存在 + relative_path 行数恒定（软删除契约，#74 口径）。
7. case 2 退出/崩溃真值：root 包名≠本应用（离开前台）、pidof 存活、`logcat -b crash`
   无本应用记录。

## case 1（SHUFFLE）同索引换批要点

- 交互卡身份不预设：初始窗口 slot0（idx0 即 currentImage，CardStack 组成映射）即当前
  交互卡；被删卡名以回收站记录反推后**回锚断言 = 初始 slot0**（换批前提真值）。
- "第三张"（从未入窗：初始窗口 {0,1}、仅 idx0/idx1 组合显示）在换批后必获新请求
  （manager 内 completed 不含其身份；外部 DeckScreen 预取不写 manager.completed）——
  恢复结论的行为级确定性信号。
- 二次删除 = 后续操作能力：换批后当前卡（原下一张）再删 → 计数递减 + 记录累计 2 条 +
  终态窗口 slot0=第三张。
- 日志匹配用 `card_<uri>_` 片段（带尾分隔符）——纯 uri 前缀会互串（media/123 是
  media/1234 的前缀）。
- token-身份完整性按管理器实例分窗（case 2 重启后新实例 token 从 1 重计，跨实例混窗
  假阳性；case 1 单实例全程一窗）。

## case 2（DRAW）动画中 BACK 退出要点

- **时序收敛论证**（删除未提交 = 竞速无关确定结论）：BACK 在 dragEnd 观察后立即注入
  （≤ ~500ms），genie 440ms——
  - BACK 先于 genie onComplete：组合销毁中断动画与回调（onRemove 不触发）；
  - BACK 后于 genie onComplete：DeckScreen 记 pending + Snackbar 显示，数据层删除
    **仅发生于 Snackbar dismiss**（onDismiss → onRemove），而 Snackbar 的 3000ms
    计时是组合内 LaunchedEffect——随组合销毁取消，提交不发生。
  - 两分支回收站必无记录、媒体行/行数不变。
- deck 为主页无 BackHandler 拦截（DeckScreen 仅 viewerState/isSelectionMode 挂
  BackHandler；PredictiveBackContainer 仅非 DECK 屏）→ KEYCODE_BACK 即 Activity finish。
- 重启恢复：deck 计数 = 退出前计数（读数动态化，不硬编码 3）+ 全量预载重建（新管理器
  completed 空 → 全窗口重发）+ crash 缓冲干净 + 再删除可提交。
- pid 断言口径：断言"存活"（pidof 非空）而非前后相等——BACK-finish 后进程为 cached
  态，系统可合法回收；crash 缓冲 + 可操作性才是"无崩溃"契约。

## 断言纪律（沿用 #74）

- 全部等待有截止时间轮询；失败留证：截图 + 树 dump + logcat 切片 + crash 切片写入
  `cloud-ui-failures/t06-remaining-<tag>.*`（文件名无 `|`）；CI trap 拉取为 artifact。
- 夹具：每例 3 张合成媒体（shell 绑定所有权，CloudFixtureCleanup 清单 + @After 哨兵
  校验无关哨兵未被误删）。
- 日志归属：手势标记（注入前采样不回退）+ 单调游标双重下界（#74 P2-1 口径）。
- 覆盖口径：只宣称设备级接线验收；reducer 级覆盖归 PreloadingManagerTest/
  DrawCardStateLifecycleTest，互不冒称。
- 预期 case 数：2（probe 登记 expected_tests=2，tools/run-cloud-ui-probe.sh 第 9 类）。

## 验证记录

- `:app:compileDebugAndroidTestKotlin` 本地绿（2026-11-14，49aa39f）。
- `:app:testReleaseUnitTest --tests PreloadingManagerTest` 本地绿（生产增补不破坏
  reducer 契约）。
- CI：build-volume-confirmation.yml @ test/t06-remaining-2026-11-14，run 43468670035
  起（按轮迭代，同卡点 2 轮红→阻塞登记）。

## 轮 1（run 43468670035，2026-11-14）：双 API 0/2 红 → 根因定位与修正

- **同卡点**：两例均 `window log not observed: idx=0`（初始窗口等待 20s 超时），
  API36/API37 一致。
- **根因（确定性，非环境）**：初始 phase 的 `advanceLogCursor()` 在 deck-ready 返回
  **之后**执行——窗口/enqueue/finish 日志在 deck 组合期即已发出（实测初始窗口日志
  早于 deck-ready 可见约 1-2s），游标被推过这些日志 → `isWithin`（ts ≥ cursor）永久
  排除。修正：初始 phase（case 1 初始 / case 2 初始 / case 2 重启后）不推进游标，
  startMark/relaunchMark（startActivitySync 之前采样）即本 phase 下界；游标保留给
  手势 helper 内部的阶段边界。
- **次要发现（登记）**：本 run 中 `logcat` filter spec（'Tag:V' '*:S'）被设备忽略
  （失败留证 dump 含 MediaProvider/WifiScoreCard 等全量行，双套件一致；既有
  CardLifecycleDeviceTest 断言因按 tag 门控不受影响、API36 3/3 绿）。影响 = 全量流
  加速环形缓冲轮转，用例后期的 token-完整性扫描需早期行存活 → @Before 增加
  `logcat -G 16M` 扩容主缓冲（对 spec 生效环境无害）。断言不受影响（全部 tag 门控）。
- **机制实证（同一 run 既有套件 logcat）**：SHUFFLE 换批路径实测产出
  `window gen=4 idx=0 identities=[0=card_137...,1=card_136...]` + `enqueue index=1
  token=3 identity=card_136...`（删除 idx0 后同索引承接原下一张、第三张获得真实新
  请求）——case 1 断言所依赖的生产行为在真实设备按预期发生。

## 轮 2（run 43473978834，2026-11-14）：双 API 0/2 红 → 根因定位与修正（卡点前移，非同点）

- **进展**：轮 1 的 window-not-observed 已消（初始窗口日志双 API 均命中）——轮 1 根因
  修正有效。卡点前移至初始窗口身份匹配断言。
- **同卡点判定**：轮 2 卡点（keyFragment 串匹配恒不中）≠ 轮 1 卡点（游标排除）——
  非同卡点 2 轮红，不触发阻塞登记。
- **根因（确定性）**：日志匹配片段用夹具对象自身 uri 串（插入返回
  `content://media/external/images/media/<id>`），而应用侧缓存键为
  `content://media/external_primary/images/media/<id>`（卷路径 external_primary）——
  `expected:<2> but was:<0>`。实测窗口/请求日志内容本身完全正确（draw 三槽
  [141,140,139]、shuffle 两槽 [145,144]）。修正：keyFragment 改为
  `images/media/<id>_`（Media 行 id 全卷唯一 + 带尾分隔符防 45/145 类互串），
  双 uri 形态通吃。
- **同 run 副证**：dispose 日志在组合销毁（@After finish）时按预期产出
  （`dispose active=0 completed=2`）——case 2 的 teardown dispose 断言路径成立。

## 轮 3（run 43477827912，2026-11-14）：双 API 1/2——case 2 双 API 绿；case 1 仅剩串形比较

- **case 2（DRAW 动画中 BACK 退出）双 API PASS**——teardown dispose + 静默 + 删除未
  提交 + 重启全量预载重建 + 后续可操作全链路验证成立。
- **case 1 推进至倒数第 3 断言**：初始窗口匹配/真实删除/换批窗口身份/第三张新请求/
  旧身份无回写负向扫描/token 完整性/二次删除 全部通过，唯
  `第二次删除的对象应为换批后的当前卡` 用 uri 串比较——回收站记录行 uri 为应用侧
  external_primary 形态，夹具对象为插入 external 形态（id 实测相等 143==143 /
  145==145）。修正：改用 matchesSameMediaIdentity（id+卷，T03 媒体身份比较器）。
- **同 run 副证（scenario a 生产行为全记录）**：初始窗口 [145,143] → 删除后
  gen=3 idx=0 [143,144]（同索引承接 + 第三张入窗 + enqueue index=1 token=3）→
  二次删除后 gen=5 idx=0 [144] 单槽 → dispose active=0 completed=1；staleDispose
  零出现（微图无 in-flight，负向"无回写"扫描一致通过）。
- 非同卡点 2 轮红（轮 2 卡点已消），不触发阻塞登记。

## 轮 4（run 43481421413，2026-11-14）：全绿

- build + device API36 + device API37 全部 success；T06RemainingDeviceTest 双 API
  `OK (2 tests)`；既有套件回归全绿（含 CardLifecycleDeviceTest 双 API OK(3)——轮 1
  API37 的"恢复 button not found"为环境性偶发，本轮未复现）。
- 分支提交：49aa39f → 3fb335a（轮 1 游标修正）→ 2bc3a38（轮 2 keyFragment 修正）→
  dec8fcf（轮 3 媒体身份比较修正）。

## 覆盖映射（任务四验证维度 → 断言）

| 验证维度 | 断言落点 |
| --- | --- |
| 实际预载请求恢复 | case 1：换批后对从未入窗第三张的真实 `enqueue index=1`（resume ≤ enqueue）+ `finish success`；case 2：BACK 退出组合销毁 dispose 后，重启新管理器全量重发（窗口含 3 身份 + 逐卡 enqueue） |
| 旧 Disposable 释放 | case 2：组合销毁 `dispose` 日志（组合销毁即整体释放路径）+ 其后至重启前零请求活动（disposed 守卫+debounce 取消+scope cancel 的静默负向断言）；in-flight 时刻 staleDispose 路径已日志化（轮 3 实证微图场景零出现，与夹具解码时序一致） |
| 旧回调无法写入新批次 | case 1：换批标记后无被删身份 enqueue/finish（有界负向扫描）+ 全程 token-身份序列完整性（每个 finish 必有先行同 token 同身份 enqueue）；reducer 级由 PreloadingManagerTest oldCallbackCannotCompleteReplacementAtSameIndex 等确定性覆盖 |
| 两种卡片模式后续操作能力 | case 1（SHUFFLE）：二次真实删除（换批继续成立+计数递减+记录累计）；case 2（DRAW）：重启返回后真实删除可提交 |

# 微单元 #78（2026-11-14）：两项设备验证补强

任务：修复一（DRAW 动画中退出确定性——BACK 注入落在"删除动画已启动且未完成"的
结构性窗口，替换轮 4 仅有的 dispose.ts >= dragEnd.ts 后验比较）+ 修复二（旧回调
晚到设备验证——真实预载入口 + 晚到回调注入钩子 + 守卫拦截断言）。分支
test/t06-remaining-2026-11-14（实际分支名，任务书 11-12 为笔误；起点 head 与任务书
指定一致 = 1047f912）。

## 真实调用链读取结论（修复一）

- DRAW 删除执行体：`CardStack.kt` DRAW 变体 `executeDeleteAnimation`：onDragEnd →
  `dragEnd draw` 日志 → `preloadingManager.resume()` → `scope.launch`
  （rememberCoroutineScope，组合销毁即取消）→ bitmap 预载等待（≤100ms）/IO 兜底 →
  `genieController.startAnimation(...)` → `GenieEffect.kt`
  `GenieAnimationController.startAnimation`：isAnimating=true → snapTo(0) →
  animateTo(440ms) → **onComplete()（先于 onRemove——提交边界）** → reset()。
  取消语义：组合销毁取消协程 → onComplete 不执行（删除不提交）。
- 最小可观测位置：启动/完成日志置于 DRAW executeDeleteAnimation（绑照片 id 与
  控制器实例）；挂起点置于 startAnimation 内 snapTo(0) 之后、animateTo 之前
  （isAnimating=true 后的 suspension point——取消契约与生产"取消于 animateTo 期间"
  等价：onComplete 均不执行）。

## 生产改动（仅 DEBUG 门，逐行登记；不动 app/src/test）

1. **GenieEffect.kt**：新增 `GenieDeleteAnimTestHook` 单例（armForPhoto/onTarget/
   takeHold 三入口全 BuildConfig.DEBUG 门；consume-once、消费即解除 id 绑定；挂起
   无释放 API——仅组合销毁的协程取消可结束，杜绝"释放竞态完成动画"）；startAnimation
   内 snapTo(0f) 后、animateTo 前挂起块（命中时 `deleteAnimHold genie=<hash>` 日志 +
   while(true) delay(20)）；imports 增 Log/BuildConfig/delay。Release：takeHold()
   恒 false 直通。
2. **CardStack.kt**（仅 DRAW 变体 executeDeleteAnimation）：genie 分支 startAnimation
   调用前 `deleteAnimStart draw img=<id> genie=<hash>` 日志 + if(BuildConfig.DEBUG)
   钩子 onTarget 接线；onComplete 内（onRemove 前）`deleteAnimComplete draw img=<id>
   genie=<hash>` 日志；fallback 分支启动前 `path=fallback` 日志（无挂起钩子——夹具
   有效 JPEG 不可达，出现即 hold 等待超时留证定位）。SHUFFLE 变体不动。
3. **PreloadingManager.kt**：enqueue listener onSuccess 内 tryDeferFinish 直通包裹
   （未 arm/Release 恒 false 即 finish，行为不变）+ companion 晚到回调钩子块
   （armDeferredFinishForTest/releaseDeferredFinishForTest/tryDeferFinish 全 DEBUG
   门；捕获即解除 consume-once；lateFinishCaptured/lateFinishDelivered/
   lateFinishReleased 日志）。既有 5 处日志不变。probe 登记 expected_tests=3；
   包装 timeout 720→900s（轮 5 实证）。

## 修复二控制点覆盖层（逐项登记）

- 钩子控制 **listener→finish 的交付时机**（捕获→测试释放→真实 finish 守卫）；
  finish 的 active 存在 + token 匹配 + 未 disposed 三重守卫与 stale remove +
  Disposable.dispose 均按生产原样执行（钩子不改任何守卫/取消逻辑）。
- **底层取消后回调是否仍可能**：coil Disposable.dispose 对已完成请求为 no-op（无
  观测差异）；真实竞态"完成与 dispose 交错"产生晚到回调——钩子以确定性顺序复现
  同一守卫路径；不覆盖 coil 内部取消状态机。
- coil 结果路径（缓存写入）未延迟：生产中结果与回调同源完成，晚到的可观测契约即
  回写（finish）。
- 旧请求进入持身份/版本/token：identity 含 dateModified 缓存键 + token，均持于
  enqueue/lateFinish 日志。
- 相邻证据：PreloadingManagerTest（reducer 级，不动）+ 负控制 run 43519541480
  实证（变异 B 未门控时 reducer 级先红）。

## 轮次记录

### 轮 5（run 43513218218，1e950de）：API36 红——probe 包装超时，非测试失败

- API37 device 绿（T06Remaining 3/3 OK）；API36 红：probe.sh 在 720s subprocess
  超时被截断（截断点 = 收尾套件 PartialSelectionPhaseB 进行中）。T06Remaining 增至
  3 例（+晚到回调用例 ≈90s）使 2GB API36 首次触顶。修正：run-volume-device-probe.py
  包装 timeout 720→900s（2be08ad）。

### 轮 6（run 43516660722，2be08ad）：全绿

- build + device API36 + API37 全 success；T06RemainingDeviceTest 双 API
  OK (3 tests)（确定性窗口 case 2 + 晚到回调 case 3 首次双 API 绿）。

### 负控制（必须项，隔离变异，验证后恢复）

- **变异 B（修复二）**：finish 移除 token 匹配门。
  - run 43519541480（未门控）：build 阶段 release reducer 级先红——
    PreloadingManagerTest.oldCallbackCannotCompleteReplacementAtSameIndex 与
    resetIgnoresLateCallbackWhenSameMediaIsReentered 失败（PreloadingManagerTest.kt:83），
    device job 被阻断——reducer 级负向证据。
  - run 43521168823（DEBUG 门控至设备 debug 构建，release 单测绿、device 可达）：
    **API37 device 实证** case 3 在新负向断言
    `失效后交付的旧回调不得产生任何回写/重发（finish 守卫 token 拦截）` 失败——
    logcat 实录：lateFinishDelivered index=1 token=2 后紧跟
    `finish index=1 token=2 success=true identity=...146_...`（旧 token=2 回写覆盖
    新 token=4 的 Active）。**API36 同 run 全绿**——机制：捕获次序不定（该 run
    API36 捕获 index0/token1，换批后该槽因 completed 命中不重建请求，生产第二重
    守卫 active[index] ?: return 仍丢弃——变异只破坏 token 门未破坏 presence 门；
    API37 捕获 index1/token2，槽位有新 Active → 回写 → 断言咬合）。登记为捕获
    次序相关的非确定性咬合；API37 路径即真实竞态主害路径。
- **变异 A（修复一）**：取消路径仍交付 onComplete。
  - run 43521168823 的 A-v1（仅包裹 animateTo）**不触发**（API36 全绿机制实证）：
    测试挂起点在 try 外，取消落在 hold 循环，finally 不执行。
  - run 43525310678（A-v2：try 覆盖 挂起+animateTo，DEBUG 门控）：**双 API device
    job 均红**，失败位置即新负向断言
    `动画中退出后旧删除动画不得提交（取消路径 onComplete 不执行，无 deleteAnimComplete）`
    （drawBackDuringDeleteAnimationExitsCleanlyAndRecoversPreload）——case 2 断言
    对"取消仍提交"类回归确实咬合。
- 恢复：c938158（restore 双文件至 2be08ad 态）→ b54cbc2（restore GenieEffect 至
  c938158 态）；git diff 2be08ad HEAD 为空——生产代码与轮 6 绿态逐字节一致。
- **证明范围登记**：普通绿 CI（轮 6/轮 10）证明新断言在正常生产下通过且不破坏
  既有断言（3 例双 API OK(3)、全套件回归绿）；变异轮红证明新断言对对应行为破坏
  确实失败；合成日志分析（artifact logcat 实录，如 43521168823 API37
  lateFinishDelivered→finish token=2 序列）证明失败位置即所破坏契约的直接观测点。

### 轮 7-10（b54cbc2，与 2be08ad 生产/测试代码逐字节一致；docs-only 差异）

- 轮 7（run 43528838111）：API37 绿（T06Remaining 3/3）；API36 红——收尾套件
  PARTIAL PhaseA `photo picker grid cells < 5 (saw 0)`（系统照片选择器空网格，
  环境性）+ PhaseB 前置级联。T06Remaining 自身双 API 绿。
- 轮 8（run 43531362728）：双 API 红——既有套件 CardLifecycleDeviceTest 在 CI
  容量低谷超时（CloudCleanupUiTest Time 169.8s ≈常态 3 倍；API36 probe 触 900s
  包装超时）。环境性减速，T06Remaining 未及执行。
- 轮 9（run 43534666245）：API37 绿；API36 红——PARTIAL PhaseB
  `photos access page not reached`（系统 UI 导航环境性 flake，与轮 7 同套件不同
  断言点）。T06Remaining 双 API 绿（API36 本轮该套件未及执行的部分与 API37 一致）。
- **轮 10（run 43536950642，最终头 b54cbc2）：全绿**——build + device API36 +
  device API37 全 success，T06RemainingDeviceTest 双 API OK (3 tests)
  （API37 01:07:46 / API36 01:08:22）。轮 7-9 三连红均为相邻既有套件（PARTIAL/
  CardLifecycle）的环境性 flake（同代码轮 6 全绿佐证），非同卡点测试逻辑问题。

## 覆盖映射增量（#78 两项 → 断言）

| 验证维度 | 断言落点 |
| --- | --- |
| 动画中退出确定性窗口 | case 2：deleteAnimStart（绑定照片 id+控制器实例）→ deleteAnimHold（genie 哈希一致；挂起无释放 API → 完成在取消前结构性不可能）→ BACK 注入；注入前无 deleteAnimComplete 前置断言 |
| 旧动画不提交（负向直接证据） | case 2：自删除手势标记至重启前无 deleteAnimComplete（取消路径 onComplete 不执行）+ 数据层契约（回收站无记录/行数恒定/计数=退出前） |
| 旧回调晚到不能写回 | case 3：lateFinishCaptured（失效前捕获）→ staleDispose（真实换批失效）→ lateFinishDelivered（交付≥失效）→ 旧 token 零生命周期日志（finish 守卫拦截；同身份新 token 合法重建不受限） |
| 新请求正常成功/后续交互 | case 3：第三张真实 enqueue+finish success → 再次真实删除可提交（计数/回收站累计/终态窗口 slot0=第三张）+ 全程 token-身份完整性 |
