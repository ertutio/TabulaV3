# 包C 夜间进度笔记（照片安全/授权/事务故障）

- 日期：2026-11-08。写者：包C（wg-C 工作区）。
- 基线：origin/master `43a8da1037ca50f34c9fabd8a41c2ac505c93824`（PR51/PR52 已合并）。
- 分支：`fix/photo-safety-faults-2026-11-08`。
- 方法：先按任务要求从**实际入口代码**（删除/复制/移动/撤销/恢复）核对覆盖，再选缺口交付。参考了 coord/ledger-A-remaining-assertions.md 的 T01/T02/T04 行，但以实际代码为准（全部指针已在 wg-C 树内复核）。

## 一、覆盖核对表（TC56/TC57/TC58，入口×既有测试逐项）

### TC56（更新路径返回 0 后：null 流/短复制/写或发布失败/同源别名/删源失败→CopiedButSourceRetained/目标未证明不删源/恢复不补发不可逆删除）

| 断言 | 状态 | 指针（wg-C 树内实际核对） |
| --- | --- | --- |
| null 流 | 已覆盖 | `MediaStoreCopySafetyTest#nullInputAndOutputStreamsFailAndRollbackOwnedTarget`、`missingInputStreamFails...`、`missingOutputStreamFails...`（生产：SystemAlbumSyncManager 复制事务回滚 owned target） |
| 短复制 | 已覆盖 | `MediaStoreCopySafetyTest#shortSourceEofAndShortDestinationWriteNeverPublish` |
| 写/发布失败 | 已覆盖 | `readWriteFlushAndCloseFailuresNeverPublishAndRollbackOwnedTarget`、`publishExceptionFailsAndRollsBackOwnedTarget`、`publishMustAffectExactlyOneRowAndFailureRollsBack`、`cancellationWhenPublishReturnsStillRollsBackAndPropagates` |
| 同源别名 | 已覆盖 | `insertedUriEqualToSourceIsRejectedBeforeOpeningOutput`、`insertedUriThatAliasesSourceIsRejectedBeforeOpeningOutput`、`filesCollectionAliasResolvesToSameMediaStoreItemAndCanBeCopiedSafely` |
| 目标未证明不删源 | 已覆盖 | CleanupEvidenceTest 全簇（无账本/目标缺失/同源/同尺寸不同内容→unverified）+ `SourceImageSafetyTest` + `AlbumManager.computeCleanableUris/getReverifiedCleanableUrisForAllAlbums`（账本候选 + 轻校验/深复核双闸门，删除前深复核 `verifyCopyIntegrity`） |
| 删源失败→CopiedButSourceRetained | 外部阻塞（沿用 PR43 口径） | move 路径无删源步骤（MoveSourceSafetyTest：0 行更新→失败不复制不删除）；cleanup 删源走 deleteInBatches 部分成功保留（BatchDeletionTest）；真实移动授权 blocked |
| 恢复不补发不可逆删除 | 已覆盖 | RecycleBinDeletionTest：`saveFailureStopsTaskAndRecoveryNeverDeletesAgain`、`journalCommitFailureCanBeReconciledIdempotently`、`cancelledCoroutineLeavesJournalForLaterRecovery`；`reconcileRecycleBinDeletion` 只查询+提交记录（RecycleBinDeletion.kt KDoc 与实现一致） |
| 移动 0 行更新 | 已覆盖 | MoveSourceSafetyTest#zeroUpdateIsFailureWithoutCopyOrDeleteForEitherMoveEntryPoint、MoveCopyItemResultTest#moveZeroRowUpdateClassifiesFailed |

### TC57（同 action 并发提交+撤销交错/注入复制与记录失败/排队后重建/每 action 至多一次副作用/排队不冒充成功/卡片与账本可恢复一致）

| 断言 | 状态 | 指针 |
| --- | --- | --- |
| 同 action 并发提交（至多一次副作用） | 已覆盖 | ArchiveCommitEvidenceTest#concurrentCommitsOfSameActionCopyExactlyOnce（claim 原子领取） |
| 撤销交错——顺序 | 已覆盖 | cancelThenCommitIsNotClaimed |
| **撤销交错——并发（commit 在途时 undo）** | **缺口→本单元交付** | 原无测试钉；`claimPendingArchive`/`cancelLastPendingArchive` 同 monitor（pendingArchiveQueue 为 Collections.synchronizedList+复合操作加 synchronized）应安全→补并发交错断言（见下） |
| 注入复制失败 | 已覆盖 | copyFailureLeavesNoLedgerOrSourceRecord |
| 注入记录失败 | 已覆盖 | evidenceWriteFailureKeepsSourceUnverifiedButArchiveCommits + CopyEvidenceStoreTest.writeFailureReturnsFalseAndPreservesPreviousContent |
| 排队后重建 | 已覆盖 | queueRebuildDropsPendingActionWithoutSideEffects（队列仅内存→NOT_CLAIMED+零副作用） |
| 排队不冒充成功 | 已覆盖 | cancelThenCommitIsNotClaimed、commitOfUnknownActionIdIsNotClaimed；COPY_FAILED 不写账本不写源记录 |
| 卡片与账本可恢复一致 | 已覆盖（合成面） | 复制失败→无账本/无源记录+COPY_FAILED（UI toast 由 MainActivity接线）；重建→NOT_CLAIMED |
| 回收站 journal 重建一致性 | 已覆盖 | RecycleBinManagerTest#newManagerRestoresPendingJournalAndMergesWithoutLosingIt、#failedPartialWriteRollsBackAndSilentCommitFailureIsDetected |

### TC58（同媒体 ID 改内容/临时读取失败后恢复/重启/旧缓存/专清推荐消费者/旧哈希失效有界重算/冷却进度保留）

| 断言 | 状态 | 指针 |
| --- | --- | --- |
| 同媒体 ID 改内容 | 已覆盖 | ImageHashCacheVersionTest#versionedRoundTripAndStaleInvalidation、RecommendationEngineContentInvalidationTest#staleGroupIsNotServedAfterContentEditWithinTtl、MediaStoreCopySafetyTest#sameNameAndSizeButDifferentContentIsNotReused |
| 临时读取失败→恢复 | 已覆盖 | 失败面：CleanupEvidenceTest#unreadableDestinationFallsBackToUnverifiedWithoutCrashing；恢复面：SimilarGroupDetector.ensureHashesComputed 对 `isFailed` 条目**每轮有界重算**（SimilarGroupDetector.kt:268-273）+ SimilarGroupDetectorVersionTest#failedLegacyEntryIsRetriedAndVersionStamped |
| 重启/旧缓存 | 已覆盖 | CopyEvidenceStoreTest#atomicStoragePersistsAcrossInstances、RecycleBinManagerTest#newManagerRestoresPendingJournal...、ImageHashCacheVersionTest#legacyEntryWithoutVersionIsStaleForVersionedReads |
| 专清/推荐消费者 | 已覆盖 | CleanupEvidenceTest、RecommendationEngineContentInvalidationTest（含 volumeSwapWithSameMetadataDoesNotServeStaleGroup） |
| 冷却/进度保留 | 已覆盖 | PickCooldownIdentityTest、GroupIdentityCleanupPersistenceTest、AlbumCleanupEngine 断点持有逻辑（getCheckpointBatch 临时缺席→持有不清断点） |

## 二、本单元选择与理由

**选交付：TC57 后半——撤销与并发提交的交错窗口**（任务候选1）。
理由：这是 TC57/候选清单中唯一在单测面完全无钉的交错（并发提交/复制失败/记录失败/重建均有钉）；且完全在 app/src/test 可证明面内。其余候选核对结论：
- 「复制发布失败后账本/源不变式未接线」：核对未发现缺口——账本唯一写入点 commitPendingArchive 且发布成功后才写；copyImagesByIdentity（普通复制）不写账本=保守（无证据→源永不进入可清理）；清理删除入口 performDeleteUris 仅对 confirmed 清账本（MainActivity:813 附近）。
- 「恢复 UNKNOWN/缺失后重复删除防护缺入口」：三删除入口（回收站/图集/原图清理）均核过：回收站走 deleteRecycleBinSelection+journal；恢复入口 restoreImagesInOneTransaction 与加载入口 loadAndApplyMediaSnapshot 均先 reconcile（MainActivity:684/189）；SnapshotCrossVolumeSoftDeleteTest 钉图集批量入口身份确认。
- 「进程重建后 journal/意图日志一致性」：RecycleBinManager pending journal 重建合并已有钉；归档队列重建（queueRebuild...）已有钉。

### 交付内容（app/src/test，3 个新测试于 ArchiveCommitEvidenceTest）
1. `cancelDuringInFlightCommitDoesNotDisturbTheSingleCopy`：afterSourceRead 闩锁造确定性交错（提交已领取+复制在途时撤销）→撤销返回 null、复制恰好一次、账本/源记录齐、源字节不变。
2. `cancelDuringInFlightCommitTargetsOnlyRemainingPendingAction`：双 action 场景——A 在途复制时撤销只摘除仍待执行的 B，A 副作用不受扰动、B 零复制。
3. `concurrentCancelAndCommitOfSameActionResolveExactlyOneWinner`：真线程并发撤销×提交×10 轮——互斥不变量（撤销赢⇒NOT_CLAIMED+零副作用+空账本；提交赢⇒COMMITTED+恰好一次复制+账本/源齐）。

### Baseline FAIL 声明
**无 baseline FAIL**（如实登记）：生产 `claimPendingArchive`/`cancelLastPendingArchive` 共享 `pendingArchiveQueue` monitor（AlbumManager.kt:117 `Collections.synchronizedList` + :1310/:1383 `synchronized(pendingArchiveQueue)` 复合操作），交错行为在基线即正确；本单元为不变量钉扎（断言补强），不改生产行为。

### 环境项（单列，不计产品修正）
- wg-C 与主克隆的 Windows 本地跑 gradle 报 `java.io.IOException: 文件名、目录名或卷标语法不正确`（SdkLocator validateSdkPath）。原因：`local.properties` 用单反斜杠转义（`sdk.dir=C\:\Users\...`），AGP 8.9 读取路径含非法序列。修复（环境级，未进仓库）：调用 gradle 时注入 `ANDROID_HOME=C:\Users\yuxuan\android-sdk` 环境变量。local.properties 为 gitignored 本地文件，两处均未改动。

## 三、验证记录

- 受影响类：`ANDROID_HOME=... ./gradlew :app:testReleaseUnitTest --tests "com.tabula.v3.data.repository.ArchiveCommitEvidenceTest" --no-build-cache` → BUILD SUCCESSFUL，10/10（7 既有+3 新）全绿（app/build/test-results/testReleaseUnitTest/TEST-...ArchiveCommitEvidenceTest.xml：tests=10 failures=0 errors=0）。
- 全量：`ANDROID_HOME=... ./gradlew :app:testReleaseUnitTest --no-build-cache` → BUILD SUCCESSFUL，**396 tests / 0 failures / 0 errors / 0 skipped**（59 个测试类 XML 汇总，Windows 本地）。
- 本轮纯单测单元，未 dispatch CI（按约定 PR 后再定）。

## 四、登记交协调者（发现但不在本包可写范围/不构成可证缺陷）

1. **恢复入口未置在途标志（登记不修）**：`MainActivity.restoreImagesInOneTransaction`（MainActivity.kt:682）未设 `isDeletingRecycleBin=true`，RecycleBinScreen 动作栏（RecycleBinScreen.kt:1271/1281）仅按 `selectedCount>0` 启用按钮——用户可在恢复协程在途时再触发同集合删除。数据级不变量经 journal+finishDeletion require+身份移除保护（核对无双重系统删除/无崩溃/无数据丢失），属 UX 层竞态（末写者胜）。MainActivity.kt 属协调者分配文件，登记不动。可单元化程度低（守卫在 Activity 内），建议设备验证或在协调者侧加守卫。
2. 其余候选均核对为已覆盖/外部阻塞（见覆盖核对表），无新缺陷登记。

## 五、PR 与剩余缺口

- PR：https://github.com/ertutio/TabulaV3/pull/54（分支 `fix/photo-safety-faults-2026-11-08`，基线 master 43a8da1，单测试提交 fe37616）。
- 剩余缺口（本包视角，均非本单元可修）：
  - TC56-REALAUTH：真实移动授权反馈（真机 blocked，PR43 口径维持）。
  - T07-PARTIAL/撤销强停：真机 blocked（沿用台账）。
  - CloudCleanupUiTest API37 间歇 RecoverableSecurityException：待诊断（包A台账已登记）。
  - 恢复入口在途标志（见第四节登记1）：协调者侧最小修复候选（MainActivity restoreImagesInOneTransaction 置 isDeletingRecycleBin / RecycleBinScreen 按钮加在途禁用）。
