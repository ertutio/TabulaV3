# notes-RB：任务 B——有效冷却 + 非空清理进度跨升级（upgrade-cooldown，2026-11-12 深夜）

分支：`test/upgrade-cooldown-2026-11-12`（= test/upgrade-overlay-2026-11-11 @ **545570e7**，升级框架基点）。
新文件（与 T13/任务 A 零共享）：`app/src/androidTest/java/com/tabula/v3/UpgradeCooldownSeedTest.kt`、
`app/src/androidTest/java/com/tabula/v3/UpgradeCooldownDeviceTest.kt`、`tools/run-upgrade-probe-cooldown.sh`、
`.github/workflows/upgrade-cooldown-verification.yml`。T13 关闭判定缺口之二见 notes-T13.md §5 末段登记
（注入冷却种子被 `clearAllPickRecords()` 合法清空，模拟器仅 3 张照片）。

## 1. 冷却入口与清理条件（43a8da1 生产源码实测，head 逐字节同）

- **写入口**：`RecommendationEngine.getRandomWalkBatch` →
  `preferences.recordImagesPickedByIdentity(newlyPicked.map { it.mediaIdentityKey() })`
  （RecommendationEngine.kt:251）。生产链：`MainActivity` L979 把
  `recommendationEngine.getRecommendedBatch(images, size, identityCandidates = allImages + deletedImages)`
  传入 `DeckScreen`；DeckScreen L544/609/639 调它取批（启动即取批，T13 R2 的重启清空行为实证该路径
  随 `am start` 触发）。每批把"新抽中的照片"按完整媒体身份键（卷|ID）记入
  `tabula_pick_timestamps.xml`（`time_<identityKey>`/`days_<identityKey>`，days 随机轮转 7/12/24）。
- **清理条件**：同函数 L253-257——`if (allImages.size <= batchSize) preferences.clearAllPickRecords()`。
  `DEFAULT_BATCH_SIZE = 15`（AppPreferences.kt:1222，取值域 coerceIn(5,50)）。3 张照片 ≤ 15 → 每次取批
  全清——这就是 T13 注入种子被合法清空的机制。
- **TTL 语义**：`cleanupExpiredPickRecords` 以 24 天上限清过期；`getPickCooldownIndex` 按
  `now - pickedAt >= days*24h` 跳过过期记录。冷却 days ∈ {7,12,24} → 刚写入的记录在升级+重启的分钟级
  窗口内恒有效（按 TTL 语义构造：pickedAt 取种子当下，升级后仍有效——无需改时钟）。
- **读回**：`getPickCooldownIndex()` → `PickCooldownIndex(byIdentityKey, byLegacyBareId)`。
  升级框架（545570e7）与 43a8da1 对 AppPreferences/RecommendationEngine/AlbumCleanupEngine/DeckScreen/
  MainActivity **五文件 diff 为空** → 本单元验证的是纯状态幸存，不是行为迁移。

## 2. 冷却种子构造（≥31 张、真实生产入口、确定性）

夹具 **N = 140** 张合成 JPEG（复用 `FullRecoveryStateContract.seedPhoto` 64×64，同月同 albumId）。
140 = 10 个清理组成员 + 冷却目标 T + 129 陪跑。库量 140 > batchSize(15) 恒成立 → 生产清理分支
永不触发。可用池消耗账（每次 deck 取批/引擎取批 = -15）：
OLD 启动 1 次（-15~45）→ 幸存用例排除断言（-15）→ 重启 ×2（-15~45）→ 幂等用例排除断言（-15）
→ 最坏 105，剩余 35 ≥ 15 → **任何断言点都不触发冷却回填**（回填 = 可用不足时按 pickedAt 升序
把冷却照片补进批次——T 的 pickedAt 最早，一旦回填 T 必现，会污染"不出现在推荐"断言；池量设计
排除该语义）。

种子步骤（UpgradeCooldownSeedTest，全部经 OLD 生产入口）：
1. `recommendMode` 默认 RANDOM_WALK（断言）+ batchSize 15（断言，固化进夹具）。
2. 三组清理进度经生产写入口：`saveAlbumAnalysisResult/saveGroupImageCounts/saveAlbumTotalImages/
   saveGroupsImageIds`（Phase1 seedCleanupGroup 模式扩为 3 组）→ `acknowledgeCleanupMembers`
   （生产确认入口，commit()）确认 g1 的 m1 + g2 全员 → `AlbumCleanupEngine.getNextBatch` 一次：
   引擎把"全员已确认"的 g2 自动打永久标记 `markAlbumGroupsPermanentlyProcessed`
   （AlbumCleanupEngine.kt:347-358 生产对账路径）→ `engine.saveCheckpoint([g1], 1)`（断点指向剩余
   成员 m2/m3）→ `flushCleanupPrefs`（Phase1 同款 commit 落盘信号）。
3. 冷却目标经**推荐生产入口**：循环 ≤7 次 `RecommendationEngine.getInstance(ctx).getRecommendedBatch(
   allRows, 15, identityCandidates = allRows)`。确定性论证：每批 take(15) 抽可用集；第 k 批后可用
   96...140-15k；当可用 ≤ 15 时 take 全取——T 未被抽中则 T ∈ 可用 → **最迟第 ceil(140/15)=10 批内
   T 必被 `recordImagesPickedByIdentity` 真实记录**（实际 ≤10 次循环，bound 12）。断言冷却索引含 T。
4. 定形：`removeImagesFromCooldownByIdentity(all - {T})`（生产"切换算法移除未浏览"入口，
   DeckScreen onRemoveFromCooldown 同款）→ 冷却恰 = {T}。T 的 pickedAt/days 固化进夹具。
   再以同值 `putLong("time_T").commit()` 同步落盘 pick prefs（T13 flushCleanupPrefs 模式，不加新键）。
5. 夹具 JSON `upgrade-cooldown-expected-state.json` 写 `getExternalFilesDir`（T13 同路径不同名）：
   albumId、三组成员/顺序/acknowledged/permanent、checkpoint {groupIds:[g1], index:1}、
   cooldownTarget{identityKey, pickedAt, days, displayName}、expectedBatch{groupIds:[g3,g1],
   images:[g3 四成员, m2, m3]}（OLD 引擎实拍，升级后须逐项复现）、recommendationPool、mediaNames。

## 3. 组语义与升级后断言（UpgradeCooldownDeviceTest，判据 3/4/5）

生产语义实测（43a8da1 源码）：
- `getCheckpointBatch`（AlbumCleanupEngine.kt:795-802）：断点组内**任一成员已 acknowledged →
  clearCheckpoint + 返回 null**（"旧索引无法判断保存与删除先后，安全从该原组剩余行重开"）——
  组 1 的续接在生产语义 = 磁盘断点幸存 + `getNextBatch` 交付剩余成员。
- `getNextBatch`（L347-421）：永久标记组排除（不复活）；交付行 = 成员 − acknowledged；组按
  memberKeys.size 降序合并（SMALL_GROUP_THRESHOLD=10, MAX_BATCH_SIZE=30）→ g3(4) 先于 g1(3)，
  g2(3) 已排除 → **expectedBatch [g3, g1] 顺序确定**。

用例 1 `verifyCooldownAndProgressSurvive`（重启前）：
1. 夹具 140 名 rebuildRow 身份键逐一 == 夹具 recommendationPool（媒体真值生产面）。
2. 冷却：`getPickCooldownIndex()` 非空且**恰含夹具 T 键**，pickedAt/days 与夹具逐位一致、未过期。
3. 排除行为（真实入口）：`getRecommendedBatch(allRows, batchSize)` → 结果非空、恰 batchSize 个、
   T 不在结果、结果 ⊆ 夹具池（无关媒体仍可推荐）。
4. 进度：acknowledged == {m1}∪g2 全员；permanent == {g2}（不复活：getNextBatch 的 groupIds 不含 g2）；
   磁盘断点 == ([g1], 1)；getNextBatch 交付 == 夹具 expectedBatch（组 1 剩余成员续接 + 组 3 保留）；
   回收站空（组进度未被复活成回收条目）。
5. 快照 `upgrade-cooldown-snapshot.json` 落盘为幂等基线。

用例 2 `verifyCooldownIdempotentAfterRestarts`（force-stop+重启 ×2 后）：
1. 快照逐字段复检零漂移（断点/组成员/acknowledged/permanent/回收/冷却目标 pickedAt+days）；
   冷却总量允许增长（重启 deck 取批合法新增），但 T 条目逐位不变。
2. 生产续接语义复检：磁盘断点仍 ([g1],1) → `getCheckpointBatch` 按 795-802 规则返回 null（规则
   跨版本一致）→ `getNextBatch` 仍交付夹具 expectedBatch → 冷却仍约束 T（getRecommendedBatch 再断言）。

## 4. 探针 / workflow / 门

- `tools/run-upgrade-probe-cooldown.sh`（T13 骨架，cd- 前缀，无 metadata 注入/无 T13 哨兵）：
  ①装 OLD app+androidTest、grant → ②跑 `UpgradeCooldownSeedTest`（OK(1) 门）→ pull 夹具 →
  ③root 直读门 A：`tabula_pick_timestamps.xml` 含 `time_<T>` 且 `days_<T>`；`tabula_album_cleanup.xml`
  含 acknowledged_members_ 与 processed_ → ④`am start` OLD（真实生产 deck 取批）→ pidof 门 →
  force-stop → root 直读门 B：T 条目**仍在**且 time_ 条目数 ≥ 16（判据 1 核心：正常启动+推荐流程后
  记录真实存在、未被 clearAllPickRecords 清空）→ ⑤覆盖安装 NEW（Success/无签名冲突）→ ⑥跑幸存用例
  → ⑦重启 ×2（pidof 门）→ ⑧跑幂等用例 → ⑨logcat FATAL 门 + 留证（全程 root 侧拉取 prefs 快照）。
- `upgrade-cooldown-verification.yml`：复制 upgrade-verification.yml 改引用；**OLD worktree 额外步**：
  把本分支 `UpgradeCooldownSeedTest.kt` 拷入 43a8da1 worktree androidTest 目录，并 digest 门——
  `git -C old-src diff HEAD` 必须为空（零 tracked 修改）+ status --porcelain 恰一条 untracked 种子文件 +
  `git rev-parse HEAD:app/src/main` == `git rev-parse 43a8da1:app/src/main`（生产源码未被夹具修改），
  全部留证 upgrade-cooldown-build-record 工件。触发器 workflow_dispatch + 本分支 push；并发组
  upgrade-cooldown-verification-。工件名 upgrade-cooldown-old-apks / -new-apks / -build-record /
  -verification-API*（与 T13 及任务 A 不冲突）。

## 5. 约束核对

- 只新增 androidTest + 自有 probe/workflow 新文件；app/src/main、app/src/test、
  run-upgrade-probe.sh、upgrade-verification.yml 零触碰。
- 与任务 A（回收记录，另一代理）零共享文件：类名/探针/workflow/结果前缀（cd-）/夹具与快照文件名
  全部 cooldown 专属。
- 合法失效核对：冷却 TTL 跨升级按语义仍有效（7/12/24 天 vs 分钟级窗口）；断点 7 天 TTL 同理；
  永久标记无 TTL。无需人为时间构造。

## 6. 运行证据

**R1（run 43270680122，commit f8061b4）**：build GREEN（digest 门：old worktree 仅 1 条 untracked
种子文件、tracked diff 空、oldMainTree before==after）；device API36+API37 同一单点红——
升级后幸存用例读夹具键 `permanentlyProcessedGroups` 抛 `JSONException: No value for
permanentlyProcessedGroups`（种子写键名 `permanentlyProcessed`，读写不一致）。**其余全部门绿**：
- 种子 `OK (1 test)`（31s，140 张合成 + ≤10 批确定性命中目标 + 定形 + 夹具落盘）
- root 门 A：pick prefs 恰 1 条目标 time_/days_；cleanup prefs 含 acknowledged_members_ + processed_
- **判据 1 核心门（门 B）**：OLD 正常启动 + 真实生产 deck 取批后，pick prefs time_ 记录数 = **16**
  （目标 + 15 张新抽取），目标条目仍在——`clearAllPickRecords` 未触发，T13 缺口的正面回归通过
- 升级后快照（evidence state）逐项正确：断点 [g1]/1、acknowledged=[17,20,21,22]、
  permanent=[g2]、recycleCount=0、目标冷却条目 pickedAt/days 逐位在池（cooldownIdentityCount=31
  = 目标 1 + OLD 启动 15 + 幸存用例排除调用 15，与设计消耗账一致）
修复 commit 56910d2：夹具键名统一为 permanentlyProcessedGroups。

**R2（run 43274154738，commit 56910d2）= 全绿（build + device API36 + device API37）**。
逐门证据（工件 upgrade-cooldown-verification-API36/API37 + upgrade-cooldown-build-record）：
- 三门 instrumentation `OK (1 test)` ×3 ×双 API（cd-seed/seed.txt、cd-verify/survival.txt、
  cd-idem/idempotency.txt）
- **判据 1**：种子经生产推荐入口（≤10 批确定性命中）+ 门 B 双 API 过——OLD 正常启动 + 真实
  deck 取批后 pick prefs time_ 记录 = 16（目标 + 15 张 deck 新抽取），目标条目未被
  `clearAllPickRecords` 触碰（T13 缺口正面回归双 API 通过）
- **判据 2**：升级前夹具固化目标身份键（volume|id）、pickedAt、days（生产轮转值）；幸存用例
  升级后读回 pickedAt/days **逐位一致**（双 API pickedAt match=True），TTL 未过期
- **判据 3**：幸存+幂等双用例：冷却记录读回非空且键一致、目标不出现在真实引擎推荐批次、
  无关媒体照常（批次满 batchSize）；force-stop+重启 ×2 后幂等零漂移（IDEMPOTENT_FIELDS
  逐字段）。post-prefs time_ = 46 = 16 + 幸存调用 15 + 幂等调用 15（重启 deck 走
  pending-batch 恢复不新取批——池消耗低于设计账，余量更大）
- **判据 4**：三组进度双 API 逐项过——组 1 磁盘断点 [g1]/1 幸存 + getNextBatch 交付剩余成员
  （m1 排除）；组 2 永久标记 [g2] 幸存且不复活（批次不含、回收 0）；组 3 成员/状态原样；
  acknowledged 集与夹具逐项一致；重启后 getCheckpointBatch 生产放弃规则（795-802）跨版本一致
- **判据 5**：预期全部来自种子夹具 JSON（成员/顺序/状态/期望批次 [g3,g1]+6 行），升级后
  逐项核对不重算；跨进程（force-stop→新 instrumentation 进程）读回全部结果
- digest 门：oldMainTree before==after（4d26ad65==4d26ad65）、old worktree 恰 1 条 untracked
  种子文件；APK SHA-256 六件入 upgrade-cooldown-build-record；logcat FATAL = 0（双 API）

**迭代计数**：2 轮（R1 双 API 单点红=夹具键名不一致 → 定向修复 → R2 双 API 全绿）；
无重复卡点，无阻塞登记。workflow 已注册可 workflow_dispatch（push 触发已实跑两轮）。
PR：#76（base master，分支含 T13 未合并提交——与 T13 #73 叠放）。
