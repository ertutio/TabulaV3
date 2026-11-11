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

## 设计与实现选择（登记）

| 决策点 | 选择 | 理由 / 备选 |
| --- | --- | --- |
| ACTION_CANCEL 注入 | UiAutomation.injectInputEvent 显式 MotionEvent 序列 DOWN → MOVE×6（120px，越 touch slop 确保拖动已启动）→ ACTION_CANCEL | shell `input` 不支持取消语义，无备选可达；MOVE 步幅 20px/步、间隔 60ms 保证 Compose 帧驱动下越过 slop |
| 真实 swipe | shell `input swipe`（时长 450ms、位移 600px） | 真实系统手势通道；位移 600px ≫ 删除阈值（屏高 8%≈192px），不依赖速度阈 |
| 场景 1 DRAW 模式进入 | prefs 预置（AppPreferences.swipeStyle=DRAW 后启动） | 任务允许二选一；会话内切换本身由场景 2 专测（Settings UI 驱动），两途径均有覆盖 |
| 场景 2 模式切换 | Settings UI 驱动（deck 顶栏"设置"→"切换样式"区"轻掠模式"→返回），离页/返回经 BackHandler+重组 | 诊断口径：切换经 StateFlow 会话内生效无需重启；同时覆盖"离页/返回可操作"维度；切换生效的既定信号=日志模式串（`dragCancel draw`） |
| 删除真值 | MediaStore 行存在性 + 按 relative_path 圈定的行数恒定 + deck 节点消失 + RecycleBinManager 记录 | 生产契约：deck swipe 删除=软删除（回收站记录，物理删除仅经清空/对账通道）；若生产改为 swipe 即物理删除，此断言即契约变更警报 |
| 恢复验证 | 回收站 UI 逐条恢复 → RecycleBinManager 记录清除 + UI 条目消失 + MediaStore 行保留 + deck 状态标记可见 | 恢复后镜像重新进入当前 deck 批次不在断言范围：deck 批次同步（C-1 syncRowsToSnapshot）只剪不增，恢复行经下一批派生进入（DeckScreen L731 effect 实证），登记为边界 |
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
  - 轮 1：run 43067990321（结果待补）。

## 已知边界 / 不做的事

- 生产改动仅限 PreloadingManager 两行 DEBUG 门日志（+import），无行为变化；未动 app/src/test。
- 单文件单写者：本工作树独占（wg-T6）。
- 恢复镜像重进 deck 当前批次的行为未断言（批次同步只剪不增，见上表"恢复验证"行）。
- logcat 采集用 `-v epoch` 墙钟过滤（标记时刻-1s 余量）；不做 logcat -c 以外的缓冲假设。
