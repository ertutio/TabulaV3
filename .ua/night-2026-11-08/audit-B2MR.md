# 独立审计：PR #79 增量单元 B2MR（46da489→0cbdb0e，androidTest 设备测试交付，2026-11-14）

复审角色：独立审计员（未参与实现）。
审计对象：分支 test/b2-to-master-2026-11-14 增量 **5d13365→0cbdb0e**（46da489 test 提交 + 0cbdb0e docs 提交）及连带 probe 预算 / workflow 超时修改；判据 = coord/b2-acceptance-criteria.md 判据 10 + coord/t13-overlay-acceptance.md 边界 2 设计。

## 结论：**需修复**（1 项最小修复，一行恢复；核心交付、判据映射与 CI 证据全部核实无误）

## 逐项

1. **增量范围与文件归类**（git diff --name-status 5d13365..0cbdb0e，逐文件归类）：
   - `A app/src/androidTest/java/com/tabula/v3/B2MediaAccessRevisionDeviceTest.kt`（+665，仅 androidTest）✓
   - `A docs/notes-B2MR-2026-11-14.md`（+108，仅 0cbdb0e）✓
   - `M tools/run-cloud-ui-probe.sh`（+70 行新段 + **1 行既有行顺带改动**，见修复项 1）△
   - `M tools/run-volume-device-probe.py`（仅 timeout 1200→1500 + 注释）✓
   - `M .github/workflows/build-volume-confirmation.yml`（仅 timeout-minutes 30→40 + 注释）✓
   - **app/src/main 与 app/src/test 零 diff**（`git diff 5d13365..0cbdb0e -- app/src/main app/src/test` 空输出实证）✓
   - 备注：任务书所写路径 `benchmark/run-cloud-ui-probe.sh` 实为 `tools/run-cloud-ui-probe.sh`（描述口径差异，非实现问题）。

2. **判据来源与映射**：
   - 判据 10 原文明确允许替代形态："或以既有 PR50 竞争门控的存在性+最终状态一致性断言，最终 UI/查询状态与第 9 条一致"——本类正是该形态：设备层承担"最终状态一致性"（集合面 47 全集 + batch-site-4 池集相等终局判别器），严格竞态形态归 PR50 + FPF Phase3 受控竞争门。映射成立 ✓
   - t13 边界 2"真实 MainActivity 接线级"逐条满足：权限 shell 变化→mediaAccessRevision→扫描重启（非注入式）；设计④"断言 UI 最终集合=权限变化后真值"= awaitCollectionFace(47)；设计⑤"重复两次"= 升级重启 ×2；约束"不动生产注入点、复用 Phase3 断言原语与哨兵纪律"全部遵守 ✓
   - 生产接线引用逐行独立核实：MainActivity.kt onResume `checkPermission(foregroundRefresh=true)`（342-352）、applyAccessLevel 级别变更分支 `mediaAccessRevision++`（395-407，++ 在 :405）、TabulaApp LaunchedEffect(revision)（576-620，revision 于 :584）、AppPreferences.kt:1222 `DEFAULT_BATCH_SIZE = 15`、RecommendationEngine.kt:253-258 `allImages.size <= batchSize → clearAllPickRecords()`——引用与源码逐字一致，无虚构 ✓

3. **场景真实性与断言面**：
   - PARTIAL(B) 经真实系统机制建立（#57 原语：Select photos→照片选择器 7 格像素驱动选 {1,2}），非 pm 夹具伪造；地面真值断言 visible 恰={b1,b2}（判 level=PARTIAL + VISUAL_USER_SELECTED granted 且 FULL denied）✓
   - 会话内 `pm grant` 升级（判 level=FULL）→ 升级重启 ×2 → 断言 a：集合面 = 47 名全集（**displayName 面**：set 相等 + size 相等 + 无重复，截止轮询）含哨兵 s1；断言 c：批次日志行 id 集 == store 47 行 _id 集（**身份面**，mediaIdentityKey="${volumeName}|${_id}" 同 _id 基，LocalImageRepository.kt:702）——双面齐备；"锚序"指 logcat 锚序列（artifact 实证齐全），断言本身为集合相等口径（与 FPF Phase3 边界 2 同款解析），如实 ✓
   - PID 身份断言存在且充分：`pid0=Process.myPid()` vs shell `pidof` 恒等 ×5（step1/step3/step4/step5/终验），grant 不杀进程口径复核 ✓
   - 断言纪律：全部判据为截止轮询（awaitCollectionFace / awaitDeadline / waitFor / 30s 批次日志轮询 / 6s 组合观察窗+有效性门）；HOME 后 sleep(1000-2000) 为 settle 延迟且后续均有轮询判据，与 B2PermissionRefreshDeviceTest:518-519 家规同款，无 sleep 碰运气；失败留证 dumpTree+saveScreenshot（timeout-collection-* / unexpected-gate-after-upgrade / timeout-b2mr-pool 等），文件名无 `|` ✓

4. **如实登记核对（重点）**：类头 84-101 行 + notes 明确登记"严格竞态形态（在途扫描取消、旧读取晚返回不覆盖）设备层不可确定性构造（生产 load 无注入点）"，改由单测 MediaSnapshotLoadRaceTest（app/src/test 实存）+ FullPartialFullPhase3Test 判据 10 受控竞争门（:415 起，NonCancellable + releaseOld.await 晚返回形态实存）覆盖。代码侧核实：无任何生产注入点（app/src/main 零 diff）、无 sleep 赌竞态、无注入式伪造——**登记与代码一致，诚实成立** ✓

5. **执行笔记与实际 diff/CI 一致性**：段名 "B2 media-access-revision PARTIAL->FULL device verification"（脚本 :648 实存）、OK(1) 精确门、段预算 480s（:654）、wrapper 1200→1500、device job 30→40、run id 43604993882、head 46da489（notes 如实写轮 1 head=46da489，0cbdb0e 为其后 docs 提交）——全部与 diff/CI 一致 ✓

6. **CI run 43604993882**（gh run view --json）：conclusion=success，headSha=46da489b，headBranch=test/b2-to-master-2026-11-14，workflow_dispatch；build + device(36) + device(37) 三 job 全绿 ✓。**Artifact 独立下载核验**（Tabula-VolumeFix-API36/37）：
   - API36 test.txt 尾部：`Time: 146.258` + `OK (1 test)`；API37：`Time: 137.294` + `OK (1 test)`——与 notes 逐字一致 ✓
   - logcat 锚序齐全：fixtures injected ×2（7+40）→ step1 PARTIAL(B) → step2 store=47 → step3 → step4 → step5a 47 names → step5b deck kept → step5c "47 ids == full 47 pool" → step5 → complete → residual=[] ✓
   - batch-site-4 恰 1 行 / batch-site-3 恰 15 行（logcat.txt grep 计数核实）✓

7. **probe 新段机械解一致性**：与 b2pr/FullRecovery 段逐项同构——pm clear + revoke 预清 → gallery 全库 0 行清点门 → mkdir → `timeout 480s am instrument … | tee` → `grep -Eq "^OK \(1 tests?\)"`（与既有 13 处同式；expected_tests=1 与类内唯一 @Test 一致）→ FAILURES!!!/INSTRUMENTATION_FAILED/Process crashed/shortMsg=/STATUS_CODE -[34] 五模式失败门（同式）→ `collect … 2 …logcat.txt adb logcat -d -v threadtime` / `collect … 0 - adb pull …cloud-ui-failures`（与 :488-490/:599-604 同参同式）→ 段末 `status=1` 传播 + 末段定位（`exit "$status"` 前）✓

8. **workflow / py 预算修改核验**：两文件 diff 仅为超时数值 + 注释行（workflow -1/+3、py -1/+5），无其他行为变化 ✓

## 修复项（最小）

1. **tools/run-cloud-ui-probe.sh 既有行顺带改动，超出申报形态且 notes 未登记**。FullRecovery 段既有行（增量基线 5d13365 :447，字节级核实）：
   `adb shell pm clear … | tr -d '<CR 字节>' | grep -Fx Success` → 0cbdb0e 改为 `tr -d '<LF 字节>'`（引号内 0x0D→0x0A，改动归属 46da489）。
   - 影响评估：CI 中性（ubuntu adb 输出 LF-only，两种形态均过；run 43604993882 FullRecovery 段绿即为证），但在 CRLF 型 adb 宿主上 "Success\r" 不匹配 `grep -Fx Success` → 该段假红。
   - 修复 = 该行恢复 CR 剥离语义（建议直接写家规式 `tr -d '\r'`，与同文件其余 8 处一致）；一行改动，不动验收证据、无需重跑验收。

## 登记（非阻塞）

- 本机无模拟器，设备判据以 run 43604993882 artifact 为证（已独立下载核验，非转抄实现者口径）。
- wg-B2/device-results/（b2mr-api36-test.txt、b2mr-api36-wiring-grep.txt）为未跟踪本地留档，与 notes "本地留档" 声明一致，不入库无碍。
- FPF Phase3 cleanup-sheet API36 抖动（上轮 run 43597856253）为既有登记事项，与本单元无关（本段设计已规避该路径），维持"后续独立单元跟进"。
