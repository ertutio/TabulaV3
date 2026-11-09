# T01–T13 剩余项映射（复审者用，2026-11-09 03:00，基整合分支 e3b005f）

每行：剩余项 → 生产入口（文件#函数） → 断言/现状 → 现有证据。仅列**开放**项；已关闭组不列（全表见 ledger-A-remaining-assertions.md + 本夜 INDEX）。

| 组/项 | 生产入口 | 断言/现状 | 证据 |
| --- | --- | --- | --- |
| T03 exitingRightCard 裸 ID | `ui/components/CardStack.kt#recall 动画右卡淡出辅助`（~:2632） | 同 ID 跨卷约一帧误抑制、自愈、非定位缺陷（登记不修） | PR33 审计+包D 复审（audit-D1-pr58.md） |
| T03 A2 死代码 | `AlbumViewScreen.kt:214` filter 回退路径 | 生产不可达（getAlbumImages 恒供），口径登记 | C1 盘点表 |
| T04 真跨卷/进程死亡 | `RecycleBinDeletion.kt#deleteInBatches` 链 | 设备级 blocked 簇（合成测试不可替代） | PROGRESS 既有 blocked 登记 |
| T06 R15 成员缺席组持有 | `AlbumCleanupEngine#getNextBatch` 持有语义 | PR57 notes 登记（B 未分析无批次=生产语义）；#57 收尾经跨月夹具+seedCleanupGroup 使成员视图可断言 | PR57 收尾单元 + notes-B 6.12 |
| T06 焦点④调用方半边 | 位图调用方 recycle 纪律（24 处审计核对全自有） | 静态纪律，无测试钉（无反例不造） | PR35/包D 审计 |
| T07 撤销强停 | 权限撤销→OS 杀应用进程 | instrumentation 随宿主死亡（机制实证）→真机人工口径 | PR41/ForegroundPermission 登记链 |
| T07 生产授权回调消费 | `MainActivity#createDeleteRequest` launcher 回调 | **收尾单元进行中**（确认/取消回调恢复+in-flight 释放；不可靠则阻塞登记） | 子代理执行中（test/prod-auth-callback-2026-11-09） |
| T07 FULL→PARTIAL→FULL | 权限级别变化→门控+重建 | B2 未启动；PhaseA 原语+跨进程读回已由 #57 备好 | PR57 + notes-B |
| T08 真实测量 | `performance-baseline.yml`（paused） | 工具链三 PR 待合并；测量待专项授权 | E1 审查（report-E-perf-chain.md） |
| T09 覆盖安装人工清单 | `docs/upgrade-verification-checklist.md` | 步骤已写明，未实际执行（需设备+双包） | U11 登记 |
| T10 优化收益实测 | StorageStatsManager/归档链 | #59+收尾已交付正确性与对账夹具；收益数字依赖测量授权 | PR59 + ArchiveQueryCountTest/ArchiveReconcileTest |
| T12 依赖升级决策 | Coil 2.7.0/exifinterface 1.3.7/coroutines 1.9.0 | 排序已定（F1 报告）；升级 PR 另行安排（EXIF 定点回归 #63 已备转红契约） | report-F-toolchain.md + PR63 |
| T13 发布 | — | 交付候选=整合分支+汇总 PR；发布/合并默认分支均待专项授权 | 本表 |
