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
