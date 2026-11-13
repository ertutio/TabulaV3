# B2 设计笔记：T07 FULL→PARTIAL→FULL 三阶段验收（wg-B2）

日期：2026-11-10。分支 `test/full-partial-full-2026-11-09`（= PR57 分支 tip 3492728）。
判据源：`coord/b2-acceptance-criteria.md`（权威，逐条映射见 §4）。复用资产全部同分支现成。

## 1. 关键研究结论（生产语义，读码实证）

### 1.1 成员夹具如何被引擎分析成组（参考 FullRecovery/CloudCleanup 种子方式）
- 夹具全部 **shell 身份**（宿主 adb push + scan_file 入库，非应用自有）——与 #57 同理：
  scoped storage 下应用自有媒体无需权限即可见，会令 PARTIAL 选择集语义不可观测；
  shell 持有使"应用可见集合 = 严格授权级别决定的集合"。字节传输只走宿主 adb
  （CI run 42476083194/42477634711 实证 UiAutomation 传字节被截断）。
- 成组用 **生产写入口种子**（`FullRecoveryStateContract.seedCleanupGroup` = saveAlbum-
  AnalysisResult/saveGroupImageCounts/saveGroupsImageIds，同 FullRecoveryPhase1Test/
  PartialSelectionPhaseATest 先例）；组 ID 由成员身份键派生（`groupIdOf`，SimilarGroup.id）。
- `restoreGroupsFromPrefs` **保留持久成员顺序**（"保留持久成员顺序：外部编辑不应移动旧断点
  指向的身份"，AlbumCleanupEngine.kt:655）→ 组内批次顺序 = 持久键序 [g1, g2]，断点索引
  语义确定。
- 月份归属：6 张夹具全部落在 2026-11（touch -t 指定 mtime，扫描派生 DATE_MODIFIED）→
  `buildMonthInfos(rows).single()` 单月，`cleanupAlbumId = month_2026_11`，displayName =
  "11月"（当前年）。g1/g2 必须同月（seedCleanupGroup 用 `.single()`，跨月即抛）。

### 1.2 断点/已处理语义（决定断言设计，读码实证）
- `getCheckpointBatch` 存在 **acknowledged 成员安全重启分支**（AlbumCleanupEngine.kt:800）：
  断点保存组内任一成员已 acknowledged → `clearCheckpoint` + 返回 null（"明确删除已缩短可
  操作成员，旧索引无法判断保存与删除先后。安全从该原组剩余行重开"）。**因此**：
  - Phase FULL 落盘的断点记录 (group, 1) 在 Phase PARTIAL 期间必须**只读不改**（测试与
    应用自身都不触发 getCheckpointBatch——应用冷启动 isAlbumCleanupMode 恒为 false，
    MainActivity.kt:519 `remember { mutableStateOf(false) }`，清理模式不跨进程自动恢复，
    故冷启动不会触碰断点）；Phase FULL 恢复阶段在读回断点之后再驱动 UI 续接。
  - Phase 恢复的"g2 依断点恢复为待处理"以**两级断言**落地面：引擎级
    `getNextBatch(albumId, 月行) == [g2]`（g1 经 acknowledged 过滤不交付、g2 待处理、
    组记录无重复无丢失）+ UI 级（真实月份入口续接后 deck 当前卡 = g2、g1 不在 deck）。
    UI 续接走的就是生产安全重启路径（清旧断点→按组目录+acknowledged 重算），与 #52
    FullRecoveryReadbackTest 的"safe restart at index zero"同机制。
- deck 上滑删除流（DeckScreen.onRemove，~行 1060）：被滑卡从批次**移除**、currentIndex 不
  前移（下一张滑入原位）→ 经 sheet"切换回全局"退出时 onSwitchToGlobal 保存
  `saveCheckpoint(原批次 groupIds, currentIndex)`（DeckScreen ~1747）——滑 1 张后退出落
  (group, 0)（变异后批次帧的索引歧义，正是 1.2 安全重启分支存在的原因）。**因此**
  Phase FULL 在 UI 退出清理模式后，用同一生产入口 `AlbumCleanupEngine.saveCheckpoint
  ([groupId], 1)` 把断点钉在"组内指向 g2 待处理"（持久键序下索引 1 = g2），再 flush
  （`flushCleanupPrefs` commit() 同步落盘信号）。这与 FullRecoveryPhase1Test 直接调
  engine.saveCheckpoint 同口径（生产写入口，非裸 prefs 写）。
- 引擎 getNextBatch 的持有语义（~行 385）：未处理成员当前不可见（不在快照且未
  acknowledged）→ 整组持有（返回 null，不置 COMPLETED、不清进度）——PARTIAL 下 G 组正是
  此形态；判据 6 的"B 未成组→无批次可断言"场景按 #57 收尾同款以"组记录保留+非永久完成"
  断言（不以月名/数量替代）。
- fullyRecycled 组才会被标永久完成（组内**全部**成员 acknowledged）——G 组含未处理的 g2，
  永不误标；"非永久完成"断言全程安全。

### 1.3 跨进程进程死亡口径（对齐 #52 实证）
- `pm revoke READ_MEDIA_IMAGES` → 系统强停应用（#52 probe 注释"宿主侧权限收窄：应用被系统
  强停（实证行为）"）→ 阶段间 sleep 2 后新 instrumentation = 新进程。
- `pm grant READ_MEDIA_IMAGES` **不杀进程** → #52 口径：宿主 grant 后显式 `am force-stop`，
  下一 instrumentation 新进程经测试内 am start 重开。
- 阶段间应用数据（files/ + shared_prefs + 回收站 journal）保留，仅阶段首 `pm clear`。

### 1.4 判据 10"旧快照晚到不覆盖"的实现路径
- PR50 的竞争门控 = `mediaAccessRevision` 键控 LaunchedEffect（键重启=取消在途扫描）+
  verbatim 提取的 `loadAndApplyMediaSnapshot` 加载-提交链（MainActivity.kt:183，internal），
  CancellationException 原样重抛、apply/onError 不为被取消扫描执行。
- 采用判据允许的**制造条件**形态（强于"存在性"断言）：在 instrumentation 内用真实生产链
  重演 PR50 `MediaSnapshotLoadRaceTest.lateReturningOldReadReturnsButNeverCommits` 的晚到
  形态——旧扫描 load 挂在 NonCancellable 闸门上（底层读取不可中断）、新扫描（**默认真实
  生产 load**）提交后释放旧读取；断言：旧读取确实返回（晚到形态成立）、提交只归新扫描、
  取消不落 error 态、新扫描 apply 到的投影 = 当前真实全池投影（6 行 − 回收站 g1 = 5 行，
  真实数据断言而非合成 ID）。androidTest 访问同模块 internal 符号走 AGP friend-path
  （编译门验证；失败则回退判据允许的"PR50 门控存在性+最终状态一致性"形态，见 §5 风险表）。
- 竞争后"最终 UI/查询状态与第 9 条一致"：重断言可见全集、acknowledged/recycle/组记录、
  deck 仍当前卡 = g2。

## 2. 媒体集合设计（6 张，shell 身份，Pictures/FullPartialFullFixtures/）

| 夹具 | 颜色 | touch -t (mtime) | 角色 |
| --- | --- | --- | --- |
| fpf-s1.png | 灰 888888 | 202611010900 | 哨兵 S：任何阶段不进任何集合/选择集 |
| fpf-n1.png | 蓝 0000FF | 202611031000 | 非组成员：FULL 下可见、PARTIAL(B) 下不在 B 集合 |
| fpf-g1.png | 红 FF0000 | 202611051100 | G 组成员（将被成组并部分处理） |
| fpf-g2.png | 品红 FF00FF | 202611061200 | G 组成员（断点指向的待处理者） |
| fpf-b1.png | 黄 FFFF00 | 202611071300 | B 集合专有（PARTIAL 阶段才可读） |
| fpf-b2.png | 青 00FFFF | 202611082000 | B 集合专有（最新 → 选择器网格首格） |

- 推送顺序 = mtime 升序（S→N1→G1→G2→B1→B2，相邻 >1.1s）→ 选择器网格按时间倒序 =
  **[B2, B1, G2, G1, N1, S1]**，B 集合 = 网格索引 {0, 1}（#57 像素驱动原语直接复用）。
- 成员身份断言一律 displayName + mediaIdentityKey（月名/数量相同不视为集合证明）。
- 集合断言口径（判据 1 括号语义）：FULL 阶段"可见恰={g1,g2,n1,b1,b2}"实现为
  **成员集合无多余无缺失 + 哨兵仅作存在性核对**：可见集合 − 5 成员 == {s1} 且 s1 在场
  （FULL 下应用可见全部媒体，s1 必然可见，判据括号已言明"s1 也在"）；PARTIAL 阶段
  可见恰={b1,b2}（s1/g1/g2/n1 全部不可见）。行数核对（app 查询 list.size==set.size +
  shell 查询 Row 数）落实"无重复、无缺失"。

## 3. 三阶段编排（shell 侧 + 3 个 androidTest 类，各 1 例，预期 case 数 = 3）

```
probe 脚本（tools/run-cloud-ui-probe.sh 新段，插在 PARTIAL 段之后、FULL 恢复段之前）
  pm clear → revoke VISUAL_USER_SELECTED(兜底) → pm grant READ_MEDIA_IMAGES
  夹具预清理（已知目录 content delete + rm -rf）→ 图库空核对（残留行名进失败信息）
  python 生成 6 PNG → adb push（顺序+间隔）→ touch -t → scan_file → 逐行核对在库
  [Phase1] am instrument FullPartialFullPhase1Test        （FULL 建立 + 处理 g1 + 落盘）
  宿主 pm revoke READ_MEDIA_IMAGES（系统强停应用）→ sleep 2
  [Phase2] am instrument FullPartialFullPhase2Test        （PARTIAL(B) 建立 + 持有读回）
  宿主 pm grant READ_MEDIA_IMAGES（不杀进程）→ am force-stop（对齐 #52 显式重启口径）
  [Phase3] am instrument FullPartialFullPhase3Test        （FULL 恢复 + 续接 + 晚到竞争）
  每阶段 OK(1) 数量门 + 失败 grep 门 + logcat/状态文件/ui-failures 拉取
```

### Phase1（FullPartialFullPhase1Test，判据 1–3）
前置断言：FULL 已授、VISUAL_USER_SELECTED 未授、级别=FULL、ranchu/goldfish、probe 参数。
1. 地面真值：授权前 app 查询可见 = 5 成员 + s1（集合差断言 + 行数核对）；shell 双面核对
   6 行逐行在库（displayName + relative_path）。
2. 种子 G 组（生产写入口）→ 组目录/成员键断言；引擎 `getNextBatch(albumId,[g1,g2])` 批次
   = 组 [g1,g2]（顺序=持久键序）。
3. 真实 UI 处理 g1：launch → 选择图集 → 月份 → 11月 → 开始整理 → deck 当前卡 = g1 →
   上滑 g1 → 截止轮询回收站=[g1键] 且 acknowledged={g1键}（生产撤销窗口提交）→ deck
   当前卡 = g2、g1 节点消失。
4. 经真实 sheet 退出清理模式（选择图集 → 全局，onSwitchToGlobal 生产路径）→ 生产入口
   saveCheckpoint([groupId],1)（断点=组内指向 g2 待处理）→ flush commit() 信号 →
   prefs 读回核对（断点/已处理/组键/未永久完成/月专清未完成）。
5. 期望状态落盘（FullRecoveryStateContract 形态扩展）：visible 全集、组键、断点索引、
   已处理集合、成员/哨兵/月归属、phase1Pid；文件长度>0。末尾再地面真值+shell 双面核对。

### Phase2（FullPartialFullPhase2Test，判据 4–7）
前置断言：级别=NONE（revoke 后裸状态）、pid≠phase1Pid、onboarding/免责标记保留。
1. 授权前 app 查询可见=空集（选择集过滤语义，#57 同款）。
2. shell 三面核对：g1/g2/n1 行仍在（不可读但可恢复）+ 哨兵 s1 行在。
3. 真实系统机制：launch → 授权访问照片 → 系统对话框 Select photos（候选匹配+推进核验）
   → 选择器 6 格收集 → 像素驱动勾选 {0,1}（=b1,b2）→ 确认提交回读 → 轮询
   VISUAL_USER_SELECTED 授且 FULL 未授（级别=PARTIAL）。
4. 地面真值：可见恰={b1,b2}；g1/g2/n1/s1 不可见；b1/b2 rebuildRow 可读（PARTIAL 才可读）。
5. 进度完整保留（跨进程读回，与 Phase1 落盘逐项一致）：组键 [g1,g2] 原样、断点 (group,1)
   原样（只读，绝不调 getCheckpointBatch——安全重启分支会清断点，§1.2）、acknowledged
   ={g1键}、回收站=[g1键]、未永久完成、月专清未完成；B 未成组（组目录仅 G 组）→ 按
   判据以"组记录保留+非永久完成"断言，不驱动月份 UI、不以月名/数量替代。
6. 哨兵：app 内不可见 + shell 行存在（判据 7 全程行存在）。
7. 写 phase2Pid。

### Phase3（FullPartialFullPhase3Test，判据 8–10）
前置断言：FULL 已授（级别=FULL）、pid≠phase2Pid。
1. 地面真值（判据 8）：可见 = 5 成员 + s1（无重复：list==set；无缺失：shell 6 行核对）。
2. 记录读回（判据 9，驱动 UI 前）：断点 (group,1) 原样、acknowledged={g1键}、回收站
   =[g1键]、组键 [g1,g2] 无重复无丢失、未永久完成。
3. 引擎续接断言（判据 9）：5 成员 rebuildRow 全部可读；`getNextBatch(albumId, 真实月行
   (6 行))` 批次恰=[g2]（g1 不重新进入待处理、g2 依进度恢复为待处理）；调用后仍未永久完成。
4. 晚到竞争（判据 10）：§1.4 受控晚到（旧扫描滞留 PARTIAL 时代投影 {b1,b2}、新扫描真实
   生产 load）→ 四断言 + 最终一致性重断言（可见集/acknowledged/回收站/组键不变）。
5. UI 续接（判据 9 UI 级）：launch → 月份 → 11月 → 开始整理 → deck 当前卡 = g2（生产
   安全重启路径）、g1 不在 deck；最终查询状态与步骤 4 后一致（判据 10 收口）。
6. @After：按名+路径 shell 精确删除 6 夹具并核对消失 + rm -rf 目录（#57 PhaseB 收尾同款）。

## 4. 判据逐条映射表

| 判据 | 落点 |
| --- | --- |
| 1 FULL 地面真值+VISUAL_USER_SELECTED 清理 | Phase1.前置+1（pm clear 后 VISUAL_USER_SELECTED 天然无授权，probe 再兜底 revoke） |
| 2 真实入口建组+断点+处理 g1 | Phase1.2（生产写入口种子）、3（真实 UI 上滑）、4（生产入口断点钉到 g2） |
| 3 期望状态落盘 | Phase1.5（FullRecoveryStateContract 形态 + 扩展字段） |
| 4 revoke→对话框→Select photos→像素选 b1,b2 | probe 宿主 revoke + Phase2.3 |
| 5 可见恰={b1,b2}+三面核对 | Phase2.1/2/4 |
| 6 进度完整保留+B 集合引擎语义 | Phase2.5（含 §1.2 只读纪律与安全重启机制说明） |
| 7 哨兵全程行存在+集合外不可见 | Phase2.2/6（FULL 阶段的存在性核对见 §2 口径） |
| 8 grant→重启→可见恰=5+哨兵 | probe 宿主 grant+force-stop（#52 口径）+ Phase3.1 |
| 9 已处理保持+g2 依断点恢复+无重复无丢失 | Phase3.2/3/5 |
| 10 旧快照晚到不覆盖 | Phase3.4/5（§1.4） |
| 通用：双面核对/截止轮询/留证/无固定 sleep/数量门 | 全程 awaitDeadline 截止轮询；证据走 PartialSelectionUi 截屏+树 dump（文件名 sanitize 无 `|`）；probe OK(1)×3 数量门+失败 grep 门 |

## 5. 风险与回退

| 风险 | 缓解/回退 |
| --- | --- |
| androidTest 访问 internal `loadAndApplyMediaSnapshot` 编译失败 | 回退判据允许形态：PR50 门控存在性断言（MediaSnapshotLoadRaceTest 在 app/src/test 的受控竞争已验）+ 最终状态一致性断言；登记于执行记录 |
| sheet"全局"段点击被吞 | PartialSelectionUi.tap 自带坐标回退；退出核验=DECK_MARKER 回归 |
| 上滑手势未触发删除 | removeCard 坐标 swipe（FullRecoveryUi 同款）；提交走截止轮询，超时留证红灯 |
| 选择器格数/排序漂移 | collectGridCells 逐格 desc 日志 + driveCheckedCells 像素收敛（3 轮矫正）+ 地面真值最终裁决 |
| 月名"11月"依赖 CI 当前年=2026 | buildMonthInfos displayName 当前年判据与夹具 mtime 同为 2026-11；若 CI 时钟漂移，月份断言按 rebuildRow 实际 DATE_MODIFIED 重建月名（不硬编码字符串于断言，仅用于 UI 导航等待） |

## 6. 约束遵守
- 只改 androidTest 新增文件 + tools/run-cloud-ui-probe.sh（LF）；不动 app/src/main、app/src/test。
- probe 脚本与 PR66 的 ProdAuthCallbackDeviceTest 行的机械并集留给整合（本分支不加）。
- 提交推送随做；每轮 CI 失败留工件证据。

## 7. 执行记录（2026-11-10/11，两段会话）

### 7.1 实现与首轮 CI（前会话，已完成并推送）
- 三阶段实现完成：44e1c50 已推送（5 个 androidTest 文件 + probe FPF 段，+1155 行），工作树干净。
- CI run 42666340050（2026-11-09，API36+API37 device 双红）：
  - **唯一独立失败 = Phase1 `exitCleanupViaSheet`**：`IllegalStateException: unexpectedly visible: 选择图集进行整理 (cleanup-sheet-dismissed)`（FullPartialFullUi.kt:60 awaitGone）。
  - Phase2（readExpectedState）与 Phase3（kt:98 同）为**依赖级联失败**（Phase1 未落盘期望状态，文件不存在）——非独立红。

### 7.2 根因分析（本会话源码实证，修正未落码即中断）
- **生产 sheet 退出路径已核实（协调者指令④）**：DeckScreen.kt:1744 `onSwitchToGlobal` = saveCheckpoint（变异批次帧）+ 清模式 + `showAlbumCleanupSheet = false`；AlbumCleanupBottomSheet.kt:865/893 全局 ToggleItem clickable → onModeChange(GLOBAL) → onSwitchToGlobal。**退出动作本身正确，生产确实关 sheet**。
- **真实失败机制 = 等待语义混淆（协调者指令②）**：`awaitGone`（FullPartialFullUi.kt:64）首见即杀——ModalBottomSheet 退场动画 ~300-500ms 内头部合法可见，tap 返回后首次轮询即误杀（成功路径也会红）；且单击无推进核验（CI 实证 tap 可被吞没，同 tapDialogActionAndAwaitPicker 存在的理由）。startMonth 的 awaitGone("开始整理") 同病，首轮靠时序侥幸通过。
- 次要：Phase1 g1-removed awaitGone 同属过渡期首见即杀语义（上滑动画）。

### 7.3 修正方案（已定稿——已于本会话全部落码，见 §7.4）
1. FullPartialFullUi 新增 `awaitDismissed(label, tag, timeoutMs=12000, observeMs=1500)`（等待最终消失：允许有界过渡，消失后 1.5s 观察窗不得重现）与 `tapAndAwaitDismissal(label, tag, attempts=3, perAttemptMs=8000)`（点击+推进核验+重试）。需补 import android.util.Log。
2. `startMonth`：tap+awaitGone → `tapAndAwaitDismissal("开始整理", "month-sheet-dismissed")`。
3. `exitCleanupViaSheet`：tap("选择图集") → `tapAndAwaitDismissal("全局", "cleanup-exit-global")` → `awaitDismissed("选择图集进行整理", "cleanup-sheet-dismissed", 8000)` → `PartialSelectionUi.waitFor(DECK_MARKER, 15000)`（生产退出完成核验）。
4. Phase1 `g1-removed-from-deck`：awaitGone → awaitDismissed。Phase3 `g1-not-reentered` 保持严格 awaitGone（不变量语义，g1 不得出现在 deck）；Phase2 判据7 补哨兵严格 2s 观察窗（`ui.awaitGone(sentinelName, 2000)`）。
5. **依赖纪律（协调者指令③）**：StateContract 新增 `requireDependency(ctx, pidKey, upstream)`（文件存在且含 pid 键，否则 check 报"依赖未满足：Phase X 未完成——本阶段拒绝执行"）；Phase2 @Before 门 phase1Pid、Phase3 门 phase2Pid。
6. probe：Phase1 红 → 跳过 Phase2 instrumentation；Phase2 红 → 跳过 Phase3（"dependency-unmet" 日志，fpf_status 保持 1，不跑依赖阶段）；跳过时宿主侧按名兜底清理 6 夹具。
7. 然后：compileDebugAndroidTestKotlin → 提交 → dispatch CI（build-volume-confirmation.yml @ test/full-partial-full-2026-11-09）→ 按轮迭代 → notes 收尾 + PR（base=test/partial-selection-ab-2026-11-08）。

### 7.4 修正落码与 R2/R3 CI（本会话，2026-11-11 晚——B2 收拢）

- **§7.3 全部落码**（commit 67ca439，compileDebugAndroidTestKotlin 通过，LF 纪律保持）：
  1. FullPartialFullUi 新增 `awaitDismissed`/`tapAndAwaitDismissal`（私有 `pollFinallyGone` 共用轮询核）；`startMonth`、`exitCleanupViaSheet`、Phase1 `g1-removed-from-deck` 改推进语义；Phase3 `g1-not-reentered` 保持严格 awaitGone；Phase2 判据7 补哨兵严格 2s 观察窗。
  2. StateContract 新增 `requireDependency(ctx, pidKey, upstream)`（文件存在 + JSON 解析成功 + pid 键>0，否则 check"依赖未满足：Phase X 未完成——本阶段拒绝执行"）；Phase2/Phase3 @Before 首行置门。
  3. probe：`fpf_phase1_ok`/`fpf_phase2_ok` 门——Phase1 红 → 跳过 Phase2；Phase2 红（含 Phase1 红级联）→ 跳过 Phase3；"dependency-unmet" 日志 + fpf_status 保持 1 + 宿主侧 `fpf_cleanup_fixtures` 按名兜底清理 6 夹具（content delete + rm -rf）；失败 grep 门逐段前移、收尾循环加文件存在守卫。
- **R2 = run 43049377860（67ca439，API36 红 / API37 绿，~19min）**：
  - Phase1（exitCleanupViaSheet）与 Phase2 双 API 全绿——§7.2 根因（awaitGone 首见即杀）修正确认有效，R1 唯一独立失败消除；依赖门/跳过纪律本轮未触发（Phase1 绿正常续跑）。
  - API36 Phase3 红：`timed out waiting for 11月`（startMonth 内 tap(monthName)）。留证裁决（ui-failures timeout-11月 截屏+树 dump）：sheet 已在"月份"模式、"11月/5 张照片"行渲染在屏，但 a11y 树 30s 无月行文本——分段控件静态文本可达、dialog 内 LazyColumn 列表项语义更新丢失（R1 API36 的 ALBUM 模式留证同构）；app 无错误日志、无主线程阻塞（Davey 双进程同量级且 Phase1 仍成功）——时序/状态依赖的语义暴露丢失（API37 全绿、Phase1 同路径 R1/R2 均绿）。判据 8/9/10 引擎与记录断言全部先行通过，红仅限 UI 续接导航。
  - 定向修正（commit 991307e）：startMonth 月行 a11y 不可达时有界重开 sheet 重试——`tapMonthIfExposed`（10s 轮询可达即点击）→ `reopenCleanupSheet`（scrim "Close sheet" = 生产 onDismissRequest 路径，关闭核验 = 头部最终消失）→ 重切月份，至多 3 轮，逐轮留证、无固定 sleep。
- **R3 = run 43055853277（991307e，双 API 全绿，~25min）**：API36/API37 三阶段各 OK(1)，probe 数量门+失败 grep 门全过；Phase1/Phase3 月行均 attempt 0 命中、无 reopening 触发（重试为未触发的保险路径）。判据 1–10 全断言双 API 绿（映射表 §4 全兑现），通用纪律全程在案。**（2026-11-14 更正引用：该轮表述在断点腿（测试钉索引而非 UI 自保存读回）与哨兵腿（仅 a11y 树观察窗）超出实际——以 §8 边界补强轮及 PR72 正文对照表为准。）**
- 遗留：无。R3 = 收拢轮（全绿）；PR：base=test/partial-selection-ab-2026-11-08。

## 8. 边界补强轮（2026-11-14，PR72 收尾单元——边界 1+3；边界 2 由主代理串行另做）

指令源：coord/INDEX.md "新指令批（2026-11-14）：PR72 补强三边界"。约束：只改 androidTest
（4 个 FPF 文件）+notes；不动 app/src/main、app/src/test、probe；边界 2（旧快照晚到接线
验证，t13-overlay-acceptance.md §边界 2 设计）不在本单元。

### 8.1 先确认生产语义（读码实证，再写断言）
- `AlbumCleanupEngine.getCheckpointBatch` 放弃规则（AlbumCleanupEngine.kt:796-831）：
  ①临时不可见（成员不在快照且未 acknowledged）→ **返回 null 不清断点**（持有，FULL 后
  同实例可再恢复）；②**acknowledged 成员规则（:800-804）**：断点保存组内任一成员已
  acknowledged → `clearCheckpoint` + 返回 null（"明确删除已缩短可操作成员，旧索引无法
  判断保存与删除先后。安全从该原组剩余行重开，不能盲目平移而漏掉尚未浏览的成员"）；
  ③批次组成变化/保存后重分析 → 同为放弃形态。行缺席在快照校验处"只持有，不清断点"
  （:845-850）。`saveCheckpoint(groupIds, currentIndex)` = 原批次 groupIds + 当前帧索引
  （AppPreferences 键序字符串+index+时间戳，7 天过期）。
- deck 退出自保存路径（DeckScreen.kt:367/382/1753-1757 + onRemove ~1056-1090）：被滑卡
  从批次移除、**currentIndex 不前移**（下一张滑入原位）；onSwitchToGlobal 保存
  `saveCheckpoint(currentCleanupBatch.groupIds, currentIndex)` → 3 成员组滑 1 张后退出
  = **([G], 0)**。月专清入口（:1677）getCheckpointBatch null → 回退 getNextBatch 正常取批
  （重算分支与断点分支同函数内并列，R3 已实证）。
- 回收站 loadRecycleBin = JSON 追加序（addAllToRecycleBin 逐条 append，RecycleBinManager
  :86-96）→ 处理 g1、g2 后 = **[g1键, g2键]**。

### 8.2 边界 1：PARTIAL 哨兵 2s 同窗观察（Phase2 判据 7 补强）
- 旧形态差异登记：单次 `visibleDisplayNameList` 缺席断言 + `awaitGone(2s)` 仅 a11y 树——
  媒体集合面只在窗前采样一点；且 awaitGone 的 `find()==null` 无法区分"确认缺席"与
  "窗口缺失/他包窗口"。
- 新形态（FullPartialFullPhase2Test 判据 7 块）：同一 2s 观察窗内逐次（100ms）三面读数：
  ①**应用权限下的生产媒体查询**逐次断言哨兵缺席——新增
  `FullPartialFullStateContract.visibleDisplayNameListOrNull`（cursor=null 按语义返回
  null，不折叠为异常）；查询异常 → dumpTree+saveScreenshot 留证后如实失败；cursor=null
  → 计数登记（Log.w，"null 不是缺席证据"）继续；成功 → 逐次缺席断言（含查询原文）。
  ②**应用界面可观测**：`rootInActiveWindow` 非空且 packageName==被测应用包；缺失/他包
  → 计数登记继续；全程无应用窗口 → 失败（界面面从未成立）。③**UI 树哨兵节点缺席**
  （原 awaitGone 不变量语义并入同窗，出现即留证失败）。窗末汇总登记
  （queryOk/queryNull/appWindowOk/windowMissing）+ 两道有效性门（queryOk≥1、
  appWindowOk≥1）+ shell 行存在（全程行存在保留）。
- 效果：把"窗口缺失导致的假缺席"与"确认缺席"分离；媒体集合面在窗内持续采样（非单点）。

### 8.3 边界 3：真实 UI 自保存断点的跨进程读回 + 安全重启（判据 2/9 补强）
- 夹具：**第 7 张 shell 身份夹具 g3**（fpf-g3.png，纯绿 FF00FF00 → COLOR_G3=0xFF00FF00，
  宿主同款 zlib PNG 算法）。宿主 probe 只推 6 张（不动）→ Phase1 测试侧补建
  `ensureG3Fixture`：shellScript 通道 mkdir + `base64 -d` 落盘（PNG 以 base64 **文本**
  内嵌于测试源——run 42476083194/42477634711 截断实证针对字节传输；文本 argv 无此问题，
  shellScript 通道自身即依赖 on-device base64 -d）→ `touch -t 202611090900`（全套最晚）
  → scan_file 入库（宿主同款机制+广播兜底，截止轮询行出现）→ shell 行核对。文件由
  shell UID 经 FUSE 落盘、shell 扫描 → 行所有权=shell（与 adb push 同身份，非应用自有，
  PARTIAL 选择集语义保持可观测）。g3 mtime 取最晚是刻意的：g3 同时是最后入库行（_id
  最大）→ 时间倒序与 _id 倒序两排序模型下网格同为 [G3,B2,B1,G2,G1,N1,S1]，B={1,2}
  不依赖未实证的排序模型。Phase1 增 @After：@Test 红（状态文件未落盘）时按名+路径
  删 g3，防残留行污染同宿主图库空核对（宿主 skip 兜底只按名清 6 张）。
- Phase1：3 成员组 [g1,g2,g3]（seedCleanupGroup 生产写入口；地面真值 6 成员 + 哨兵；
  shell 7 行）。**移除旧形态的 `engine.saveCheckpoint([G],1)` 覆盖**——经真实 sheet 退出
  （onSwitchToGlobal）后读回并断言 UI 自保存状态 = **([G],0)**（onRemove 索引不前移），
  flush 后落盘（状态文件 checkpointIndex=0 = UI 自保存基准）。
- Phase2：断点原样 = ([G],0)（跨 revoke 进程死亡读回，只读纪律不变）；组键 [g1,g2,g3]。
- Phase3（跨 force-stop 读回后按**生产契约明文**断言，非重算）：
  ①`getCheckpointBatch` 返回 **null** + 断点**已被清除**（acknowledged 放弃规则
  :800-804——UI 自保存 ([G],0) 含已处理 g1；盲目平移索引 0 将重新交付 g1）；
  ②放弃不误伤：组键/已处理/回收站原样；
  ③安全重启批次 = **[g2,g3]** 持久键序（≥2 有序未处理成员——成员顺序可断言；单成员
  批次无法区分索引续接/重算/顺序多种定位行为，此即 g3 存在的理由）；
  ④UI 续接：deck 当前卡 = g2、g1 不在 deck（生产安全重启路径，getCheckpointBatch null
  → getNextBatch 回退）→ **上滑处理 g2 → 下一卡 = g3**（顺序在真实 deck 上成立）→
  生产撤销窗口提交（acknowledged={g1,g2}、回收站=[g1,g2] 追加序）；
  ⑤最终一致性同步更新（{g1,g2}/[g1,g2]）。
- 与判据 2 的关系：判据 2"断点=组内指向 g2 待处理"的语义由"UI 自保存状态 + 生产续接
  契约解析出 g2 待处理"端到端落实（强化：不再由测试钉指定索引），判据未降低。

### 8.4 执行记录
- 实现 + compileDebugAndroidTestKotlin 通过（4 文件 +327/−88，CR=0），commit **d1de057**
  推送 test/full-partial-full-2026-11-09。
- CI：build-volume-confirmation.yml @ test/full-partial-full-2026-11-09 =
  **run 43467600831（@d1de0579）= 首轮全绿**：build + device(API36) + device(API37)
  三 job success（~23min，1 轮无红）。双 API 工件核对：FPF phase1/2/3 各 **OK(1)**、
  0 FAILURES、无 dependency-unmet；logcat 证据线（双 API 同构）：
  - P1 `boundary-3 fixture g3 ensured (shell identity, mtime newest)`（g3 on-device
    补建成功）+ 地面真值 7 行（含 g3）+ `phase FULL complete: uiCheckpoint=0`；
  - P2 `sentinel same-window observation: queryOk=4 queryNull=0 appWindowOk=4
    windowMissing=0 (2s window)`（API36）/ `queryOk=12 ... appWindowOk=12`（API37）
    ——同窗配对观察 4/12 次，cursor=null 与窗口缺失均为 0；
  - P3 `resumed batch processed in persistent order: g2 committed, deck at g3` +
    `phase FULL-recovery complete: safe-restart resumed [g2,g3]`；@After 7 夹具
    全部 gone=true（含 g3）。
  - full-partial-full-expected-state.json：`checkpointIndex:0`（UI 自保存基准）、
    members=3 键（g1,g2,g3）、processedMembers=[g1键]。
- 登记：同窗观察实际迭代次数由"查询+树遍历"成本决定（API36 4 次/API37 12 次，
  窗口固定 2s、间隔 100ms）——"反复读取"成立（非单点），次数非目标值；g3 残留
  风险面：仅 instrumentation 进程在 g3 创建后、@After 前硬崩溃会残留 g3 行
  （常规红路径由 Phase1 @After 覆盖；硬崩溃属 probe 已红场景，登记不追加门）。
- 后续：边界 2（旧快照晚到接线验证，真实 MainActivity 接线级）由主代理在本单元
  之后串行实施（t13-overlay-acceptance.md §边界 2 设计），本 PR 正文覆盖声明已按
  三边界现状重写（判据 10 的接线级验证不在本 PR 完成声明内）。

## 9. 边界 2：会话内媒体插入 + Resume 触发修订重扫的真实接线验证（2026-11-14，PR72 收尾微单元 B2）

指令源：协调者执行指令（设计权威 = t13-overlay-acceptance.md §边界 2 设计，2026-11-14
定稿形态：会话内 shell 注入新媒体 → ContentObserver/Resume 触发重扫描 → 新成员出现在
deck/图库；Home→Resume 触发权限修订重查 → 集合保持正确；两循环；用例末清理 + 残留
兜底）。约束：只改 FullPartialFullPhase3Test.kt（+notes §九）；不动 StateContract 既有
成员；不动 probe。

### 9.1 驱动方式核对（probe 类级 + OK(1) 精确计数门 → 新用例并入既有方法，如实登记）
- probe 对 Phase3 的驱动 = **类级**（tools/run-cloud-ui-probe.sh:346 `-e class
  com.tabula.v3.FullPartialFullPhase3Test`，无方法过滤）——新 @Test 方法会自动跟随类执行；
- **但** probe 紧随其后有精确计数门（:351 `grep -Eq "^OK \(1 tests?\)" phase3.txt`，
  run 43467600831 实证输出 `OK (1 test)`）——第二个方法 = `OK (2 tests)` **确定性破门**
  （与测试成败无关的假红：fpf_status=1 → probe exit 1 → device job 红）。probe 禁改
  → 按约束既有回退形态（"并入既有方法或改类级并说明"）：边界 2 用例**并入既有测试
  方法** `fullRestoreKeepsProcessedMemberResumesCheckpointAndIgnoresLateSnapshot` 末段
  （判据 10' 之后），probe 未动、OK(1) 语义保持。逻辑自含（无跨段状态依赖），probe
  计数门未来若放宽，本段可平移为独立 @Test。

### 9.2 生产接线（读码实证，本段验证的信息流）
- onResume → checkPermission(foregroundRefresh=true)（MainActivity.kt:342-352）→
  applyAccessLevel：级别不变 + 带权限回前台同样 mediaAccessRevision++（:405）→
  LaunchedEffect(revision,...) 键控重启（取消在途旧扫描——"旧后台扫描晚返回"的自然
  竞态窗口，设计知悉）→ loadAndApplyMediaSnapshot（loadAllData+回收站投影）→ apply
  提交 allImages（:576-620）。
- **应用无 ContentObserver**（app/src/main 全库 grep 零命中）——会话内 shell 注入在
  生产侧唯一重扫触发 = ON_RESUME；设计表述"ContentObserver/Resume"中实际存在的通道
  即 Resume（如实登记，非降级：设计目标为真实接线验证）。
- deck 消费面：清理 sheet"全局"段（onSwitchToGlobal，DeckScreen.kt:1744-1764）=
  清空全局批次 + needsInitialBatch=true → LaunchedEffect(:537) getRecommendedBatch
  (活投影) 重取 → 生产批次日志 currentBatch<-[batch-site-3]（DeckScreen.kt:154-157
  logBatchRows，全批 `uri:dateModified`）。
- 批次 = 整池：池 6/7 ≤ batchSize 默认 15，RANDOM_WALK 池≤batchSize 自清冷却
  （RecommendationEngine.getRandomWalkBatch）→ 每次重取 = 全池洗牌。

### 9.3 可观察信号与确定性边界（如实登记，设计"截止轮询"形态）
- **①池集相等断言（确定性投影证据）**：批次日志行按 **行 id**（uri 卷限定形式随 API/
  构造点为 external / external_primary——run 43467600831 API36 logcat 实证
  `content://media/external_primary/images/media/<id>`，故按 id 比对，形式无关）与
  期望池集合相等：新成员入池 = ON_RESUME 重扫描已提交；既有 5 行（g3,n1,b1,b2,s1）
  无丢失；g1/g2 回收站行不入池 = 修订重查后集合保持正确（回收站语义经真实重扫生效）。
- **②deck 窗口 displayName 节点可见**（设计"n2 节点可见"）：RANDOM_WALK 洗牌
  （shuffled.take(batchSize)）决定卡位；deck 渲染当前+peek 卡（ImageCard
  contentDescription=displayName，CardStack 三卡 ≤3 槽；重取后 index=0 → 当前+next
  2 槽）——新成员节点可见与否取决于洗牌位置 → 每轮经真实 sheet 退出重取批（=重新
  洗牌）截止轮询至命中（90s/循环，典型 1-3 轮收敛；洗牌位置不确定性登记，确定性
  投影证据由 ①承担，两者同一轮询环各达成即可）。
- **③旧读取晚返回的确定性构造不可行**（生产 load 无注入点、自然扫描毫秒级不可控）
  → 该语义由单测级 MediaSnapshotLoadRaceTest（PR50）覆盖；本段验证**最终状态正确性**
  （真实接线的信息流终态）——与执行指令如实登记口径一致。
- 地面真值：MediaStore 行集断言（判据 8 原语复用）——注入后恰 7 夹具+n2 / 7 夹具
  +n2+n3，无重复无缺失（g1/g2 行仍在库：回收站是应用投影语义，MediaStore 行不删）；
  哨兵 s1 shell 面行存活（两循环后 assertShellRowExists）。

### 9.4 用例形态（并入既有方法末段，两循环）
- 夹具：fpf-n2.png（touch -t 202611101010）/fpf-n3.png（202611101200）——均晚于 g3
  （202611090900 原全套最晚）且同落 2026-11；ensureG3Fixture 同款（base64 文本通道 +
  scan_file + 广播兜底 + 行截止轮询 + shell 行核对；内容与 g3 同款纯绿 PNG，身份 =
  名+行）；行所有权 = shell。宿主不推送（probe 不动），测试侧自管自清。
- 循环 1：注入 n2 → shell 面行核对 → MediaStore 真值（7+n2 无重复）→ Home（app pause，
  后台在途扫描继续）→ 2s → am start（FullPartialFullUi.launchApp）→ 回前台等待（清理
  卡 g3 或全局状态文本任一回归——容 activity 重建路径，30s 截止）→ 消费观察截止轮询
  （①+②）。
- 循环 2：注入 n3（mtime 晚于 n2）→ 同链路 → 期望池 7 id（n2 跨循环保持）→ MediaStore
  全集 9 行无重复（"全集无重复"判据）。
- 清理：用例末 shell 删 n2/n3 行 + rm 文件并核对消失；**@After 残留兜底扩展**（n2/n3
  不在 ALL_NAMES——宿主不推送、用例末自清；用例中途失败路径由 @After 兜底，幂等，
  防死行残留污染后续 probe 段——g3 清理模式同款扩展）。
- 副作用登记：循环 1 消费观察首轮自清理模式退出 → 生产 saveCheckpoint([G],0)
  （onSwitchToGlobal 既有路径，断点含 acknowledged g1 → 后续读回按放弃规则清零）；
  后续 FullRecoveryPhase1Test 自有 saveCheckpoint 同 albumId（同月份桶）覆盖，无跨段
  污染（读码实证）。重取批记录抽取冷却随池≤batchSize 自清，无跨循环漂移。

### 9.5 执行记录
- 实现 + compileDebugAndroidTestKotlin 通过（仅 FullPartialFullPhase3Test.kt，
  +notes §九），commit 6c12c51（6c12c51a0b9933150dc1759a1db78ce4bc101fb6） 推送 test/full-partial-full-2026-11-09。
- CI：build-volume-confirmation.yml @ test/full-partial-full-2026-11-09 = run 43478496667（@6c12c51，首轮全绿：build + device(API36) + device(API37) 三 job success，~21min）
  （首轮全绿；双 API 证据线同构：n2/n3 注入行核对→Resume 渲染→池集相等各 1 轮收敛（n2 持久：n3 池含 n2 id）→节点可见 1/3 轮命中→哨兵存活→n2/n3 清理 gone=true；phase3 OK(1) 双 API（91.4s/94.2s，B2 增量 ~50s））。

## 10. PR72 后续收尾单元：git 并入最新 master + 真实 MainActivity 权限刷新接线设备验证（2026-11-14，wg-B2，B2PermissionRefreshDeviceTest）

指令源：协调者执行指令（TabulaV3 收尾单元 #72 后续，两任务）。约束：不动
app/src/main、app/src/test；断言纪律照旧；预期 case 数声明。工作区 wg-B2
（分支 test/full-partial-full-2026-11-09 @ e95f705 确认后新建后续分支）。

### 10.1 任务 1：git 整合（预期冲突的实际落点=父分支已预解）
- 后续分支 `test/b2-to-master-2026-11-14` 自 e95f705 创建，合并 origin/master
  （f7450366ecf04…，含 #70/#75/#76 合并与诊断）→ **合并结果 Already up to
  date**：父链内已含 master f7450366 的合并（**b1a93cd** "Merge master f7450366
  (13 components + T12 + TaskA/B + diagnostics) into B2 branch"，parents =
  6c12c51 + f7450366）——master 为本分支祖先，master 侧无新提交。
- 预期中的 run-cloud-ui-probe.sh 并集冲突（master 的 8 类 + ProdAuthCallback
  恢复 vs 本分支 FPF 依赖门/兜底清理）**已在父分支 b1a93cd 解决**，逐项核对：
  8 类类列表完整（含 ProdAuthCallbackDeviceTest 恢复登记注释 + CardLifecycle
  DeviceTest 3 例）+ FPF 三阶段专用段（fixture 注入/宿主 grant-revoke 驱动/
  依赖门 dependency-unmet 跳过 + fpf_cleanup_fixtures 兜底）全部在场；
  `git diff origin/master HEAD -- tools/run-cloud-ui-probe.sh` 仅为本分支 FPF
  段新增（+175 行），master 侧内容零丢失。本分支合并无需再解。
- 推送 test/b2-to-master-2026-11-14 → **PR #79**（base=master，标题
  "PR72 后续：B2 三阶段并入最新 master"，body 记录父链
  test/full-partial-full→ace7625/e95f705、b1a93cd 预解并集与本分支关系）。

### 10.2 任务 2：生产接线读码（先读码，不臆测）
- 触发链（MainActivity.kt）：onResume → checkPermission(foregroundRefresh=true)
  （:342-352）→ applyAccessLevel（:395-407）：**级别变化（NONE/PARTIAL/FULL
  互切）或级别不变+带权限回前台 → mediaAccessRevision++** → TabulaApp
  LaunchedEffect(revision,…) 键控重启（:583-613，取消在途扫描）→
  loadAndApplyMediaSnapshot（:183-224 verbatim 提取）→ apply 提交 allImages →
  DeckScreen 快照向可见消费面同步（DeckScreen.kt:720+）。另有权限对话框回调
  （permissionLauncher → applyAccessLevel(foregroundRefresh=false)）。
- **应用无 ContentObserver**（app/src/main 全库 grep 零命中）——会话内权限/
  媒体变化的生产侧唯一重扫触发 = ON_RESUME 修订重查（与 §9.2 同口径）。
- 既有覆盖盘点：PR50 MediaSnapshotLoadRaceTest（单测级晚返回语义）、FPF
  Phase3 判据 10（真实生产链 NonCancellable 受控竞争门）、Phase3 边界 2（§九，
  同会话媒体注入+Resume 重扫，级别不变分支）、ForegroundPermissionUiTest
  （后台外部删改回前台刷新，级别不变分支）。**缺口 = 级别变更分支的接线 +
  revoke 新进程恢复语义 + 同进程会话内权限集合变化**——本单元三个场景据此定形。

### 10.3 设备场景定形（3 例 = 3 次独立 instrumentation，宿主编排）
关键平台事实（决定编排形态）：**in-process pm revoke 会杀死 instrumentation**
（CI 41706417934 双 API 实证，ForegroundPermissionUiTest 既有登记）→ revoke
必须宿主侧；**pm grant 不杀进程**（#52 口径）→ 会话内授权升级可同进程；
**PARTIAL 的真实用户选中集合不可经 pm 确定性夹具化**（ForegroundPermissionUiTest
既有登记）→ 会话内级别变更以 grant 升级 NONE→FULL 为载体。类：
`B2PermissionRefreshDeviceTest`（androidTest 新类，复用 PartialSelectionUi 原语
+ FullPartialFullStateContract.visibleDisplayNameList + ensureG3Fixture 式
shell 身份 base64 文本注入通道）。
- **步骤1 fullDeckShowsFixturesInjectsLateMediaAndRecordsPid**（宿主 pm clear →
  grant → instrument）：FULL 地面真值恰 2 夹具（b2pr-a1/a2，shell 身份）→ 真实
  启动 deck 渲染（DECK_MARKER + 卡节点）→ PID 落盘状态文件
  （b2-permission-refresh-state.json，跨进程死亡持久）→ 会话内注入晚到媒体
  （b2pr-late）→ MediaStore 集合面恰 3 行 + **当前界面旧集合保持**（晚到媒体
  界面面 awaitGone 确定性缺席——无重扫触发）。
- **步骤2 宿主 pm revoke（系统强停，pidof 前后留证）→
  revokedRelaunchShowsGateAndOldSetNeverReappears**：依赖门（步骤1 状态文件
  phase1Pid）+ 新进程断言（myPid ≠ phase1Pid）→ 重启恢复 = REVOKE 后语义：
  权限门控屏呈现（PermissionRequestScreen"授权访问照片"）+ **旧集合不重现**
  （组合观察窗 10s：a1/a2/late/DECK_MARKER 全标签缺席不变量 + 应用包窗口有效性
  门，Phase2 同窗观察纪律）+ MediaStore 集合面 3 行仍在库（不可见=权限语义
  非删除）。设计"回收站不可见/空态或权限对话框"由门控屏语义覆盖（NONE 下
  TabulaApp 不组合，回收站/图库均不可达）。
- **步骤3 inSessionGrantUpgradeRerunsScanOnSameProcess**（宿主 force-stop 后
  instrument，无 grant 状态延续）：自有夹具 b2pr-c1/c2（集合面恰 2 行 shell 面
  地面真值——无权限侧不走应用查询）→ 无权限启动 = 门控屏 → pid 基准
  （pidof 恒等 = 应用在 instrumentation 进程）→ **测试侧 pm grant（前台持门控
  时授权——无后台窗口混淆；断言 GRANTED + 级别 FULL + pid 恒等 = grant 不杀
  进程同进程实证）** → Home→am start（onResume 修订重查，applyAccessLevel
  **级别变更分支** revision++）→ 截止轮询 60s：生产批次日志 currentBatch 行含
  2 夹具行 id（池集证据，按行 id 卷限定形式无关——Phase3 边界 2 同款解析）+
  门控按钮自窗口消失（权限翻转生效）+ 夹具卡节点可见（新内容到达消费面）→
  全程 pidof 恒等终验。
- probe 新段（tools/run-cloud-ui-probe.sh "B2 permission-refresh wiring
  three-step verification"，插于 FullRecovery 段后、exit 前）：各步 OK(1)
  精确计数门 + 失败形态门 + **dependency-skip**（步骤1 红 → 跳过步骤2/3 +
  b2pr_cleanup_fixtures 按名兜底；步骤2 红 → 跳过步骤3）+ 段前 pm clear/
  兜底 revoke/全夹具目录预清/图库空核对（FPF 同款）+ 段间 force-stop（步骤3
  前，消除步骤2 遗留进程/活动对 am start 与 pid 基准的干扰——FPF Phase3/
  FullRecovery 同款口径）+ collect 采集（logcat/状态文件/ui-failures）+
  wiring-grep 裁决摘要 + 段末权限回收。

### 10.4 如实登记（确定性边界）
- **在途扫描取消/旧读取晚返回不覆盖新权限态的严格竞态形态，设备层不可确定性
  构造**（生产 load 无注入点、自然扫描毫秒级不可控）——单测级
  MediaSnapshotLoadRaceTest（PR50）+ FPF Phase3 判据 10 受控竞争门覆盖；本类
  验证真实接线的端到端信息流终态（与 §9.3 ③ 同口径）。
- 会话内权限集合变化以 **grant 升级 NONE→FULL** 为载体：设计示例"revoke
  VISUAL_USER_SELECTED（若应用持有则不杀进程）"在 FULL 持有下级别不变
  （FULL 优先，MediaPermissionPolicy）→ 不触发 revision，无语义；revoke 存储
  权限系统强停为平台行为（不可同进程）——两形态均如实登记。
- **NONE→FULL 时 hasPermission false→true 使 TabulaApp 首次组合**——首次加载
  与 revision 递增在设备层叠加、无法分离二者贡献（revision 键控取消语义由
  单测与 Phase3 竞争门覆盖；步骤3 可断言的是级别变更分支的端到端接线）。
- 步骤1 的"立即 pm revoke"落为方法返回后宿主立即 revoke（in-process revoke
  杀 instrumentation 的平台约束）——注入与 kill 分离于宿主编排，语义不变。

### 10.5 执行记录
- 实现 + compileDebugAndroidTestKotlin 通过（B2PermissionRefreshDeviceTest.kt
  新增 + run-cloud-ui-probe.sh 新段；不动 app/src/main、app/src/test），commit
  **09df170**（09df1707bbd3fa1207e170529bdd65599c067347，+713 行：测试类
  +probe 段）推送 test/b2-to-master-2026-11-14——本单元唯一提交（任务 1 的
  合并为 Already up to date 无新提交，分支 head 自 e95f705 递进至 09df170）。
- CI：build-volume-confirmation.yml @ test/b2-to-master-2026-11-14 = run
  **43514530630**（@09df170，dispatch 一次）= **首轮全绿**：build（21:52→21:59）
  + device(API36)（21:59→22:17）+ device(API37)（21:59→22:18）三 job success。
  本段耗时 ~100s/API（22:16:13→22:17:51 API36），probe 总墙钟未触 1200s 预算。
  双 API 证据线同构（artifact logcat + 状态文件）：
  - 步骤1 T1：`t1 deck rendered with old set (card node seen: b2pr-a1.png)` +
    `t1 pid recorded: <pid>` + `t1 complete: late media kept out of current UI
    (no rescan trigger), old set stable, fixtures kept for step2`（晚到媒体
    界面面确定性缺席 + 夹具保留给步骤2）。
  - 步骤2 T2：`t2 new process confirmed: phase1Pid=<p1> myPid=<p2>`（强停后
    新进程）+ `t2 permission gate screen rendered after revoke relaunch` +
    `t2-post-revoke absence window ok: appWindowOk=50 labels=4`（组合观察窗
    50 次有效应用窗口读数、4 标签全程缺席）+ `t2 complete: gate shown, old
    set never reappeared, shell rows intact` + 自清 gone=true ×3。
  - 步骤3 T3：`t3 gate shown, pid baseline=<p>` + `t3 in-session grant applied,
    process alive (pid=<p>), level=FULL`（grant 不杀进程同进程实证）+
    `t3 gate dismissed (permission flip rendered)` + `t3 batch log contains
    both fixture ids: [<id1>, <id2>]`（生产批次日志池集证据——重扫描已提交）+
    `t3 complete: in-session grant upgrade wired to rescan on same process` +
    自清 gone=true ×2。
  - 各步 OK(1) 精确计数门双 API 全过（step1 22:16:48/22:17:01、step2
    22:17:20/22:17:33、step3 22:17:49/22:18:01）；b2pr-state 状态文件 pull
    rc=0 双 API（phase1Pid 落盘核对基准）。revoke kill evidence 宿主行：
    pid_before/pid_after 双空（instrumentation 退出后时序非确定，登记不强门——
    进程死亡核对由 T2 的 phase1Pid≠myPid 断言承担）。
- 分支状态：test/b2-to-master-2026-11-14 head = 09df170（= e95f705 + 1 提交），
  PR #79 含任务 1（git 整合）+ 任务 2（本段）全部内容。
