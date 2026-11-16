# 续接交接（2026-11-16，收口第二轮额度截止归档）

## 快照

| 项 | 值 |
| --- | --- |
| master | `52c9de60c3`（含 #77 工具+结果 / #79 B2+FPF+B2MR；组合 CI 43713220665 三 job 绿） |
| PR #79 | 已合并（52c9de60c3） |
| PR #77 | 已合并（b787aca5） |
| PR #80 | OPEN，head=`3fbb396e`（test/tc58-hash-recovery-2026-11-15），base=master，MERGEABLE |
| PR #81 | OPEN，head=`99970b7e`（test/stale-scan-2026-11-15，代码=69a9611+notes 尾随），base=master（已变基），MERGEABLE，**合并判据已达成（轮 D 43724140820 三 job 全绿）+差分复审 PASS**——待用户合并 |
| 变异分支 | test/stale-scan-mut-2026-11-15 @ c3c813f（变异-3，目标红证据）、test/stale-scan-mut1-2026-11-15 @ b19f4047（变异-1 no-op 对照）——保留不合并 |
| PR #12 | plan 分支 docs/continuous-development-2026-10-28 @ 8747510+（唯一游标+全链证据） |
| PR #80 轮次 | 4=InvalidTestClassError→cf930d5 修；5=NPE(4 dHash 静默 Failed)→aa2ee8f 插桩；6=被并发组取消；7=:196 直算 Failed("Cannot open input stream")→d64633a 位型夹具重设计（缺陷 B 结构修复+契约测试 3/3）→8=establish 10s×25 次"Cannot open input stream"全败（瞬态理论被推翻=持久性）→ac0de7a 截止轮询重试→**9=多角度归因插桩运行中（43735113962）→10=按诊断修复（额度内最后一轮）** |

## ✅ 根因已定案（轮 9 诊断，2026-11-16 00:46）——真实生产缺陷

轮 9 诊断铁证：**所有 open 路径全部成功**（主线程/Default 线程/openFileDescriptor/直接文件读 = 2278B ×4，appops=allow）而紧随其后的 computeDHash 仍 Failed("Cannot open input stream")——环境无罪，**代码有罪**：

`PerceptualHash.computeDHash` Step 2（bounds 探测）的 use-lambda 尾表达式 = `BitmapFactory.decodeStream(input, null, options)`，`inJustDecodeBounds=true` 按 SDK 契约**恒返回 null**（维度写入 options）→ `use` 返回 null → elvis 恒触发 → **computeDHash 对任何图片恒 Failed**。哈希计算链从未在设备上成功过；专清相似分组一直在"元数据预筛通过但哈希验证恒拒"的降级形态运行（latent 生产缺陷，被本次设备验收首次暴露——用户 §二 "发现生产缺陷时建立有效旧行为 FAIL 再做最小修复" 的预设场景精确命中）。

- **有效旧行为 FAIL**：轮 5-9 双 API 红 @ establish（Failed("Cannot open input stream") 指纹）
- **最小修复**：c382c3d（use-lambda 尾加 `Unit`——elvis 只守真实 open；一行+注释）
- **轮 10（43737578472，额度内最后一轮）**：同断言 PASS 候选——绿=§二 闭环+生产缺陷修复经设备同断言验证；红=保留诊断登记 blocked（解锁=下一会话按轮 9 诊断续修）
- 附带影响评估：修复后 ensureHashesComputed 首次可产出真实 dHash → 专清相似分组恢复哈希验证语义（行为变化面=正向修复，非新风险；既有单测不含 computeDHash 设备路径=PR28 Robolectric 限制口径维持）

## 原"未解之谜"段（已被上节取代，保留作排查史）

**现象**：测试进程对自己刚创建并发布的 MediaStore 行 `openInputStream` 返回 null（"Cannot open input stream"），持久 10s+（25 次重试全败，双 API），而**同一 uri 在 ~100ms 前的 awaitReadable（同路径 openInputStream+decodeBounds）成功**；shell `ls -l /sdcard/` 确认文件在（2278B）；MediaProvider 日志显示 open 已达 provider（"Open with lower FS"）但应用层收到 null。

**轮 9 诊断面**（3fbb396e，logcat 搜 "diag "）：主线程 vs Dispatchers.Default open 拆分、openFileDescriptor 精确异常、绕过 provider 的直接文件读、行 is_pending/_data 状态、appops、权限 dump。

**两大候选根因与对应修复**：
1. **发布后扫描使行 id 失效/换 id**（awaitReadable 在扫描前成功、扫描后旧 uri 持久 null）→ 修复：establishHashesWithTolerance 每轮重跑 loadAllData 取新鲜 uri（wg-TC58 分支 test 文件内，~10 行）。
2. **应用层对该行的 open 被系统面阻断**（appops/世代/云媒体策略）→ 修复：夹具改 **shell 所有权路线**（仓库已证模式：FPF/B2Permission 全部 shell-owned+grant=deck Coil 读取成功；内容变更用 shell 重写字节+touch+scan_file 升版，B2MR 注入同款）——彻底绕开 app-owned 行的读取面。

**修复后判定**：轮 10 三 job 绿+HashRecompute 段 OK(1)×2API = §二 闭环；仍红 → 保留全部诊断工件，登记 blocked（解锁=轮 9/10 诊断数据+上述两路线的下一轮实施），§二 其余内容（插件加固+守卫测试+位型契约）已独立成立。

## 未解登记（延续）

- **FPF Phase3 flake 高频化**："timed out waiting for 全局"（onSwitchToGlobal 全局批次等待 a11y 语义丢失家族）已双 API 出现（轮 A API36/轮 C API37/轮 8 API36），频率约 2-3/10 run；diag-closure2-burst-2026-11-15.md 含门禁政策裁决项（段级有界重试，呈用户）。
- PR #80 正文已载轮次链+审计结论；PR #81 正文已载 §三 完整交付。

## 证据文件索引（全部在 plan 分支 .ua/night-2026-11-08/ 与 coord/）

- 交接/诊断：handoff-closure2-2026-11-16.md（本文件）、diag-closure2-burst-2026-11-15.md
- 审计/复审：audit-recompute-pr80.md（需修复→已落实）、review-stalescan-mutation-delta.md（PASS）、audit-B2MR.md、review-B2MR-closure.md、audit-stale-scan-pr81.md（PASS）、audit-TC58-pr80.md、review-TC58-plugin-delta.md（PASS）、audit-RA-pr75.md、audit-RB-pr76.md（PASS）等 21+ 份
- 执行笔记：wg-Stale/docs/notes-stale-scan-2026-11-15.md（五步+矩阵+时间线+变异判定）、wg-B2/docs/notes-B2MR-2026-11-14.md（§7-§11）、wg-TC58/docs/notes-B2MR-2026-11-14.md（§9-§10 插件/修复）
- 报告：pre-perf-closure-report.md（收口报告含第二轮增量）、pre-perf-inventory.md（盘点）、morning-report-2026-11-15.md
- 原始工件：scratch-stale-logs/（轮 A-D 全量 log+轮 C artifact，wg-Stale 旁路本地）——关键 run 的 CI 工件在 GitHub Actions 永久保留

## 唯一 next_action（下一会话）

1. 读轮 9/10 诊断 → 实施对应修复（上表两路线之一）→ 若轮 10 绿：§二 差分复审 → PR #80 合并判据达成；
2. 用户裁决项：#80/#81 合并、FPF Phase3 门禁政策、性能阶段启动（#80/#81 与工具链 PR45/42/6 已 CLOSED 的事实——重开或以 #77 为测量基线）；
3. 恢复现场命令：worktrees 已建于 workspace（wg-TC58/wg-Stale/wg-Stale-mut/wg-B2/wg-Docs），全部推净无未提交跟踪改动。
