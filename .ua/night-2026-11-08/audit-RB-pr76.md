# 独立审计：PR #76 任务 B——有效冷却 + 非空清理进度跨覆盖安装升级（合并后，2026-11-14）

审计角色：独立审计员（未参与实现；实现 worktree=wg-RB，分支 test/upgrade-cooldown-2026-11-12）。
对象：PR #76（MERGED 2026-11-13T06:18Z，base=master，head a50b0de）。任务 B 自有提交 = f8061b4（主体）+ 56910d2（R1 定向修复）+ 025d889（种子唯一性+冷重启收紧），head a50b0de 为并入任务 A 分支（eac4b98，#75）的叠放合并。

## 结论：**PASS（证据链闭环）**——无 P1/P2，无需后续修复 PR；1 条 P3 笔记措辞瑕疵（非阻塞，无需后续 PR）

## 逐项
1. **范围（"4 新文件零生产触碰"）**：PR 自有贡献 diff eac4b98→a50b0de 恰 4 个新增文件（UpgradeCooldownSeedTest.kt、UpgradeCooldownDeviceTest.kt、tools/run-upgrade-probe-cooldown.sh、.github/workflows/upgrade-cooldown-verification.yml）；逐提交核对 f8061b4/56910d2/025d889 均只触 androidTest+tools+workflow；**app/src/main 与 app/src/test 零 diff** ✓。`gh pr diff --name-only` 对 master 的长文件清单来自叠放的未合并 T13/#73/任务 A/集成线提交（PR body 与 notes 已披露，非任务 B 授权改动）✓。
2. **冷却种子经真实生产入口**：SeedTest 循环调用 `RecommendationEngine.getRecommendedBatch(allRows, batchSize=15, identityCandidates=allRows)`（MainActivity/DeckScreen 同款调用面），经生产 `recordImagesPickedByIdentity` 落冷却（RecommendationEngine.kt:251，43a8da1 与 a50b0de 两版该文件 diff 为空，实测证实）；prefs 直写仅为 T13 flushCleanupPrefs 同款**同值 commit 落盘**（time_T 同值 putLong），非格式猜测；定形经生产 `removeImagesFromCooldownByIdentity` 入口。生产清理分支 `allImages.size <= batchSize → clearAllPickRecords`（L254-257）逐字核实：140 > 15 恒不触发；确定性论证（可用 ≤15 时 take 全取 → 最迟 ceil(140/15)=10 批命中 T）与代码语义一致，循环 bound 12 ≥ 10 ✓。
3. **升级断言（判据 2/3）**：pickedAt/days 全部来自 OLD 实拍夹具 JSON（upgrade-cooldown-expected-state.json，种子固化），DeviceTest 逐位比对（assertEquals on fixture long/int），非硬编码猜测；目标经真实引擎 `getRecommendedBatch` 断言不被推荐（批次满 batchSize=15 且 ⊆ 夹具池 → 无关媒体照常、空批防空转）；TTL 语义校验 now-pickedAt < days*24h；byLegacyBareId 残留为零 ✓。幂等：force-stop 冷重启 ×2（探针含 force-stop→pidof 证死→am start→pidof 证活）后 13 字段快照零漂移（IDEMPOTENT_FIELDS 含 cooldownTargetPickedAt/Days）✓。
4. **三组进度（判据 4）**：组 1 磁盘断点 ([g1],1) 幸存 + `getCheckpointBatch` 生产放弃规则（断点组含 acknowledged 成员 → clearCheckpoint+返回 null，AlbumCleanupEngine.kt:795-802 逐字核实，文件 OLD↔NEW diff 为空 → 跨版本一致）+ `getNextBatch` 续接交付剩余成员（m1 排除）；组 2 永久标记经生产对账（getNextBatch → markAlbumGroupsPermanentlyProcessed，L348-352）建立、幸存且不复活（批次不含 g2、回收站恒 0）；组 3 原样（members/状态按夹具核）；期望批次 [g3,g1]（memberKeys.size 降序）逐项一致 ✓。
5. **断言纪律**：测试内零 sleep；withEvidence 失败留证（状态快照落盘后重抛）；哨兵齐备（allowSyntheticMediaProbe 门、模拟器硬件门、权限门、seedPid 进程换代锚、夹具自洽、非空防空转）。探针 sleep 仅为宿主进程等待且每步 pidof 门验证；OK(1)+失败模式检测（FAILURES!!!/INSTRUMENTATION_FAILED/crash/负状态码）+logcat FATAL 门+root 直读门 A/B（门 B = OLD 真实 am start 后 time_ 记录 ≥16 且目标条目仍在——T13 缺口正面回归）✓。
6. **workflow digest 门**：OLD worktree=43a8da1，仅 1 条 untracked 种子文件（status --porcelain 恰一条 + tracked diff 空 + `oldMainTreeBefore==oldMainTreeAfter`），证据入 upgrade-cooldown-build-record 工件；43a8da1↔545570e 五生产文件 diff 为空实测复核 ✓。
7. **CI 证据链**：run 43274154738（R2）conclusion=success，headSha=**56910d2**（与引用一致）；PR head a50b0de **自有绿轮 run 43375382346**（build+API36+API37 全绿，2026-11-13T05:59Z，先于合并 06:18Z）——"代码同 head"疑虑在最终 head 上闭环，且 025d889（43367597137）、81a5d68（43371604913）均有独立绿轮 ✓。
8. **轮次登记**：R1 run 43270680122（f8061b4）failure 已登记且定性（双 API 单点红=夹具键名 permanentlyProcessed→permanentlyProcessedGroups 不一致，其余门全绿；56910d2 修复）；R2 全绿；R2 后 025d889 测试收紧改动亦有绿轮覆盖 ✓。
9. **notes-RB.md 交叉核对**：SHA（基点 545570e、绿轮 56910d2）、run 号、门语义、池消耗账（最坏 105/余 35 ≥15 不触发回填）、判据映射与代码/CI 全部一致 ✓。唯一瑕疵：§2 步骤 3 "循环 ≤7 次" 与同段 "bound 12/实际 ≤10" 及代码 `while (attempts < 12)` 不一致——P3 笔记措辞残留，不影响代码与证据。
10. **probe/workflow 改动**：run-volume-device-probe.py / upgrade-verification.yml / run-upgrade-probe.sh 等既有文件零触碰（window() 只调用不修改）；4 个新文件均为任务 B 专属命名（cd- 前缀、夹具/快照/工件名独立），与 T13/任务 A 零共享 ✓。

## 审计手段
gh pr view/diff、gh run view/list、gh api runs；wg-RB worktree git show/diff（43a8da1、545570e、f8061b4、56910d2、025d889、eac4b98、a50b0de）；未跑模拟器/构建。证据文件：coord/notes-RB.md、wg-RB 4 个新文件源码、CI run 43270680122/43274154738/43367597137/43371604913/43375382346。
