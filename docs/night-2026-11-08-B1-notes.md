# 包B（B1：PARTIAL 选择集 A→B 宿主驱动设备验收）进度笔记

日期：2026-11-08。工作区 wg-B，分支 `test/partial-selection-ab-2026-11-08`（基于 master 43a8da1）。

## 1. 关键研究结论（PARTIAL 真实选择集的可行路径）

### 真机只读探针（PLR110，API 37 / Android 17，仅读+弹窗关闭，未确认任何选择、未装任何应用）
- `am start -a android.provider.action.PICK_IMAGES --ei android.provider.extra.PICK_IMAGES_MAX_LIMIT 8` 可启动系统照片选择器。
- 选择器网格项 = **可点击节点，带 content-desc"照片拍摄于 <日期时间>"**，按时间倒序（最新在前）。
- 选择器存在"仅可访问所选的照片和视频"横幅（部分授权语境）。
- 结论：选择器 UI 对 UiAutomation 完全可驱动（节点可枚举、可按序点选）。
- 探针后已 KEYCODE_BACK 关闭并删除 dump 文件，无状态残留。

### 文档依据（developer.android.com partial-photo-video-access）
- 应用添加媒体无需权限（API 29+）→ **无授权状态下可先种子合成照片**，再走"选择照片"。
- 重选路径：应用再次发起授权请求/照片选择器 → 系统对话框允许用户**重新选择**（Figure 2 reselect），选择集由用户确认集合决定。
- QUERY_ARG_LATEST_SELECTION_ONLY（API 15+ 扩展）可查"最近一次选择"（备用地面真值，不必需）。
- 文档未载明 pm grant READ_MEDIA_VISUAL_USER_SELECTED 语义 → 不依赖。

### 候选路径判定
- **(a) 真实系统 UI 驱动（采纳）**：阶段A走应用 onboarding 门控按钮 → 系统权限对话框"Select photos" → 照片选择器选 A → 确认 → READ_MEDIA_VISUAL_USER_SELECTED 授权；阶段B经 ACTION_PICK_IMAGES（调用方=应用包名）重选为 B。全程真实系统机制，无 pm 权限夹具。
- (b) `pm grant READ_MEDIA_VISUAL_USER_SELECTED`：语义未验证（可能=空选择集），不作为 A→B 手段，仅登记。
- (c) shell/dumpsys：无已知程序化改选择集入口；不臆断。
- **不确定点（CI 实证裁决）**：ACTION_PICK_IMAGES 确认后是否**更新**持久 user-selected 集合（替换语义）。测试用"地面真值断言"（应用内 MediaStore 查询必须恰等于新集合）裁决——若机制不符，红灯留证据，回退方案=系统设置页（appinfo → Photos and videos → Select photos/more）驱动（已写明，未实现）。

## 2. 设计（先行登记）

### 阶段划分（借鉴 PR52：独立阶段 + 文件持久状态 + shell 编排）
| 阶段 | 类 | 入口条件 | 内容 |
| --- | --- | --- | --- |
| A | PartialSelectionPhaseATest（1例） | 宿主 pm clear、无任何授权、图库已预清理 | 无授权种子 5 张（哨兵S最旧、A1/A2、B1/B2最新，DATE_TAKEN 单调递增=发布顺序，选择器排序确定性）→ 启动应用→onboarding 门控→系统对话框→Select photos→选 2 格(=A)→确认→轮询授权+地面真值(查询恰={A1,A2})→PARTIAL 级别断言→种子清理组(复用 FullRecoveryStateContract.seedCleanupGroup)+断点1+flush→UI 断言：卡片A可见/哨兵不可见/查看器 overlay/月份列表(经真实 sheet)成员=A→写预期 JSON(files/) |
| 宿主 | probe 脚本 | — | force-stop（不 pm clear，保留应用数据） |
| B | PartialSelectionPhaseBTest（1例） | PARTIAL(A) + 应用数据保留 + 新进程 | 预期 JSON 读回+pid 不同（保留性断言：组/断点/onboarding 原样、未永久完成）→ ACTION_PICK_IMAGES 重选（自适应勾选态检测：先取消 A 勾选，再勾选 B 两格）→确认→轮询地面真值恰={B1,B2}→UI：卡片仅B、A+S不可见、查看器 overlay=B 身份、月份列表经生产 sheet 重建=B 成员→prefs 保留断言（A 组成员键原样、断点原样、未永久完成、无 acknowledged）→哨兵 shell 查询存在+进程内查询不含→写回 phaseBPid |

### 状态文件格式（PartialSelectionStateContract，files/，跨进程死亡持久）
```json
{ "albumId", "groupId", "checkpointIndex":1, "phaseAPid", "phaseBPid",
  "members":[A键], "displayNames":[A名], "sentinelName", "sentinelPath",
  "bDisplayNames":[B名], "monthAttribution":[{identityKey,year,month}],
  "bMembers":[B键] }
```

### 哨兵策略
- 哨兵 S 最先种子（DATE_TAKEN 最旧，网格最后一格），**任何阶段都不进入选择集**。
- 断言：PARTIAL 下进程内查询不含 S；deck 不显示 S；月份 UI 不含 S；shell `content query` 确认行存在（集合外媒体不受影响）；结束清理时按名+路径精确删除。

### 网格单元映射与地面真值
- 选择器排序按时间倒序（真机实证）+ DATE_TAKEN 单调种子 → cells=[B2,B1,A2,A1,S]。
- 每格 desc/isSelected/勾选子节点全量进 logcat（失败可裁决）。
- **一切以地面真值断言收口**：授权后应用内查询 displayNames 必须恰等于预期集合（不等=诚红）。

### 断言纪律
仅中心稳定节点+截止时间轮询（沿用 PixelRefreshUiTest/FullRecoveryUi 模式）；无生产改动；失败时截图+辅助树 dump 写 cloud-ui-failures（CI artifact 拉取）；@After 按名+路径精确 shell 清理（CloudFixtureCleanup 模式）；探针门禁 allowSyntheticMediaProbe+ranchu/goldfish。

### 预期 case 数量
PartialSelectionPhaseATest=1、PartialSelectionPhaseBTest=1；probe 脚本每阶段门 `^OK \(1 tests?\)`。CI 数量门 +2。

### 风险与回退
- PICK_IMAGES 更新语义不符 → 回退 Settings 页驱动（登记，未实现）；或权限对话框 Select more 路径（需 NONE→PARTIAL 中转，B2 范围）。
- 对话框/选择器文案随 API 版本差异 → 候选文案列表匹配（"Select photos"/"Select photos and videos"/"Allow limited access"/"Add"等），失败截图+树 dump 定位。
- 图库残留夹具 → 阶段A前 shell 按 relative_path 精确预清理已知合成夹具目录 + 图库计数核对，非零即失败（CI 专用模拟器，无真实用户照片）。

## 3. 执行记录
- 本地真机只读探针：完成（见上）。
- 实现：app/src/androidTest 新增 PartialSelectionStateContract.kt / PartialSelectionUi.kt / PartialSelectionPhaseATest.kt / PartialSelectionPhaseBTest.kt；tools/run-cloud-ui-probe.sh 接线（LF；提交 blob 复核仅含既有 FullRecovery 段历史 CR，与 master 一致）。
- 编译验证：`:app:compileDebugAndroidTestKotlin` BUILD SUCCESSFUL（本地实跑）。
- 提交：`1f83100`（分支 test/partial-selection-ab-2026-11-08，基于 43a8da1；5 文件 +1053）。
- 实现中修正的实现缺陷（本地编译实抓）：API 36 平台已移除 MediaStore.EXTRA_PICK_IMAGES_MAX_LIMIT（更名 EXTRA_PICK_IMAGES_MAX，值 android.provider.extra.PICK_IMAGES_MAX）——按新常量实现并登记。
- 设计要点（实现中固化）：种子间隔 >1s 使 DATE_TAKEN/DATE_ADDED 双排序键一致（网格顺序与列数无关地确定）；勾选态检测=selected/checkable/AOSP 数字角标三重；月份清理视图无 BACK 出口 → 月份流程为各阶段最后 UI 步（deck/查看器 BACK 经既有 BackHandler 正常返回）；引擎对"成员缺席组"持有不标完成（生产代码核实），保留断言与生产语义一致。
- PR/run：待做。

## 4. 失败证据（CI 迭代）
- **轮1**：PR #57，run 42468075623（head 1f83100）build 绿 + API36/37 设备红。失败=**套件实例化校验**：@Test 方法以 `Log.i`（返回 Int）收尾 → runBlocking 推断 Int → JUnit "should be void" → initializationError（PhaseA/PhaseB 同因）。**环境面全过**：图库预清理计数门=0 行通过；前置既有套件 CloudCleanup/CloudRestore/CreateAlbum/ForegroundPermission(OK(2))/PixelRefresh(OK(2))/FullRecovery 四阶段(readback OK(1))全部通过。修正：`runBlocking<Unit>`（21fb7e7，与 FullRecoveryReadbackTest 同款）。属测试基建机械失败，非断言失败。
- **轮2**：run 42469308043（head 21fb7e7）双 API 设备红。**决定性证据（失败截图+树 dump，双 API 一致）**：①对话框 Select photos → 选择器 sheet 打开成功、5 格种子齐全、**网格视觉顺序=预期 [B2(cyan),B1(yellow),A2(green),A1(red),S(gray)]**（seed 排序机制验证成立）；②选中角标=子节点 `content-desc="Selected"`（API36/37 同构）——原检测只查节点自身 → 全程失明 → 盲 tap 振荡 58 轮；③**sheet 存在系统级预选态**：终态角标在 {B2,B1,S}（count=3），与 tapped idx{2,3} 无交集且坐标 tap 58 轮边界恒定——坐标 tap 与 a11y 边界存在系统级错位；④DATE_TAKEN 被发布扫描重置为发布时刻（desc 全显 "10:10 AM"）→ 排序键=发布时间 DESC，仍确定性（种子 1.1s 间隔保证秒级互异）。修正（493a4f5）：tapCell 优先节点 ACTION_CLICK（坐标无关）；勾选收敛=节点勾选态+底栏计数双口径、每轮差集矫正≤3 轮、不收敛即失败留证。PhaseB 本轮为级联失败（PARTIAL 未建立，前置断言正确拦截）。
- **轮3**：run 42471835967（head 493a4f5）API37 红：对话框 "Allow limited access"（API37 文案，已在候选表）坐标 tap 被吞没（对话框未推进，与轮2 同 tap 成功形成对照——input tap 递送不稳定）。修正（7d4829a）：全部点击走节点 ACTION_CLICK（clickNode）；对话框动作推进核验+重试≤4 次。
- **轮4**：run 42473377747（head 7d4829a）API37 红：**对话框推进成功（attempt0 ACTION_CLICK）**、选择器打开即**全部 5 格预选（count=5）**——选择器把"应用已有权访问的照片"预勾选；scoped storage 下应用自有媒体无需任何权限即可见 → **应用自有夹具使选择集 A→B 对应用查询不可观测（设计缺陷，本轮实证）**。矫正点击实际生效但辅助树滞后真实 UI >600ms（失败截图角标恰在预期两格 count=2，树读数陈旧全选）→ 固定 600ms 单次读数造成差集重复矫正。修正（d0d8357）：夹具改 shell 写入（尝试）；勾选收敛改"树稳定等待"（连续两次读数一致才采信）。
- **轮5**：run 42476083194（head d0d8357）API37 红：单命令 ~5KB base64 shell 写文件输出为空（截断/吞没）。修正（1e583c0）：1.2KB 分块追加+/data/local/tmp 暂存+每步标记。
- **轮6**：run 42477634711（head 1e583c0）API37 红：分块写仍失败——错误信息回显即命令本体且空格剥离，**instrumentation 内 UiAutomation shell 不是可靠字节通道（机制级结论）**。修正（d7f726a，采纳协调者指引复用 C2 验证结论）：**夹具编排移至宿主 probe 脚本**——纯 python 生成 96x96 纯色 PNG（324B）→ adb push → content call scan_file → 逐张行核对（广播兜底）；行所有权=shell（非应用自有：C2 实证 shell 侧所有权绑定被 provider 接受）；固定夹具名；阶段A 前 shell 核对 5 行在场；授权前新增空集断言（选择集过滤语义前置实证）。
- **轮7**：run 42479680787（head d7f726a）API36/37 红：**阶段A 夹具核对失败**——进程内 `content query ... 2>&1` 返回 content CLI usage 错误（`2>&1` 被当作 content 的参数）。
- **轮8**：run 42481561828（head cd9df1d）API37 红（API36 同段）：IFS 通道修复后夹具核对通过、对话框推进、像素驱动选中；**abort 截图=角标恰在预期两格（A2 绿/A1 红）、count=2，而树读数恒为初始空态**——辅助树不反映勾选变化（机制级结论）。
- **轮9**：run 42484538892（head a101994a）**双 API 分叉**：API37 走到最深处（选择收敛/授权/地面真值/组+断点/deck）后 viewer-overlay 超时（API37 工件上传缺失）；API36 红在像素模型——**逐格采样证据：五格角部均为网格背景色 (141,155,187)**（未勾选渲染=内嵌圆角+背景填充，与 API37 相反；背景色恰近灰色哨兵 d=54）→ 瓷片色比对模型跨 API 不成立。修正（a7685b4）：白色勾选笔画判据 + viewer 重试。
- **轮10**：run 42486598092（head a7685b4e）**阶段A 双 API 全绿**（API36 job log 14:02:32 `OK (1 test)`；API37 同）——夹具宿主编排/对话框推进/像素选择/确认/授权/地面真值/组+断点持久化/deck/查看器（重试）/月份专清全链通过。**PhaseB 唯一卡点（双 API 一致）**：`重选确认后应用可见集合必须恰为 B（实际=[partial-a1.png, partial-a2.png] 预期=[partial-b1.png, partial-b2.png]）`——ACTION_PICK_IMAGES 重选确认后授权集未变。两 API 工件上传均缺失（平台瞬时故障，与此前下载失败同源）。

## 4.1 协调者检查点响应（2026-11-08 晚）
- **C2 机制复用**：已采纳。C2（PR65，run 42477604680 双 API 全绿）实证 shell 侧所有权绑定（content insert owner_package_name=测试 APK 包名）被 MediaProvider 接受（isCallingPackageShell 分支）——与我的 scan_file 方案同属"shell 持有、非应用自有"，方向一致；我方失败的根因是**字节传输走 UiAutomation shell**（机制级不可靠），而非 shell 持有本身。已改为宿主 adb push（d7f726a）。C2 与我在 run-cloud-ui-probe.sh 的冲突面：C2 仅 3 行（class 列表+expected+注释），我的段落独立成块，合并无实质冲突。
- **升级规则**：已知悉——同一阶段再连续红两轮即做独立只读咨询（截图+树 dump 交独立会话裁决）。
- **范围收窄评估（当前判断）**：六轮失败的根因全部集中在**夹具字节传输层**（formative infrastructure），断言层已被轮4 失败截图**间接验证**（勾选收敛实际到达预期集合——角标恰在 A 两格、count=2）。轮7 已把夹具移至宿主 adb（该层不再经过 instrumentation），PhaseA 应收敛；PhaseB 复用同一套原语（收集/勾选/稳定等待/确认），边际风险低。**判断：维持 A→B 全范围跑轮7；若 PhaseA 同阶段再红两轮，按升级规则交独立裁决，并在 05:30 前未收敛时收拢为"阶段A only"交付**（PARTIAL 建立+地面真值+UI 一致性+组/断点持久化本身已是完整验收单元，PhaseB 降级为后续单元登记）。

## 6. 独立裁决请求（2026-11-08 深夜，协调者升级规则触发）

### 6.1 当前状态一句话
**阶段A（PARTIAL(A) 经真实系统对话框+照片选择器建立 + 全链断言）已在轮10 双 API 全绿；PhaseB 唯一卡点 = ACTION_PICK_IMAGES 重选确认后授权集未变（双 API 一致）。**

### 6.2 假设链与每轮根因（R1→R10）
| 轮 | head/run | 红点 | 根因→修正 |
| --- | --- | --- | --- |
| R1 | 1f83100 / 42468075623 | 套件实例化 | @Test 以 Log.i(Int) 收尾 → JUnit void 校验 → runBlocking<Unit> |
| R2 | 21fb7e7 / 42469308043 | 勾选驱动 | 角标=子节点 desc"Selected"，原检测失明 → 盲 tap 振荡；截图实证预选态与网格顺序正确 |
| R3 | 493a4f5 / 42471835967 | 对话框不推进 | "Allow limited access"（API37 文案）坐标 tap 被吞没 → ACTION_CLICK + 推进核验重试 |
| R4 | 7d4829a / 42473377747 | 全 5 格预选 + 树滞后 | **应用自有媒体免权限可见 → 选择集语义不可观测（设计缺陷实证）**；树滞后 >600ms → 双重矫正 |
| R5 | d0d8357 / 42476083194 | shell 字节写 | 单命令 ~5KB base64 输出为空（截断）→ 分块 |
| R6 | 1e583c0 / 42477634711 | 分块写仍败 | 错误回显=命令本体且空格剥离 → **executeShellCommand=直接 exec 朴素分词，无 shell 解释（机制结论）** |
| R7 | d7f726a / 42479680787 | 夹具核对 | 进程内 content query + 2>&1 → usage 错误 → 夹具编排移至宿主 adb（push+scan_file，固定名） |
| R8 | cd9df1d / 42481561828 | 勾选驱动 | abort 截图角标恰在预期两格 count=2、树读数恒空 → **辅助树不反映勾选变化（机制结论）** |
| R9 | a101994 / 42484538892 | 双 API 分叉 | API36 瓷片色比对模型失效（逐格采样=背景色 (141,155,187)，未勾选渲染跨 API 不同）→ 白色勾选笔画判据；API37 全链走到 viewer-overlay 超时（工件缺失）→ 查看器点击重试 |
| R10 | a7685b4 / 42486598092 | **PhaseA 双 API 绿；PhaseB 单点** | 重选确认后授权集未变（唯一剩余卡点） |

### 6.3 核心证据行（工件链）
- R2/R4/R8 abort 截图+树 dump（api36/api37 ui-failures/）：网格顺序 [B2,B1,A2,A1,S] 实证成立；角标渲染/预选态可见。
- R6 错误回显（job log）：`printf %s <chunk>>/data/...b64;echo__CHUNK_0=$?`（空格剥离+逐参数 %s 拼接）——直接 exec 定量吻合。
- R9 API36 logcat：`pixelCell[0..4] avg=(141,155,187)` 五格同值（背景填充实证）。
- R10 job log：API36 14:02:32 `OK (1 test)`（PhaseA）；双 API `AssertionError: 重选确认后应用可见集合必须恰为 B（实际=[partial-a1.png, partial-a2.png] ...）`。
- 注：R9 API37 与 R10 双 API 的 per-API 工件上传缺失（平台瞬时故障）；R10 PhaseB 的像素驱动/确认路径 logcat 不可得，(b) Done 未提交 与 (c) PICK_IMAGES 不更新授权集 两种解释均与 job log 相容。

### 6.4 当前选择感知机制（像素分类器）
- 输入：screencap 全屏帧 + 辅助树格边界（位置跨轮稳定准确；**树不反映勾选变化**——仅用其位置）。
- 判定：每格左上角标区域（格尺寸 12% inset，31x31 采样）白色像素（r,g,b>210）≥15 → 勾选（角标=蓝圈白勾；跨 API 唯一稳定勾选标志——五种瓷片色与网格背景均无白色像素，逐色核查）。
- 容差/上限：白色阈值 210、计数阈 15、区域 31x31（badge ~63px，勾笔画核心在内）。R10 PhaseA 双 API 通过=该传感器在勾选/去选两方向均有效。

### 6.5 PhaseB 卡点的三种解释（裁决需先分辨）
- (a) 像素读数被欺骗（角标误判）→ 概率低：PhaseA 同传感器双 API 全绿；且若 drive 未收敛会在 driveChecked 处红灯而非地面真值处。
- (b) Done 确认 tap 未提交（像素药丸检测点偏/按钮禁用）→ 中概率：confirm 走像素兜底（a11y 8s 未命中），tap 落点无回读验证。
- (c) ACTION_PICK_IMAGES 确认不更新持久 user-selected 集（文档未载明该语义，仅"轮4 预选=all-5 自有媒体"间接暗示）→ 若为真：重选必须走权限对话框 reselect 或系统设置页。
- 分辨手段（一次诊断 run）：PhaseB 地面真值失败路径强制截屏（当前已有 deadline-ground-truth-B 留证钩子）+ logcat 确认 confirm 方法——若截屏显示选择器仍开且勾选={B}，则 (b)；若选择器已关且集=A，则 (c)。

### 6.6 可行替代路径（若裁决=继续）
1. **Settings 页驱动（登记的回退，未实现）**：`am start -a android.settings.APPLICATION_DETAILS_SETTINGS -d package:com.tabula.v3.recyclebin.test` → "Photos and videos" → "Select photos"/"Select more photos" → 照片选择器（复用既有像素驱动/确认原语）→ 确认后 Settings 的授权集变更。真实系统 UI、无生产改动；新增节点驱动风险中等。
2. **诊断先行**：按 6.5 分辨 (b)/(c)，(b) 则修确认落点，(c) 则走路径 1。
3. **B2 收拢参考**：NONE→PARTIAL(B) 重建路径（PhaseA 原语 + 哨兵重置）已被 PhaseA 实证可行。

### 6.7 若裁决=不可行：可收拢交付的最小断言集合
- **PhaseA 双 API 绿即完整验收单元**（轮10 已达成）：无授权空集断言 → 系统对话框+选择器建立 PARTIAL(A) → 级别三态断言 → 地面真值恰={A1,A2} → 哨兵/未选集合外不可见（UI+查询双面）→ 清理组+断点持久化+flush → deck/查看器 overlay/月份专清真实 sheet 一致性 → 预期状态跨进程持久。
- PhaseB（A→B）降级为后续单元登记：卡点=PICK_IMAGES 授权更新语义 + Settings 页回退设计（6.6.1）已写明，接手者可直接续作。
- PR 描述与 notes 如实登记：已交付边界=PARTIAL 建立侧；A→B 变更侧 blocked（机制结论 6.5(c) 待裁决定性）。

### 6.8 裁决选项汇总
- **A. 继续**：先诊断 run（分辨 (b)/(c)），(c) 则实现 Settings 页回退（估 1-2 轮）。
- **B. 直接实现 Settings 回退**（跳过诊断，估 1-2 轮，PhaseA 原语复用度高）。
- **C. 收拢交付**：PhaseA 即验收单元（已双 API 绿），PhaseB 登记；PR 现状可审（PhaseA 断言全保留，PhaseB 用例标记为已知边界或摘除——裁决定）。
- 时间基线：当前 22:50；冻结 06:30；每轮 CI ≈20min。三条路径均可在截止前完成。

### 6.9 裁决执行记录（R11→R15 压缩）
- R11（71b575e / 42492426169）：Settings 页实现后红——行点击仅切模式不开选择器（API36 树 dump：Photos and videos permission 页三选项+行内 gear desc=Settings）。
- R12（58a5e74 / 42494373019）：gear 直点后红——API37 AppInfo 行点击被吞没卡在 AppInfo（R3 同类）。
- R13（902a4ef / 42496304498）：导航核验重试后红——tapWithNavigationVerify 无候选等待，AppInfo 节点 ~2-5s 渲染延迟。
- R14（f540152 / 42498101516）：候选等待后红——API37 timed out waiting for partial-b1.png（月份清理成员视图）：**地面真值 {B1,B2} 已通过**（重选生效）、deck/查看器过、仅成员视图无卡——引擎对成员缺席的持久组持有（任务 A 语义），B 未分析无批次（生产语义）。
- 修正（R16 待跑）：月份断言收拢为 sheet 月行（生产 buildMonthInfos 口径）+ 持有语义登记。
- 额度纪律生效：每轮最小证据（一行/一截图区），notes 压缩。

### 6.10 裁决执行完成（R16 全绿）
- R16（50c9d88 / run 42500080220）：**三 job 全绿**（build + API36 + API37，各 ~10min）——Settings 回退链（AppInfo 深链→Permissions 导航核验→Photos and videos→gear→像素驱动 {0,1}→确认回读）+ 地面真值 {B1,B2} + PhaseB 全部保留断言（retention pre/post-switch、哨兵 shell 行存在、查看器、月行）双 API 通过；PhaseA 重绿补强（裁决第 1 问的补强条件达成）。
- 交付边界：A→B 重选机制=系统设置页（(c) 实证：PICK_IMAGES 确认不更新授权集）；月份清理成员视图为引擎持有语义（不断言，段内登记）。
- 分支 docs/night-2026-11-08-B1-notes.md = 本 notes 快照（资产保全）。

### 6.11 最终验收 run 与证据绑定
- 最终验收 run 42501864524（head 35e5683=docs-only 提交）：build 绿、API37 绿（PhaseA+PhaseB 全链）、**API36 一次性瞬态抖动**——Settings 权限页 'Photos and videos' 条目 30s 未现（该步骤 R16 同 API 通过；R10/R16 API36 多次通过；非代码回归——35e5683 与 50c9d88 的 diff 仅 1 个 notes md 文件，可 git diff 核对）。
- **证据绑定口径**：代码证据 = R16 run 42500080220（head 50c9d88，三 job 全绿，PhaseA+PhaseB 全链双 API）；其后唯一提交 35e5683 为 docs-only。按验收约定「分支随后变化则判断证据是否仍适用」：适用。
- 额度纪律生效：不再为 final-head 绑定重派。

### 6.12 收尾单元（2026-11-09 01:35-01:50，未派 CI——协调者统一派发）
1. **跨月份夹具**（probe touch -t）：S/A1/A2 mtime=10月（10-10/10-15/10-20）、B1/B2 mtime=11月（11-05/11-07）→ date_modified/date_taken 派生月份分属 10/11 月；排序 DESC 仍 [B2,B1,A2,A1,S] 不变。
   - PhaseA 月份断言升级：sheet waitFor("10月") + awaitGone("11月")（B 月随选择集不可见而缺席）+ 成员视图 A 卡（原有）。
   - PhaseB 月份断言升级：**B 组种子**（seedCleanupGroup(rowsB)，同 PhaseA 写入口）→ sheet waitFor("11月") + awaitGone("10月")（A 月随切换消失）+ **成员视图恢复**：开始整理后 b1/b2 卡可见、a1/a2/哨兵不可见（R15 的持有语义在同月场景仍成立；跨月+ B 组种子使成员视图可交付可断言）。
2. **API36 入口失败排查**（final-run 工件 dump 直查）：失败时页面=AppInfo 滚动态（通知区在视口内、条目在折叠区下方）——条目不可见而非标签缺失。修复：（候选滚动重试 ≤4 次 + 点击后目标页核验标记 "Allow limited access" + 8s 等待）接入 PhaseB 设置导航。
- 验证：compileDebugAndroidTestKotlin 过；**CI 未派**（协调者统一派发）——本单元为 compile 级增益，派发后按既有判据验收。
