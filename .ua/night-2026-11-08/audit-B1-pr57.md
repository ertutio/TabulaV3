# 审计报告：PR #57（包B B1，PARTIAL 选择集 A→B 两阶段）

日期 2026-11-09；审计者=独立只读（新会话，未参与实现，零代码修改）。gh pr view 57 实测：head=8df089ea05d9（与声明一致）、base=master、state=OPEN。

## 总评：**可合并**

四核验面全部通过；安全边界断言（零生产改动）成立；断言地面真值真实；CI 证据绑定与登记口径逐字核对成立。仅 2 处 P3 注释/措辞级瑕疵，不阻塞。

## 四项结论

1. **冻结与范围：PASS**。`git diff 43a8da1..8df089e --stat` = 恰 6 个新文件、1586 行纯插入（无删除）：androidTest 4（PhaseATest 217 / PhaseBTest 289 / StateContract 134 / Ui 675）+ tools/run-cloud-ui-probe.sh 113 + docs/night-2026-11-08-B1-notes.md 158。**app/src/main 与 app/src/test 在 stat 全清单中零出现（逐字核对）**。分支总 18 提交（verdict-B1 后 892f5ab..8df089e 恰 8 个）。
2. **断言真实性：PASS**。PhaseA：授权前查询空集 + 授权后地面真值恰={A1,A2}（`awaitDeadline` 返回 false 即 assertTrue 红，留树 dump+截图）；PhaseB：地面真值恰={B1,B2}（失败路径仍 assert 红，附 verdict/S4 诊断上下文）；哨兵双面断言（UI awaitGone + shell 行存在含 relative_path）；retention pre/post-switch 跨进程读回（pid assertNotEquals + assertRetention：A 组成员键/断点(组,1)/未永久完成/无 acknowledged）。无恒真轮询（全部截止时间+失败必抛：error()/assertTrue/IllegalStateException）、无吞失败；@After 夹具按名精确删除并断言消失。像素勾选判据=31×31 窗口白像素≥15，不收敛 3 轮矫正后 `error()` 红灯（Ui.kt driveCheckedCells）。
3. **CI 证据绑定：PASS（1 处口径澄清，见 P3-③）**。R16 run 42500080220：head_sha=50c9d88、三 job（build/API36/API37）全绿，实测一致。final-head run 42501864524：**实测 head=35e5683（8df089e 的前一 docs 提交，非 8df089e）**；API36 失败步骤原文 = PhaseB `IllegalStateException: no candidate visible for photos-permission-entry: [Photos and videos, 照片和视频]`（tapAny 30s 等待超时 = Settings 条目渲染等待，**非业务断言红**）；同 run API37 绿、PhaseA 在 API36 绿、R16 同 API 绿、50c9d88→35e5683→8df089e 两段均纯 docs（+5/+5 行核对）——"瞬态抖动、非代码回归"口径成立，notes-B.md 6.11 明确登记 head=35e5683=docs-only。expected_tests 声明核对：probe 门 PhaseA/B 各 ^OK(1) + 既有套件 8/3/2/2/2 + FULL 四阶段各 1，与 PR 声明一致（API36 log 实测 OK 计数吻合）。
4. **一致性抽验：PASS**。PhaseB 设置页重选链（AppInfo 深链→Permissions 导航核验→Photos and videos→行内 gear→像素驱动 {0,1}→确认回读）与 notes-B.md 6.6.1 设计（L127）方向一致；R11-R14 对设计的实证修正（行点击仅切模式→gear 入口、AppInfo 点击吞没→导航核验重试、月份成员视图无卡→收口为生产 buildMonthInfos 月行）均在 6.9 逐轮留痕且与代码对应。机制结论 (c)：诊断轮 run 42490539279（head 892f5ab，双 device job 红=诊断预期）job log 实测 8 处判定行 `verdict=(c) PICK_IMAGES committed but user-selected set unchanged (picker closed: com.android.providers.media...)`；notes 6.5/6.10 与 PR"最终状态"段如实登记（此前 6.5 如实记录 (b)/(c) 两解释均相容、经诊断轮裁定）。

## 问题分级

- **P1（阻塞）/ P2（需修复）：无。**
- **P3（不阻塞，3 处）**：
  - ① `PartialSelectionStateContract.kt` KDoc 仍写"阶段B 经 ACTION_PICK_IMAGES 重选"——机制已改设置页路径，注释陈旧（PhaseBTest 自身 KDoc / notes / PR 最终状态段均正确）。
  - ② `tools/run-cloud-ui-probe.sh` PARTIAL 段注释同写"ACTION_PICK_IMAGES 重选"，同为陈旧描述（脚本行为本身正确）。
  - ③ PR 描述上半部原始方案段保留 PICK_IMAGES 说法，需读至"最终状态"段方见真机制（下半段已如实更正且披露 docs-only 绑定口径）；另任务简报称"8 提交"，实测 43a8da1..8df089e 为 18 提交（verdict-B1 后恰 8），以实测为准。

## 证据索引

PR head：gh pr view 57（8df089ea05d9）；R16：run 42500080220 三 job 绿 @50c9d88；final-head 验收：run 42501864524 @35e5683（API36 失败步骤原文已核）；诊断轮：run 42490539279 @892f5ab（verdict=(c) ×8）；notes：coord/notes-B.md 6.5/6.9/6.10/6.11、分支 docs/night-2026-11-08-B1-notes.md。
