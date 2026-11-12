# T06 设备级生命周期验收 notes（2026-11-12）

任务：按已定稿诊断（coord/t06-device-gap-diagnosis.md）补齐场景 1+2 的设备级接线验收——
新 androidTest 套件 `CardLifecycleDeviceTest`（3 例）+ 最小生产增补（PreloadingManager
pause()/resume() 各一行 DEBUG 门 Log.d）+ probe 第 7 类登记。

## 基线

- 分支 `test/card-lifecycle-2026-11-12`，基 **combination/2026-11-10 caafb96**
  （含 #61 onDragCancel resume+setFastMode 接线、#62 N2 finally 复位——开工时按指令
  先基于 master 488bd21，核实该提交不含 #61/#62（两 PR 当时仍 OPEN）后经协调改基
  caafb96；L1511/L3213 的 resume() 在位经代码核验）。
- 提交：054c499（test: T06 card lifecycle device acceptance...）。

## 设计与实现选择（登记，含各轮实证修正）

| 决策点 | 选择 | 理由 / 备选 |
| --- | --- | --- |
| ACTION_CANCEL 注入 | UiAutomation.injectInputEvent 显式 MotionEvent 序列 DOWN → MOVE×6（120px，越 touch slop 确保拖动已启动）→ ACTION_CANCEL | shell `input` 不支持取消语义，无备选可达；MOVE 步幅 20px/步、间隔 60ms 保证 Compose 帧驱动下越过 slop |
| 真实 swipe | shell `input swipe`（时长 450ms、位移 600px） | 真实系统手势通道；位移 600px ≫ 删除阈值（屏高 8%≈192px），不依赖速度阈 |
| 手势原点 | **信号驱动逐候选**（全部可见夹具节点按面积降序；cancel 以 pause 日志为信号、delete 以 dragEnd 日志为信号、非破坏 swipe 以 dragEnd 为信号；未命中候选无副作用） | 轮 1/2 实证：交互卡身份不可由节点几何/名字推断——推荐批序漂移（实测初始批 [129,128,130] 交互卡=idx0）+ 摸牌样式侧缘窄条节点（96px 宽） |
| 被删卡名 | **观察式**：删除后从回收站记录反推（恰一条夹具记录） | 同上；不预设哪张被删 |
| deck 移除真值 | 状态计数递减（"N 张待整理"）+ 回收站记录 | 轮 1 API37 实证：被删卡节点在删除动画后**残留显示**，节点消失轮询不可靠 |
| deck 激活判定 | 标记存在 **且无 overlay 特征节点**（回收站空态/计数/设置区标题） | 轮 3 API36 实证：RECYCLE_BIN/SETTINGS 以 deck 为背景层，背景标记在树中恒可见，仅凭标记会把前台 overlay 误判为已回 deck |
| 场景 1 DRAW 模式进入 | prefs 预置（AppPreferences.swipeStyle=DRAW 后启动） | 任务允许二选一；会话内切换本身由场景 2 专测（Settings UI 驱动），两途径均有覆盖 |
| 场景 2 模式切换 | Settings UI 驱动：deck 顶栏"设置"→**"显示设置"子页**→"切换样式"区"轻掠模式"→逐级返回（deckActive 判定） | 轮 1 实证："切换样式"区在 DisplaySettingsScreen 子页，主设置列表不含；诊断口径：切换经 StateFlow 会话内生效；生效信号=`dragCancel draw` 模式串 |
| 返回导航 | 每步返回后有界等待 deckActive（3s）+ 桌面逃逸恢复（am start 重入）——仅回收站恢复路径；**Settings 返回路径（场景 2）禁止重入**（allowRelaunch=false，逸出留证失败，P2-2） | 轮 2 实证：固定短睡导致双击返回退出应用至桌面（树=launcher）；审计 P2-2：静默重入使"退出后重启"冒充会话内切换 |
| 删除真值（MediaStore） | 行存在性 + 按 relative_path 圈定的行数恒定 + RecycleBinManager 记录 | 生产契约：deck swipe 删除=软删除（回收站记录，物理删除仅经清空/对账通道）；若生产改为 swipe 即物理删除，此断言即契约变更警报 |
| 恢复验证 | 回收站 UI 逐条恢复 → RecycleBinManager 记录清除（数据真值）+ UI 空态/计数文本 + MediaStore 行保留 + deckActive | 轮 2 实证：回收站**空态无计数文本**（"回收站空空如也"），UI 条件需含空态分支 |
| 恢复边界 | 恢复后镜像重新进入当前 deck 批次不在断言范围 | deck 批次同步（C-1 syncRowsToSnapshot）只剪不增，恢复行经下一批派生进入（DeckScreen L731 effect 实证） |
| 时序要点 | 撤销 Snackbar（3000ms 超时=确认删除）后才提交 onRemove → 回收站记录——轮询先等 3.6s 提交窗口再查记录 | DeckScreen onRemove 延迟提交（isDeletePending/lastDeletedImage）实证 |

## 可观察信号（断言链）

1. `PreloadingManager pause`（拖动开始，**T06 新增 DEBUG 门日志**；CardStack L1154/2826 pause()）
2. `PreloadingManager resume`（**#61 接线**：onDragCancel L1511/3213 resume()；**T06 新增 DEBUG 门日志**）
   — 断言 resume 时间戳 ≤ `dragCancel` 时间戳：恢复发生在取消处理内而非下次 dragEnd。
3. `CardStackDebug dragCancel shuffle|draw idx=…`（DEBUG 构建生效）
4. `CardStackDebug dragEnd shuffle|draw`（删除轮；drag end resume 接线 L1431/3125）
   — 收尾修正后删除阶段断言**本次手势**三元组 pause ≤ dragEnd ≤ resume（生产
   onDragEnd 先记 dragEnd 日志后 resume）。
5. MediaStore 真值（行存在 + 夹具目录行数恒定）；回收站记录（RecycleBinManager.loadRecycleBin）
6. UI 真值（无障碍树）：deck 大卡节点、回收站"项待处理"、"恢复"、deck"张待整理"状态标记
7. 会话连续性真值（**收尾新增，P2-2**）：pidof pid 前后一致 + Application 生命周期
   onDestroy 事件集为空 + 持有 Activity record 未 destroyed/finishing——场景 2
   Settings 返回路径专用。

## 断言纪律

- 全部等待有截止时间轮询；失败留证：截图 + 树 dump + logcat 切片写入
  `cloud-ui-failures/card-lifecycle-<tag>.(png|tree.txt|logcat.txt)`（文件名无 `|`）；
  CI trap 拉取为 artifact。
- 夹具：每例 3 张合成媒体（shell 绑定所有权，CloudFixtureCleanup 清单 + @After 哨兵
  校验无关哨兵未被误删——沿用 CloudRestoreUiTest 模式）。
- 覆盖口径：本套件只宣称设备级接线验收；reducer 级覆盖仍归 DrawCardStateLifecycleTest
  等单测，互不冒称（诊断口径）。
- 预期 case 数：3（probe 登记 expected_tests=3）。

## 收尾微单元（2026-11-12，审计 audit-T06-pr74 两 P2 修正）

范围：仅 CardLifecycleDeviceTest.kt（+本 notes）；生产/单测/probe 不动。

### P2-1 删除阶段日志串用（跨阶段污染）

- 问题：`epochNow()` 回退 1s 作时钟余量；`phaseSwipeDelete()` 紧接 `phaseCancel()`
  且尾部断言耗时可低至亚秒——mark 窗口可能覆盖上一阶段的 pause/resume，删除阶段的
  pause/resume 断言可能命中前序阶段日志（时间游标回退导致跨阶段污染）。
- 修正（阶段标记 + 日志游标）：①阶段边界单调游标 `logCursor`（phaseCancel /
  phaseSwipeDelete / horizontalSwipeUntilDragEnd 入口各推进一次），所有断言匹配要求
  `ts >= mark && ts >= cursor`（统一谓词 `LogLine.isWithin`，waitForLog 内联同口径）；
  ②手势标记 `logMark()` 改为注入前**精确采样、不回退**——与 `logcat -v epoch` 同一
  墙钟（CLOCK_REALTIME），采样先于注入 ⇒ 本次手势日志必然 ts > 标记，1s 余量无必要；
  ③候选重试内逐候选重新采样标记，前序候选日志被排除。
- 生产顺序核对（真实顺序，断言对齐）：onDragCancel 内 `resume()` 先于 `dragCancel`
  日志（CardStack L1511→L1513 / L3213→L3215，同一同步 handler）——取消三元组断言
  resume ≤ dragCancel、pause ≤ resume 维持并补引用；onDragEnd 先记 `dragEnd` 日志后
  `resume()`（L1424-1431 / L3118-3125；onDragStart pause 在 L1154 / L2831）——删除
  阶段**新增**本次手势三元组断言 pause ≤ dragEnd ≤ resume。
- 反例变异验证（必须项）：**临时移除删除手势的 resume 接线**（CardStack.kt
  L1431/L3125 的 `preloadingManager.resume()`）时，目标断言必须失败。
  - 本机 SDK 无 emulator/system-images（仅 build-tools/platforms），设备级执行不可
    本地完成；按"隔离验证环境"以 JVM 单文件忠实复刻测试的日志匹配管线
    （同一 LogLine 解析正则、dumpLogcat 时间序、waitForLog 轮询/20s 超时语义、
    新旧两种谓词）在合成时间线上验证（scratch-T6-mutation/P2MutationCheck.java，
    仓库外、不进提交）：
    S1 旧方案+变异 → 误配 phaseCancel 的 resume（100.15）→ phaseSwipeDelete 假阳性
    通过（P2-1 污染复现）；S2 新方案+变异 → 本次手势窗口内 resume 缺失 →
    waitForLog 超时 error（"log not observed ... since=100.6 cursor=100.5"）→ 转红
    （变异精确命中：pause 接线完好仍命中本次手势）；S3/S4 新方案+无变异 → 三元组
    pause=100.62/dragEnd=100.70/resume=100.71 满足生产顺序且归属本次手势
    （前序 100.10/100.15 被游标排除）；S0 取消三元组维持。全预期满足。
  - 断言失败路径的生产代码佐证：resume() 全仓仅 4 处（CardStack L1431/L3125 onDragEnd、
    L1511/L3213 onDragCancel）——变异后删除手势到 waitForLog 之间无其它 resume 来源，
    必然超时。变异本体不落工作区（复现于隔离副本），`git diff` 仅 CardLifecycleDeviceTest.kt
    即源码干净之核对；设备级红点由本次 CI 轮实测背书（修复后全绿 ⇒ 变异差异被
    本次手势窗口真实区分）。

### P2-2 会话内切模式连续性（假阳性封堵）

- 问题：`backUntilDeck()` 遇桌面/空窗口会 `am start` 重入——应用意外退出后重启亦能
  通过场景 2 的会话内切换验收。
- 修正：①`backUntilDeck(allowRelaunch)` 参数——场景 2 Settings 返回路径以
  `allowRelaunch=false` 调用：每步返回后的有界过渡等待（3s settle ×5 步 + 终态
  12s）不变，但应用逸出前台即保存截图 + 窗口树 + 生命周期报告并失败（禁止静默
  重入）；回收站恢复路径（phaseRestoreViaBin）沿用默认重入行为，不动。②启动后
  `armSessionWatch()` 布防：pidof 采样 + Application registerActivityLifecycleCallbacks
  收集 onDestroy；返回 deck 后 `assertSessionContinuity("after-mode-switch-back")`：
  同一实例证据 = pid 不变 + destroy 事件集为空 + 持有 Activity record 未
  destroyed/finishing（窗口树/进程证据多重可靠信号）；意外退出 → 会话报告
  `card-lifecycle-session-*.txt` + 截图/树/logcat 留证 + 失败。③切回 deck 后新模式
  生效验证不变：真实手势 `dragCancel draw`（phaseCancel）+ 可观察状态变化（删除
  使"张待整理"计数递减）+ 最终 `dragEnd draw` 操作性滑。

### 收尾定点验证

- 本地：`compileDebugAndroidTestKotlin` BUILD SUCCESSFUL（2026-11-12，
  e38a3c18 + 本修正）。
- CI：**轮 6 run 43248972561**（head=88a591b，本套件代码提交）全绿——build /
  device36 / device37 三 job success，本套件双 API `OK (3 tests)`
  （15:43:03/15:43:04；ui-failures COLLECTION rc=1 为无失败无目录的非门禁噪音）。

## 验证链（按轮记录）

- 本地：`compileDebugAndroidTestKotlin` BUILD SUCCESSFUL；
  `testReleaseUnitTest --tests PreloadingManagerTest` BUILD SUCCESSFUL（生产增补无行为
  变化，Robolectric 下 Log 可用）。
- CI（build-volume-confirmation.yml @ test/card-lifecycle-2026-11-12）：
  - **轮 1 run 43067990321**：双 API 红。API36 场景 1 两例已绿，红点=场景 2 设置
    主列表找不到"轻掠模式"；API37 红点=场景 1 shuffle 回收站记录缺失。诊断（工件
    截图+树+logcat）：①推荐批序漂移使"最大节点=交互卡"不成立（初始批 [129,128,130]
    交互卡=idx0=129，手势删了 129 测试却轮询 128 的记录；被删卡节点动画后残留显示）；
    ②"切换样式"区在 DisplaySettingsScreen 子页（主列表没有）。
  - **轮 2 run 43074941361**：双 API 红 3/3。三发现：①DRAW 取消无 pause 日志——
    原点落在摸牌样式侧缘窄条（96px 宽），交互卡=中央大卡，原点启发式按运行漂移；
    ②回收站空态无"0 项待处理"计数文本（"回收站空空如也"）；③backUntilDeck 固定
    短睡导致双击返回逸出应用至桌面（树=launcher）。
  - **轮 3 run 43079372206**：双 API 2/3 绿（场景 1 两模式全绿），场景 2 红于最终
    操作性滑——树显示 deck 与回收站前台同屏（overlay 结构，背景 deck 的状态标记
    恒可见），backUntilDeck 把回收站前台误判为已回 deck。
  - **轮 4 run 43082882514**：**CardLifecycleDeviceTest 3/3 双 API 全绿**
    （deckActive overlay 判定修复后）。API36 job 仍红但红点在**既有 FULL recovery
    四阶段的 continuation/readback**（UI 等待超时 "timed out waiting for 11月"）：
    同代码轮 1/3 该阶段 API36 全过、轮 4 API37 全过、基线 combination/2026-11-10
    自身的 run 43065284177 亦红于另一既有环节（ProdAuthCallback 摘除态）——判定为
    API36 既有间歇性 flake，与本套件/生产改动无关。
  - **轮 5 run 43086693791**：复跑全绿（build/device36/device37 三 job；本套件双
    API `OK (3 tests)`，审计 audit-T06-pr74 实证）。

## 已知边界 / 不做的事

- 生产改动仅限 PreloadingManager 两行 DEBUG 门日志（+import），无行为变化；未动 app/src/test。
- 单文件单写者：本工作树独占（wg-T6）。
- 恢复镜像重进 deck 当前批次的行为未断言（批次同步只剪不增，见上表"恢复验证"行）。
- logcat 采集用 `-v epoch` 墙钟过滤（收尾修正：手势标记注入前精确采样不回退 +
  阶段游标排除前序事件，见"收尾微单元"P2-1）；不做 logcat -c 以外的缓冲假设。
- 场景 2 Settings 返回路径禁止 am start 静默重入（P2-2）；回收站恢复路径保留
  既有重入行为。
- 反例变异的本机限制：本地无 emulator，变异以管线复现（JVM）验证断言失败路径，
  设备级执行归 CI（见"收尾微单元"P2-1 反例变异验证）。
