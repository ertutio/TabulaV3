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
- **R3 = run 43055853277（991307e，双 API 全绿，~25min）**：API36/API37 三阶段各 OK(1)，probe 数量门+失败 grep 门全过；Phase1/Phase3 月行均 attempt 0 命中、无 reopening 触发（重试为未触发的保险路径）。判据 1–10 全断言双 API 绿（映射表 §4 全兑现），通用纪律全程在案。
- 遗留：无。R3 = 收拢轮（全绿）；PR：base=test/partial-selection-ab-2026-11-08。
