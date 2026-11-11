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
| 返回导航 | 每步返回后有界等待 deckActive（3s）+ 桌面逃逸恢复（am start 重入） | 轮 2 实证：固定短睡导致双击返回退出应用至桌面（树=launcher） |
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
5. MediaStore 真值（行存在 + 夹具目录行数恒定）；回收站记录（RecycleBinManager.loadRecycleBin）
6. UI 真值（无障碍树）：deck 大卡节点、回收站"项待处理"、"恢复"、deck"张待整理"状态标记

## 断言纪律

- 全部等待有截止时间轮询；失败留证：截图 + 树 dump + logcat 切片写入
  `cloud-ui-failures/card-lifecycle-<tag>.(png|tree.txt|logcat.txt)`（文件名无 `|`）；
  CI trap 拉取为 artifact。
- 夹具：每例 3 张合成媒体（shell 绑定所有权，CloudFixtureCleanup 清单 + @After 哨兵
  校验无关哨兵未被误删——沿用 CloudRestoreUiTest 模式）。
- 覆盖口径：本套件只宣称设备级接线验收；reducer 级覆盖仍归 DrawCardStateLifecycleTest
  等单测，互不冒称（诊断口径）。
- 预期 case 数：3（probe 登记 expected_tests=3）。

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
  - **轮 5 run 43086693791**：复跑（结果待补）。

## 已知边界 / 不做的事

- 生产改动仅限 PreloadingManager 两行 DEBUG 门日志（+import），无行为变化；未动 app/src/test。
- 单文件单写者：本工作树独占（wg-T6）。
- 恢复镜像重进 deck 当前批次的行为未断言（批次同步只剪不增，见上表"恢复验证"行）。
- logcat 采集用 `-v epoch` 墙钟过滤（标记时刻-1s 余量）；不做 logcat -c 以外的缓冲假设。
