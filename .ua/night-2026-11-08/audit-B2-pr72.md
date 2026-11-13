# B2 PR #72 独立审计（2026-11-12，全新会话静态核验+CI 证据，未跑模拟器）

**总评：可合并。** 判据 1–10 全部有对应断言且无降级形态；身份/等待/依赖语义与生产时序匹配；约束零违反；CI 双 API 实跑三阶段 OK(1) 全绿。无"必须修复"项。

对象核对：PR #72 head = `ace7625`（gh pr view 实取），base = `test/partial-selection-ab-2026-11-08`（3492728），本地 FETCH_HEAD 同 OID。

## 逐项结论
1. **判据覆盖（10/10，无缺失）**：1→Phase1 前置权限态+可见集差恰={s1}+list==set+shell 6 行；2→seedCleanupGroup 生产写入口+引擎批次 [g1,g2] 持久键序+真实 UI 上滑 g1+生产 saveCheckpoint([gid],1)+flush commit+prefs 读回；3→writeExpectedState（visible 全集/组键/断点索引/已处理集合/月归属）+文件>0；4→probe revoke+Phase2 NONE 前置+Select photos 像素选 {0,1}+轮询 PARTIAL 级别；5→可见恰={b1,b2}+g1/g2/n1 shell 行在+b1/b2 rebuildRow 可读；6→跨进程读回组键/断点/acknowledged/回收站逐项一致+组目录仅 G 组+未永久完成（判据明文允许的"组记录保留+非永久完成"形态，全程未调 getCheckpointBatch）；7→Phase2 哨兵不可见+严格 2s 观察窗+shell 行在，Phase1/3 shell 行核对；8→grant+force-stop+pid≠phase2Pid+可见 5 成员无重复（list==set）无缺失；9→驱动 UI 前记录读回+引擎 getNextBatch 真实月行恰=[g2]+UI 续接 deck=g2+月份归属 identityKey/year/month 逐成员核对；10→真实生产链 loadAndApplyMediaSnapshot 受控晚到（旧扫描 NonCancellable 滞留 PARTIAL 投影 {b1,b2}），旧读取返回/提交只归新/无 error/新投影=真实全池−g1 四断言+终态重断言。
2. **身份断言强度：达标。** 进度记录（acknowledged/回收站/组键/断点）全部经 mediaIdentityKey（=`volumeName|id`，MediaStore 行 ID 跨进程稳定）；可见集合为唯一 displayName 全集等值（fpf-* 唯一名），非月名/数量替代；选择器 B={b1,b2} 经像素颜色（GRID_TILE_COLORS）+名称双重钉死。
3. **等待语义：正确。** awaitDismissed=最终消失+1.5s 观察窗（重现则续等），用于合法过渡期（sheet 退场/上滑动画/月行重试）；Phase3 g1-not-reentered 与 Phase2 哨兵用严格 awaitGone（100ms 轮询，**出现即报错**）——不变量"必须不出现"的正确形态：短暂消失不构成漏检风险（漏检方向是"出现"，消失无关）；"短暂消失再现"若为错误态会持续在屏而被任一 100ms 轮询命中，<100ms 闪现无状态意义。R1 exitCleanupViaSheet 红即为 awaitGone 首见即杀的实证，修正向推进语义方向正确。
4. **依赖门：一致。** requireDependency=文件存在+长度>0+JSON 解析成功+pid 键>0（runCatching 兜底 -1）；Phase2 门 phase1Pid、Phase3 门 phase2Pid 均在 @Before 首行。probe 对称：Phase1 红→跳 Phase2、Phase2 红（含级联）→跳 Phase3，dependency-unmet 日志+fpf_status 保持 1+按名兜底清理 6 夹具（content delete+rm -rf，防 @After 未执行残留）。
5. **约束：零违反。** diff 仅 5 个 androidTest 新文件+docs 快照+tools/run-cloud-ui-probe.sh（+172 行）；app/src/main、app/src/test 零改动；全文件 CR=0（probe 仅 1 处 CR 为 base 既有 `tr -d '\r'` 字面量，非本 PR 引入）；断言全截止轮询（无 Thread.sleep，仅轮询间隔 sleep）+失败留证（dumpTree/saveScreenshot，safeFileName sanitize `|`，tags 无 `|`）。
6. **CI 证据：绑定成立。** run 43055853277（workflow_dispatch，headSha=991307e）build+device(API36)+device(API37) 三 job success；两 API 日志实证 Phase1/2/3 各 OK(1) 实跑（夹具 push、instrumentation 类执行、无 dependency-unmet 跳过）；API36/37 工件（20/22MB）在案。PR head ace7625 与 CI head 991307e 差异仅 docs/B2-notes-snapshot-2026-11-11.md（+14/−1，docs-only），测试代码与 CI 绿绑定一致。

## 问题分级
- **必须修复：无。**
- **建议级（不阻塞合并）：**
  - CI 绑定于 991307e 而非 PR head ace7625——docs-only 差异可接受；若追求严格同 head 可空跑一次 CI 留证。
  - probe revoke 后 `sleep 2` 为宿主进程死亡编排固定等待——沿用 #52/#57 既定口径（进程边界时序，非断言路径），保持现状即可。
- **观察级：** 严格 awaitGone 理论上存在 <100ms 闪现漏检窗口——所有轮询式 a11y 核验的共性极限，且两处不变量（g1 已 acknowledged 不回批、哨兵不入选择集）的失败形态是持续在屏，实际风险可忽略。

结论：B2 判据 1–10 双 API 全绿成立，PR #72 达到合并标准。
