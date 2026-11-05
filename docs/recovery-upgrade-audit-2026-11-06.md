# 数据恢复与升级兼容盘点（任务3，2026-11-06）

盘点范围：master `502fce2`（PR41 合并后）。每行给出场景、既有覆盖（真实测试名，可追溯）、结论与剩余缺口。本轮交付**不新增生产改动**；缺口要么已有登记，要么列在文末待办。

## 一、进程重建

| 场景 | 既有覆盖 | 结论 |
| --- | --- | --- |
| 归档 action 排队后进程重建：不补发复制、卡片可恢复 | `ArchiveCommitEvidenceTest.queueRebuildDropsPendingActionWithoutSideEffects`（重建后 commit 旧 id → NOT_CLAIMED、零副作用） | 已覆盖 |
| 同一 action 并发提交/撤销交错 | `ArchiveCommitEvidenceTest.concurrentCommits…`（5 轮）/`cancelThenCommit…`/`commitOfUnknownActionId` | 已覆盖 |
| 回收站持久化：损坏/保存失败/原子读/并发更新/部分写 | `RecycleBinManagerTest`（corrupt/failedSave/atomicRead/journal/concurrentUpdates/failedPartialWrite 系列） | 已覆盖；`atomicReadRestoresLegacyBackup` 在 Windows 本机为已知环境失败（CI Linux 绿） |

剩余缺口：真实断电（掉电时序）无合成可测等价物，维持登记；`RecycleBinManagerTest` Windows 环境失败族见第三节注。

## 二、旧记录升级与迁移

| 场景 | 既有覆盖 | 结论 |
| --- | --- | --- |
| 回收站序列化：orientation 新旧 JSON、批量单事务 | `RecycleBinSerializationTest` | 已覆盖 |
| 清理组旧引用迁移：legacy 组 id（无卷前缀）升级为身份键、保留断点保存时间 | `GroupIdentityCleanupPersistenceTest`（seedLegacyGroup 写入 legacy id 后经真实引擎重建断言）；`fix/group-identity-cleanup-persistence`（PR 已合并） | 已覆盖 |
| 清理成员旧裸 id 升级：确认前必须先完成唯一身份升级 | `AlbumCleanupForegroundRefreshTest.legacyBareMembersRequireUniqueIdentityUpgradeBeforeAcknowledgement` | 已覆盖 |
| 同 ID 改内容旧哈希失效（ver_ 版本键）、旧格式保守重算 | `ImageHashCacheVersionTest` 6 项 + `SimilarGroupDetectorVersionTest` 2 项 | 已覆盖 |

剩余缺口：无新增。C1 消费者簇迁移台账（AppPreferences 冷却等按名合并为显式产品语义）维持既有登记。

## 三、回收站

| 场景 | 既有覆盖 | 结论 |
| --- | --- | --- |
| 删除恢复：recovery 不重复删、UNKNOWN 与现存分离、取消留 journal | `RecycleBinDeletionTest` 8 项 | 已覆盖 |
| 入口统一与 2001 项生产组合 | `MediaDeletionBatchTest` 7 项 + PR31 的 2001 项生产组合断言 | 已覆盖 |

**Windows 本机环境失败（2026-11-06 实证，非本轮引入）：** 干净 master worktree（502fce2）复现 5 失败——4×`AlbumCleanupForegroundRefreshTest`（`RecycleBinPersistenceException: 回收站记录未能读取或保存`，RecycleBinManager.kt:82 事务路径）+ 1×`RecycleBinManagerTest.atomicReadRestoresLegacyBackup`；同一失败族（回收站持久化事务），CI Linux 历史绿。判定为 Windows 本地环境差异（与登记在案的已知环境项同族），**不作为产品回归**；全量门禁以 CI Linux 为权威。

## 四、清理断点恢复与部分权限

| 场景 | 既有覆盖 | 结论 |
| --- | --- | --- |
| 断点行外部编辑后恢复：同对象替换最新行 | `AlbumCleanupForegroundRefreshTest.checkpointRestoreServesUpdatedRowsAfterExternalEdit` | 已覆盖 |
| 组成变化放弃断点、重开干净 | 同上套件（U9 轮 6 项） | 已覆盖 |
| 整组缺席（权限收窄）→ 持有不标记、访问恢复后续接 | `AlbumCleanupForegroundRefreshTest.fullyVanishedGroupIsHeldNotMarkedAndResumesAfterAccessRestore`（C4/T07 任务 A） | 已覆盖 |
| 月快照被空快照清空后按最新池重建续接 | DeckScreen 恢复通道（任务 A 配套；接线层由审计推演覆盖） | 覆盖（接线无单测基建，如实登记） |
| 部分权限期间退出、重开、恢复权限不丢进度 | 启动 onCreate 级别检查（既有路径 + 单测映射）+ 上述持有语义 | 已覆盖（单测级）；真实撤销路径 OS 强停杀 instrumentation，设备用例不可执行（实证登记），手工验收维持 blocked |

剩余缺口：PARTIAL 真实选中集合变化无法经 pm 夹具化（级别映射单测钉住，实机待验）；无新增缺口。

## 五、真实设备项（维持 blocked，不弱化）

- 真实跨卷移动/复制、热插拔卷变化：无设备条件，维持待实机验证。
- 其他应用媒体授权（SHARE 接收端、外部写入）：维持待实机验证。
- OEM 行为（ColorOS 等对 MediaStore update/IS_PENDING 的差异）：维持待实机验证。
- 覆盖安装/旧数据：验证步骤见 [upgrade-verification-checklist.md](upgrade-verification-checklist.md)，待人工执行。

## 六、本轮结论

任务3 的合成可测面在 master `502fce2` 已有完整覆盖，未发现**新的**可立约缺口；本轮产出为（a）上述可追溯盘点表、（b）覆盖安装验证步骤、（c）Windows 环境失败族的干净基线实证。后续代码级工作继续按台账最早 ready 项推进。
