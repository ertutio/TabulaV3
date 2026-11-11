# T06 设备级生命周期验收——缺口诊断（2026-11-11，主代理核心诊断，排队待 B2/T13 批次后实施）

## 五场景 × 可观察信号 × 现状

| 场景 | 现有覆盖 | 设备级缺口 | 可观察信号 |
| --- | --- | --- | --- |
| 1 拖动取消→恢复预载/操作（两模式） | #61 生产修复（onDragCancel resume+setFastMode）；单测无接线层；**设备测试缺失** | **真缺口**：真实手势注入（含 ACTION_CANCEL）后恢复行为 | CardStackDebug `dragCancel shuffle/draw idx=…`（DEBUG 构建生效，设备测试上下文可用）；后续 swipe 的 MediaStore 真值 |
| 2 动画中切模式/离页/换批→返回可操作 | reducer 级（DrawCardStateLifecycleTest Reset/AnimationComplete）；接线层无 | **真缺口（模式切换部分）**：切换经 prefs StateFlow→Settings 驱动可会话内生效（无需重启）；离页/返回经 BackHandler+重组 | 模式区分日志（`dragCancel shuffle` vs `dragCancel draw` 字符串自带模式）；切换后新模式 swipe 真值 |
| 3 同索引换批请求身份 | PreloadingManagerTest 版本 token+PR62 单次调用——**单测已覆盖**；设备侧同索引换批隐式发生于每次删除 | 弱缺口（可选补充）：删除后同位卡片显示新内容（displayName/像素） | 查看器 displayName+identity（PartialSelection 模式） |
| 4 预载取消/失败同中心重试 | PreloadingManagerTest 已覆盖（排队/失败分离+重试）；失败日志存在（PreloadingManager.kt:117） | 无真缺口（设备补充可选，信号弱） | PreloadingManager 失败日志（仅失败有） |
| 5 旧请求晚到身份保持 | PR50 竞争门控（单测）+PixelRefreshUiTest（设备） | 无真缺口 | 既有 |

## 结论与实施设计（排队）
- **真缺口=场景 1+2 的设备级接线验收**（一个新 androidTest 套件 `CardLifecycleDeviceTest`）：
  - 手势注入：UiAutomation `injectInputEvent` DOWN/MOVE…/**ACTION_CANCEL**（拖动中取消）→ 断言 dragCancel 日志（两模式各一）→ 后续真实 swipe 成功（MediaStore 计数真值）。
  - 模式切换：驱动 Settings 卡片样式 toggle（或 prefs 注入+重启对齐 #57 口径——选实现代价小者并登记）→ 返回 deck → 两模式各完成一次 swipe+cancel 循环。
  - 断言纪律：截止轮询/失败留证（截图+树 dump，文件名无 `|`）/哨兵。
- **最小生产增补（可观测性）**：PreloadingManager.resume()/pause() 各加一行 DEBUG 门 Log.d（与 debugCardStackLog 同模式）——使场景 1 的"恢复预载"可直接断言；无行为变化。
- 覆盖口径：单测（既有 22+8+…）与设备套件分别记录；不以设备套件宣称 reducer 级覆盖，反之亦然。
