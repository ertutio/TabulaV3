# T06 PR#74 独立审计（2026-11-12，静态核验+CI 证据，未跑模拟器）

## 总评：**可合并**。范围/断言/登记/CI 全部通过；无阻断与中级别问题，3 条低级别备注。
head e38a3c1（gh headRefOid 与本地 fetch 一致，未移动）；base combination/2026-11-10。
1. **范围与 head=通过**：diff 恰 4 文件（CardLifecycleDeviceTest.kt 新增 / PreloadingManager.kt / notes-T6.md / run-cloud-ui-probe.sh）。PreloadingManager 生产 delta 恰 2 行 `if (BuildConfig.DEBUG) Log.d`，均置于 isPaused.set 之前、无语义影响（+import+注释已登记）。
2. **场景 1 断言=通过（真实）**：ACTION_CANCEL 经 UiAutomation.injectInputEvent 同步注入 DOWN→MOVE×6(120px)→CANCEL，每步返回值 assertTrue——登记可靠。生产 onDragCancel（CardStack.kt L1509-1520 shuffle / L3211-3223 draw）resume() 先于 dragCancel 日志、同一同步 handler——`resume.ts ≤ dragCancel.ts` 确证 #61 恢复发生在取消处理内而非下次 dragEnd（另断言 pause≤resume；三元组按单候选 epoch mark 窗口取值，无跨尝试误配）。MediaStore 真值=行存在+RELATIVE_PATH 行数恒定（删除后/恢复后双断言）=软删除契约锚点；回收站恢复走真实 UI 入口、以 RecycleBinManager 记录清除数据真值锚定。无恒真、无吞失败（截止轮询 error() 留证，留证 catch 不掩盖失败）。
3. **场景 2 断言=通过（真实）**：Settings UI 真驱动（设置→显示设置子页→"轻掠模式"→backUntilDeck，deckActive 含 overlay 负向判定+桌面逃逸恢复）；生效信号=生产日志模式串 `dragCancel draw`（切换未会话内生效则必然失败）；DRAW 全循环+收尾操作性滑。诊断允许二选一择廉价，实现选更强的 UI 驱动并登记——如实。
4. **已知边界登记=如实**：恢复后镜像重进未断言（KDoc/notes-T6/PR 三处一致，phaseRestoreViaBin 确无此断言）；swipe 软删除契约锚点三处一致（行数恒定=契约变更警报）。
5. **CI 证据=通过（备注 A）**：run 43086693791 三 job（build/device36/device37）全绿；日志实证本套件双 API `OK (3 tests)`（19:33:28.57/19:33:29.47 紧跟该类 instrumentation；ui-failures COLLECTION rc=1 为无失败无目录的非门禁噪音）。API36 FULL recovery 间歇 flake 定性成立：轮 4 run 43082882514 红点实证 `FULL recovery timed out waiting for 11月`（API37 同轮绿、门禁轮全绿），与基线红点不同环节；本 PR delta 不触及该路径。附证：基线 gate run 43065284177 双 API job 红于 ProdAuthCallbackDeviceTest（confirm 例双 API+cancel 例 API36）——本 PR 将其移出 probe 为证据支撑的重摘除，注释块完整记录 3 轮红，非隐瞒。
6. **probe 第 7 类=通过**：expected_tests=3 与 @Test 实数 3（SHUFFLE/DRAW/场景2）一致，CI OK(3) 双 API 印证。

## 问题分级
- 阻断：无。中：无。
- 低-1（备注 A）：门禁 run 绑定 a97f9c7（head~1），e38a3c1 仅 docs/notes-T6.md（29+/5-）——受测代码内容与 head 等价，可接受；如需严格绑定可复跑。
- 低-2：probe 变更实际含 ProdAuthCallbackDeviceTest 摘除（base 曾恢复登记），PR 描述"摘除态口径维持"措辞偏轻——但 diff 注释块完整披露且基线 run 双 API 红实证支撑，判定如实。
- 低-3：phaseRestoreViaBin 的 UI 条件为 OR 析取（计数‖空态‖条目消失），弱于纯数据真值——有 recordGone 数据锚定，可接受。

## 修正增量复审 88a591b（2026-11-12，独立新会话，静态核验+CI 证据+复刻实跑）

总评：**PASS**。两 P2 修正真实有效、既有断言零删除、CI 绑定本套件代码提交；增量恰 2 文件（+253/−26），0eb0ab5 仅 docs 补 run id（同原低-1 口径，可接受）。
1. **P2-1=通过**：全部 4 处日志匹配点（waitForLog L485、horizontalSwipeUntilDragEnd L548、phaseCancel L614、phaseSwipeDelete L671 及其 pause/resume 经 waitForLog）统一 isWithin=`ts≥mark && ts≥logCursor`；游标在 phaseCancel/phaseSwipeDelete/horizontalSwipeUntilDragEnd 入口单调推进，mark 逐候选注入前精确采样不回退——前序阶段日志 ts<游标、前序候选 ts<mark，按构造排除，跨阶段污染不可能（describeSince 仅诊断输出不过滤，不影响匹配）。
2. **生产顺序=通过**：CardStack.kt@88a591b 实测 onDragStart pause L1154/L2831；onDragEnd 先 dragEnd 日志（L1424-1427/L3118-3122）后 resume（L1431/L3125）；onDragCancel 先 resume（L1511/L3213）后 dragCancel 日志（L1513/L3215）——新增三元组 pause≤dragEnd≤resume 与生产一致；resume() 全仓恰 4 处，与 notes 一致。
3. **P2-2=通过**：backUntilDeck(allowRelaunch=false) 于 root 空/非本包时留证（saveEvidence+session 报告 txt）并 error、无 am start；armSessionWatch 挂 targetContext(Application) 生命周期回调+pidof 采样；assertSessionContinuity 三重信号（pid 不变+destroy 集空+持有 record 未 destroyed/finishing）缺一即失败——"退出后重启"两道闸封死；回收站路径 backUntilDeck() 默认重入保留（L767），范围符合登记。
4. **变异复刻=通过（备注 B）**：scratch-T6-mutation/P2MutationCheck.java 经本复审实际编译执行（javac -encoding UTF-8）——ALL EXPECTATIONS MET：S1 旧+变异假阳性（误配 resume@100.15）、S2 新+变异转红（resume 等待超时 since=100.6 cursor=100.5）、S3/S4/S0 本次手势归属与生产顺序成立；解析正则与测试逐字符一致，轮询/超时/谓词同构，合成时间线与第 2 项独立核实的生产顺序对应。局限如实：属实施者 JVM 合成模拟而非设备级变异红点（本地无 emulator 已登记），resume 调用点普查（恰 4 处）使"必然超时"论证成立，可信。
5. **既有断言=通过**：diff 删除 23 行均为注释/签名/谓词替换（2 行裸 waitForLog 改赋值）——ACTION_CANCEL 注入、软删除三真值（回收站恰 1 记录/待整理计数递减/MediaStore 行存在+行数恒定）、回收站 UI 恢复、@After 哨兵校验、@Test=3 全部未动。
6. **CI=通过**：run 43248972561 headSha=88a591be 三 job success（build/device36/device37）；本套件双 API `OK (3 tests)`（15:43:03.9 API36 / 15:43:04.8 API37，紧跟该类 instrumentation 行）；ui-failures COLLECTION rc=1 维持非门禁噪音定性。
- 备注（低）：①isWithin 依赖 logcat -v epoch 与 System.currentTimeMillis 同钟（CLOCK_REALTIME），同毫秒碰撞理论上可漏排前序日志，实际前序日志与阶段入口至少隔一个轮询间隔，可忽略；②armSessionWatch 回调未注销（测试实例引用滞留 Application），断言状态按方法新实例隔离，无正确性影响。
