# 独立审计报告：PR #54（C1，TC57 撤销×并发提交不变量）

- 审计会话：总协调者会话（未参与 PR54 实现；实现者=包C 子代理会话）。
- 冻结对象：分支 `fix/photo-safety-faults-2026-11-08`，head `fe37616`（基 master `43a8da1`），单提交，diff 仅 ArchiveCommitEvidenceTest.kt（+99/-0）。
- 审计时间：2026-11-08 17:35–17:45。

## 检查结论（逐项）

| 项 | 结论 | 证据 |
| --- | --- | --- |
| 1. 冻结与范围 | **PASS** | diff origin/master..fe37616 恰 1 文件 +99/-0、单提交；PR 页 head=fe37616（gh pr view 快照） |
| 2. 断言真实性 | **PASS（1 条登记）** | 三测试各钉真目标：①闩锁确定性交错（afterSourceRead 阻断在途复制→撤销入口触发→assertNull+唯一复制+账本/源齐）；②双 action 场景（撤销只摘除待执行 B，cancelled.id==B）；③真并发 10 轮互斥不变量（两分支互斥且完备：cancel 赢⇒NOT_CLAIMED+零副作用；commit 赢⇒COMMITTED+恰好一次+账本源齐）。登记见下 |
| 3. "无 baseline FAIL" 声明 | **PASS（独立推演确认）** | claimPendingArchive=synchronized(pendingArchiveQueue){indexOfFirst+removeAt}，cancelLastPendingArchive=synchronized{isEmpty+removeAt(lastIndex)}——同一 monitor，复合操作互斥；单一 action 场景 claim/cancel 必然一胜一败，无双复制/双摘除窗口；与实现者声明一致 |
| 4. 变异区分力 | **PASS（有区分力）** | 若移除 monitor：双并发 claim 可产生双复制（insertCalls==2）→③的 commit-赢分支 assertEquals(1, insertCalls) FAIL；cancel/claim 各摘一次→cancel 非空+COMMITTED 并存→两分支断言均 FAIL。窗口窄但存在，测试覆盖该窗口 |
| 5. 相邻回归/隔离 | **PASS** | setUp 每 test 重建 provider+重置单例+删 copy_evidence.json/source_images.json；③逐轮 resetLedgerAndSourceRecords=clearDeletedUris(sourceUri)（已核语义=clearSourceImageRecords+copyEvidenceStore.removeAll，按 URI 精确清）；resetCopyState 清 afterSourceRead 闩锁（EvidenceTestProvider.kt:190-191 核对）；无跨测试污染 |
| 6. 本地独立复跑 | **PASS** | 临时 worktree@fe37616，`--tests ArchiveCommitEvidenceTest --no-build-cache`：**10/10、0 failure、0 error、0 skip**（JUnit XML 核对） |
| 7. 实现者登记项复核（MainActivity UX 竞态） | **确认登记成立，维持登记不修** | restoreImagesInOneTransaction 未置 isDeletingRecycleBin（只读核对）；数据级 journal+require 保护在（无双重删除路径）；接线层无单测基建，无有效 FAIL 可立 |

## 登记（非阻塞）

- **R1**：③的 10 轮并发对 µs 级窗口的压力有限（不变量二值、每轮必判，但窗口命中概率低）；作为回归钉已足够，若未来有人改动 claim/cancel 同步结构，建议同步扩轮数或加扰动。不要求本 PR 处理。
- **R2**：①②依赖 provider.afterSourceRead 闩锁的单次触发语义（notifyAfterSourceRead 置空回调）——已核对 EvidenceTestProvider 实现正确，登记该耦合以便未来读测试者理解。

## 总评

**可合并**。0 必须修复，2 条非阻塞登记。实现者的"无 baseline FAIL（生产已正确，本单元为不变量钉扎）"声明经独立推演与变异分析确认。回退=revert fe37616（纯测试文件）。
