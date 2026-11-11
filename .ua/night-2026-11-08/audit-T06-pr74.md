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
