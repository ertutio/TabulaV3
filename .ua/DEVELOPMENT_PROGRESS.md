# 持续开发进度与续接记录

本文件记录事实和下一动作；执行规则见[总计划](DEVELOPMENT_ROADMAP.md)，验收规格见[验收约定](../docs/acceptance.md)。历史条目保留原记录，当前状态以本节与下表为准；旧的 PASS、accepted 或关闭不表示整个 T 组完成。

## 当前游标

| 字段 | 当前值 |
| --- | --- |
| 总任务状态 | `in_progress` |
| 已接受代码基线 | `master@b2d6b43e4ef31062a59e70568473dceb58b1a3e6`；#13 后依次合入 [#14](https://github.com/ertutio/TabulaV3/pull/14) → [#17](https://github.com/ertutio/TabulaV3/pull/17) → [#16](https://github.com/ertutio/TabulaV3/pull/16) → [#15](https://github.com/ertutio/TabulaV3/pull/15)。继续开发从该 master 建下一检查点分支。 |
| 验证输入及运行 | 最终组合输入 `87dd93c5a3cb8e8e81c1f1a77e3275d1c53749b1`：[41003765715](https://github.com/ertutio/TabulaV3/actions/runs/41003765715)，attempt 1，build/API36/API37 三项全绿。完整单测任务实际执行，无 FROM-CACHE/UP-TO-DATE；未取混合 APK 工件中的 XML，精确全套单测数未知。每 API 实际执行 VolumeDeletion 3 + Cleanup 6 + Restore 3，全数通过（共 24 项设备测试）。合并树与被测输入树相同。历史四 PR 原始运行与失败/重跑见保存点表。 |
| 当前检查点 | C0–C3 各有局部改进；各组仍 `PARTIAL`，详见下表 |
| next_action | O2（#18/#19/#20）与 #17 晚到竞争（#21）已交付待合并。T01-UIFEEDBACK 归档失败 Toast：接缝与用例已就绪（PR #22，堆叠 #21），**卡点在第 3 环**——真实 deck 路径两轮 CI 失败均已诊断：41105952670 该模式下 FixedTagBar chips 不渲染（改走顶栏「选择图集」弹层）；41108977165 弹层选择动作完成但**队列从未填充**（无 Queued/Committing/接缝日志，TimeoutException）——疑似 DeckScreen.onAlbumSelected 的 `currentImage?.let` 静默 no-op（选中时 currentImage 为空），需真机/本地模拟器实测弹层选择时的 deck 状态（或在 onAlbumSelected 加一次性 debug 日志确认分支走向）后再修测试或确认应用缺陷。**累计失败 2 次，假设与证据已留存**（PR #22 两次运行工件）。解除前转做：C1 跨卷身份消费者核验（TC13–TC16）、#20 合并后简化 #14 的 O2 注释（非必须）、#15 非阻断测试清理。总任务保持 in_progress。 |
| 尚未验证 | 真实跨卷与其他应用媒体、进程死亡和持久化失败；不得以合成探针代替。 |

## 本次合入的局部范围与剩余工作

| 检查点 | 本次局部边界 | 未完成、不得标为整组 PASS |
| --- | --- | --- |
| C0 | #14 空相册首次失败后重试显示真实照片（元数据空图集夹具）、#17 返回/重开/顺序切换显示正确照片、#16 取消夹具不落缓存，均已作为局部增量合入。 | #17 无查询进入延迟窗口握手，且等旧页内容显示后才切换，未证明旧慢结果晚到新页的竞争；#16 未直接对照未取消的相同失败输入。C0-T01-UIFEEDBACK、O2 诊断及其余 T01/T02/T05/T11a 差额仍按验收规格逐项核对，整组保持 PARTIAL。 |
| C1.1 | 哈希内容版本与失败重试的局部改进；#15 已合入推荐组缓存的 `(id,dateModified,size)` 排序版本列表精确比较，字段/成员变化使 TTL 内缓存失效，组合变更碰撞回归已补。 | 这是元数据版本失效，不保证字节变更必然更新元数据，也不是完整身份迁移；跨卷身份消费者及 TC13–TC16 仍待核验，整组保持 PARTIAL。 |
| C2 | 详情和清理入口逐批处理；仅确认项同步、UNKNOWN 停止后续请求并反馈未确认/未处理项 | 设备注入覆盖删除后 UNKNOWN 和源记录保留，不证明被系统删除的源文件仍存在；真实部分 UNKNOWN 与两入口完整 UI、进程死亡后恢复及持久失败仍待验证。本次有限 NonCancellable 落地不构成崩溃恢复日志。 |
| C3 | 请求身份包含 URI、内容版本与实际解码选项；窗口淘汰、失败重试和过期回调隔离 | 本次单测及设备运行仅证明实际执行的夹具；两种 CardStack 模式的真实预热、取消和重入仍需专用设备断言，不能用通用设备绿灯替代。 |

以下历史记录保存当时的测试结果和设计意见；冲突处以上表为准。历史头 SHA 的运行只证明对应输入、设备和实际执行夹具。

## 已接受的证据

- [#11](https://github.com/ertutio/TabulaV3/pull/11) 已合并；#10 已作为被覆盖 PR 关闭，未单独合并。merge 的父提交是原 `d2038b8e0dc8c99fbdf2c754e88dec0a8078a725` 与测试提交 `b2facebf5e6137ff0c15d1a1eedf1fd0bb5bc1cf`。
- [run 40680146410](https://github.com/ertutio/TabulaV3/actions/runs/40680146410) 在该测试提交上成功：build、API36、API37 三个 job 成功；每台 VolumeDeletionDeviceTest 3 项、CloudCleanupUiTest 2 项、CloudRestoreUiTest 2 项，共 14 项实际设备测试通过。
- 每台登记 17 项合成媒体，清理前 15 项存在、2 项已由正文删除，清理后 17 项均确认不存在；集合外哨兵保护断言通过并独立清理。每台三个测试窗口的 `issues=[]`，系统进程身份稳定，三条 volume 观测的 external_primary 为 mounted/recent，actuallyUnmounted 为空。
- API37 使用 cmdline-tools 22.0、AVD target `android-37.0`、image revision 7；实际启动 emulator 37.2.12.0。API36 使用原工具 12.0、target `android-36`、image revision 7。保留环境身份再讨论跨环境复用。
- 本轮 `testReleaseUnitTest` 为 `FROM-CACHE`，没有本轮新执行/计数。此前 run [40650966680](https://github.com/ertutio/TabulaV3/actions/runs/40650966680) 的 XML 汇总为 176 tests、0 failures/errors/skips；这不是未来固定测试数，也不能据此把全部 TC 判为 PASS。
- [run 40717554994](https://github.com/ertutio/TabulaV3/actions/runs/40717554994)（workflow_dispatch，ref docs/continuous-development-2026-10-28，head sha `6a685e3ed4440f0c8a9f40a5290dc2600eb6facc`）三 job 成功：build + API36 + API37。设备逐例：API36 旧探针 OK (2 tests)；API37 VolumeDeletionDeviceTest OK (3)、CloudCleanupUiTest OK (2)、CloudRestoreUiTest OK (2)（singleRestoreUpdatesAlbumWithoutManualRefresh / batchRestoreUpdatesAlbumWithoutManualRefresh）。即本分支 C0.1/C0.3/C1.1 改动在两台云模拟器实测无回归，且 T01 清理确认集、T02 单张/批量恢复接线取得真实 UI 证据。

## 已交付部分：保留并补差额

下表“已交付”只指列出的子边界；不表示整个 T 组及所有 TC 已验收。用当前源码/测试核对具体字段和调用者，不按旧计划的 `[ ]` 重做功能。

| 原编号 | 已有实现与测试入口 | 仍需在总任务内核对的范围 |
| --- | --- | --- |
| T01a–d 的主要实现 | SystemAlbumSyncManager 复制/移动保守路径；CopyEvidenceStore；AlbumManager action 领取及深核验。MediaStoreCopySafetyTest、CopyEvidenceStoreTest、ArchiveCommitEvidenceTest、CleanupEvidenceTest；CloudCleanupUiTest 实测确认集合及失效副本保留。 | 逐项对照全组故障/取消/重建与 UI 反馈；云探针不能代替其他应用媒体、真实多卷或所有进程中断场景。 |
| T02a | LocalImageRepository.projectSnapshot、MainActivity 两条加载/恢复刷新；ProjectSnapshotTest、CloudRestoreUiTest。 | 原始快照、软删除、并发与重建的完整组级要求。 |
| T02b | decideRestoreAction、RestoreDecisionTest。 | PRESENT/MISSING/UNKNOWN 贯穿真实恢复入口及失败保留。 |
| T02c | RecycleBinManager 序列化/批量事务、RecycleBinSerializationTest；单张/批量恢复 UI 设备测试。 | 新旧 JSON/部分失败/持久化中断等尚未逐项绑定的断言。 |
| T03r1 / r2 | MediaIdentity.kt、MediaItemKey/mediaItemKeyFromUri；SystemBucket 卷/路径分组、mergeBucketsByName；MediaItemKeyTest、BucketIdentityTest。 | Album 层及其余持久索引/偏好/统计/哈希/UI 的完整消费者迁移；显式同名合并不等于真实身份迁移完毕。 |
| T05 的数据链 | AlbumManager.getAlbumDetailImages、AlbumViewScreen、AlbumDetailImagesTest。 | 首次空列表错误与重试、切换/取消及旧结果覆盖的真实 UI 证据。 |
| T06a | OwnedBitmaps.kt、OwnedBitmapsTest，既定 CardStack 调用点。 | 预加载请求身份、Disposable/scope、动画/替换/取消/退出的完整生命周期。 |
| T11a | ImageFeatureDetector、ImageFeatureCacheStore、ImageFeatureState；ImageFeatureDefinitiveTest、ImageFeatureCacheStoreTest。 | 必要重试/取消等组级断言；T11b single-flight、有界缓存与测量尚未因此完成。 |

## 历史就绪与待办队列（状态以当前游标为准）

子项状态使用 `ready / in_progress / verifying / review_pending / blocked / accepted`；总任务另使用 `ready / in_progress / awaiting_human / complete`，其中 `awaiting_human` 表示实现已保存但尚需具体人工评审、设备或决策。只有实现、相关断言、证据和必要评审均满足才标子项 `accepted`。一组可用多个子行拆分，以 `C1.1` 等稳定编号续接。

| 检查点 | 初始状态 | 下一边界 / 依赖 |
| --- | --- | --- |
| C0：T01/T02/T05/T11a 差额 | ready | 从当前 next_action 开始；已交付部分保留，未测断言逐条补证据。 |
| C1：T03 消费者迁移 | ready | 先盘点每簇旧键/读写者，再迁移首簇；r1/r2 契约已存在。 |
| C2：T04 统一删除 | ready（接口/测试准备） | 先列实际入口与按项结果；涉及 C1 的接入等该簇接口稳定。 |
| C3：T06 预加载生命周期 | ready（定位/回归） | 可独立补请求/释放测试；版本接入依赖 C1 明确契约。 |
| C4：T07 媒体同步/移动 | ready（契约盘点） | 真实接入依赖 C1/C2 受影响接口及 C0 安全约束。 |
| C5：T08/T09 工具与基线 | ready（工具 fixture/兼容分析） | 先核 PR6 暂停与工具；真实测量、工具链改动及校准分别保存边界和授权。 |
| C6：T10/T11b 优化 | blocked（有效基线/接口） | C5 的有效性能参考及受影响正确性稳定后执行；不虚构已冻结指标。 |
| C7：T12/T13 最终交付 | ready（覆盖清单） | 可先补验收映射；依赖升级、覆盖安装、最终评审及发布各按其前置/授权推进。 |

`ready（准备）`只允许执行写明的准备范围，不表示可提前完成依赖尚未满足的接入。一个子项被阻塞时保持精确边界，转到下一独立就绪项。

## 历史 C0 差额登记（状态以当前游标为准，对照 docs/acceptance.md 组级矩阵）

状态 = PASS（断言有单测/持久证据）/ PARTIAL（逻辑在但仅静态/编译级）/ BLOCKED（需设备/云/权限）/ MISSING（未实现）。基线：本分支 e30ab1c；testReleaseUnitTest 176 项 1 失败（基线既有 RecycleBinManagerTest.atomicReadRestoresLegacyBackup）。

| case | 断言 | 证据入口 | 状态 |
| --- | --- | --- | --- |
| C0-T01-SAMEALIAS | 同源/别名不产生可删源证据（插入即拒、拒写拒删源） | MediaStoreCopySafetyTest.insertedUriEqualToSourceIsRejected…/…Aliases… | PASS |
| C0-T01-CONTENT | 同名同大小不同内容不产生证据、不删源 | MediaStoreCopySafetyTest.sameNameAndSizeButDifferentContentIsNotReused；CleanupEvidenceTest.destinationWithSameSizeButDifferentContentIsRejected | PASS |
| C0-T01-STREAMS | null/短读写、EOF、flush/close/publish 失败不发布且回滚仅目标 | MediaStoreCopySafetyTest（missing/null/short/publish 系列 29 项） | PASS |
| C0-T01-CANCEL | 插入后/拷贝中/发布时取消回滚并传播 | MediaStoreCopySafetyTest.cancellationAfterInsert…/DuringSourceCopy…/WhenPublishReturns… | PASS |
| C0-T01-LEDGERFAIL | 复制成功账本写失败→源记录存在但永不入清理候选 | ArchiveCommitEvidenceTest.evidenceWriteFailureKeepsSourceUnverified… | PASS |
| C0-T01-CONFIRMSET | 确认集合固定、深度复核只缩减、新增候选不删 | CleanupEvidenceTest；run 40717554994 CloudCleanupUiTest 2 项（哨兵保护+精确清理） | PASS |
| C0-T01-UIFEEDBACK | 归档失败 Toast、清理保留 Toast 真实可见 | MainActivity commitPendingArchiveWithFeedback / 清理路径 | BLOCKED |
| C0-TC57-CLAIM | 同一 action 并发提交至多一次复制副作用；撤销后提交 NOT_CLAIMED | ArchiveCommitEvidenceTest.concurrentCommits…（5 轮）/cancelThenCommit…/commitOfUnknownActionId | PASS |
| C0-TC57-REBUILD | 排队后进程重建：不补发复制、卡片可恢复 | ArchiveCommitEvidenceTest.queueRebuildDropsPendingActionWithoutSideEffects（重建后 commit 旧 id → NOT_CLAIMED、零副作用） | PASS |
| C0-T02-SNAPSHOT | 原快照−软删除派生浏览/计数/封面；空集合原样 | ProjectSnapshotTest 6 项 | PASS |
| C0-T02-RELOAD | 加载/刷新/恢复路径接线后重载一致 | 接线静态核对 + run 40717554994 CloudRestoreUiTest（恢复后相册无手动刷新即更新，走投影加载） | PASS |
| C0-T02-RESTORE3S | 普通项恢复前 PRESENT/MISSING/UNKNOWN 核验，未知保留缺失不入图库 | RestoreDecisionTest 3 项；run 40717554994 CloudRestoreUiTest 单张恢复真实 UI | PASS |
| C0-T02-RESTORE-UNKNOWN-UI | UNKNOWN 恢复保留记录、反馈可见、解除注入后重试成功 | run 40746123075 双设备 CloudRestoreUiTest.restoreKeepsUnknownEntriesWithFeedback 通过（FileOperationManager debug UNKNOWN 注入；Toast 文本已在 run 40732503499 日志捕获） | PASS |
| C0-T02-ORIENT | orientation 新旧 JSON 往返、旧格式加载再保存不丢 | RecycleBinSerializationTest 3 项 | PASS |
| C0-T02-BATCH | 批量恢复单事务、未知保留、部分失败状态可见 | RecycleBinSerializationTest.batchRemove…；run 40717554994 CloudRestoreUiTest.batchRestoreUpdatesAlbum…（真实批量恢复 UI） | PASS |
| C0-T05-MODEL | 详情直连完整 ImageFile、无映射 N+1 | queryImages 直连（结构性）；AlbumDetailImagesTest | PASS |
| C0-T05-MS | DATE_MODIFIED 全仓库毫秒一致 | AlbumDetailImagesTest（秒→毫秒断言）；queryImageFromUri 已删除 | PASS |
| C0-DETAIL-EMPTY-RETRY | 详情加载失败错误/重试可见；解除注入后重试成功 | 非空路径：run [40746123075](https://github.com/ertutio/TabulaV3/actions/runs/40746123075)（sha e13fcea）；空态分支：PR #14 新增 albumDetailEmptyAlbumLoadFailureShowsErrorAndRetryShowsRealImage，run [40910570752](https://github.com/ertutio/TabulaV3/actions/runs/40910570752)（sha a9c5962）双设备通过（元数据空图集构造绕开 O2；断言空态专属文案+重试、解除注入后插入真实照片重试渲染其 displayName） | PASS（断言即"错误/重试可见+重试后真实图片显示"；切图集后旧结果不覆盖仍属 O1 待办） |
| C0-T11A-CACHE | 忙碌/快速模式/失败阴性不入缓存；版本/开关失效；阳性恒缓存 | ImageFeatureCacheStoreTest 7 项 | PASS |
| C0-T11A-DETECT | 快速模式阴性 definitive=false；无失败动态阴性 definitive=true | ImageFeatureDefinitiveTest 2 项 | PASS |
| C0-T11A-RETRY | 必要重试与取消不落缓存 | #16 已合入：阻塞流取消夹具未缓存、另一个正常 JPEG 对照可缓存；最终组合单测任务实际执行并成功。未取消的相同失败输入尚未直接作对照；Composable 重入接线仍为结构核对。 | PARTIAL（限定夹具通过；同输入反事实、其他取消阶段与重入真实 UI 尚待验证） |
| C0-TC56-MOVE | 移动回退不删源；更新 0 行/异常/preQ/取消不复制 | MoveSourceSafetyTest 8 项 | PASS |
| C0-TC56-RESULT | 移动逐项返回 Moved/CopiedButSourceRetained/NeedsPermission/Failed | 结果枚举未实现（现返回 Uri?/null） | MISSING（属 T07/C4） |
| C1.1 TC58 哈希版本 | 同 ID 改内容旧哈希失效（版本键 ver_）；旧格式条目保守重算；失败条目每轮有界重试一次并按当轮版本落盘；cleanup 同步清 ver 键 | ImageHashCacheVersionTest 6 项 + SimilarGroupDetectorVersionTest 2 项（断言不依赖解码成败，可区分旧行为） | PASS |
| C1.1 其余消费者 | Album/元数据/偏好/统计/UI 六簇的旧键盘点 | C1 消费者盘点表 | PARTIAL（旧的不迁移决定不代表跨卷身份迁移完成） |
| C2 T04-入口统一 / `review_pending` | 新增 deleteMediaUrisBatch（与 deleteRecycleBinBatch 同构：错误分类仅 INVALID_REQUEST 可拆分、授权拒绝 Cancelled、授权后逐项核验 UNKNOWN 隔离）；详情删除 performDeleteBatch 与原图清理 performDeleteUris 两入口接入 deleteInBatches（回收站入口本就是样板）；详情回调 Boolean → deleted set，UI 按"确认删除数/未能删除数"如实反馈 | MediaDeletionBatchTest 7 项（含 250 项分批、部分成功保留、取消不请求后续批、UNKNOWN stopReason、仅无效请求可拆分）；设备 run 40723464281 API37 全绿、API36 CloudCleanupUiTest 两例超时（截图显示应用停在图库、无崩溃，对话框未出现——探针单次等待）；**同 SHA 重跑 [run 40726796077](https://github.com/ertutio/TabulaV3/actions/runs/40726796077) 三 job 全绿** → 判定为模拟器时序抖动而非 C2 回归 | PASS（附两次 run 与抖动记录；建议探针加对话框重试） |
| C3 T06b-预加载生命周期 / `review_pending` | PreloadingManager 重构：请求键含内容版本（cardMemoryCacheKey 共享函数，六处键位统一）、preloadedKeys 按键记忆（换批/内容编辑/失败自然失效）、排队与结果分离（listener 成功入集/失败移除）、按索引跟踪 Disposable 并在换批/重置/释放时取消、pause 同中心可重试、dispose() 释放自有 scope（两种 CardStack 模式接线）；CardStack 缓存读取键同步 | PreloadingManagerTest 7 项（键含版本、同索引换批不跳过、失败重试、成功不重复、暂停后同中心重试、过期 Disposable 取消、dispose 全取消）；接线静态核对 | 单测 PASS；**设备 run [40751316401](https://github.com/ertutio/TabulaV3/actions/runs/40751316401)（sha d4866be）三 job 全绿**（双设备 14 项探针） | 结果回调为编译级验证；T06a 所有权保持 |

| C2-UNKNOWN-UI / blocked | UNKNOWN 提示在详情/清理真实 UI 的可见性 | 同上接线 | BLOCKED（需设备探针注入场景，与 C0-DETAIL-EMPTY-RETRY 探针同批） |

## 观察与证据缺口

| 编号 | 类型与当前事实 | 最小下一步 | 影响 |
| --- | --- | --- | --- |
| O1 | 部分完成（2026-10-30）：#14 空态失败/重试真实照片、#17 返回/重开/顺序切换、#16 特征预加载取消夹具均已合入，证据见本轮保存点。 | 补查询进入慢加载窗口的确定性握手，再让旧查询在新页打开后结束，检查新页不被覆盖；#16 补相同失败输入的未取消对照；C0-T01-UIFEEDBACK 仍就绪。 | 有限夹具通过不代表所有取消阶段或跨页竞争已验收。 |
| O2 | **已确诊并修复（待合并）**：#18 诊断（run [41082467467](https://github.com/ertutio/TabulaV3/actions/runs/41082467467) / [41086854893](https://github.com/ertutio/TabulaV3/actions/runs/41086854893)）在 API37 逐阶段复现旧占位链：insert/write/unpublish 全部成功，**delete 前导点命名的自有占位项抛 SecurityException（has no access）**；同链非 dot 名删除成功、API36 对 dot 名全绿 → 失败阶段=unpublish 后 delete，触发条件=占位名前导点（旧"scoped storage mkdirs"归因不成立）。真实 createBucket 返回 NULL（无异常）→ createAlbum 抛「无法创建图集文件夹，请检查存储权限」。 | #20 修复：占位名去前导点（634cc22，堆叠于 #18）；其 CI run [41088956649](https://github.com/ertutio/TabulaV3/actions/runs/41088956649) API37 记录 `real-createBucket OK` 与 `createAlbum OK imageCount=0`（同镜像此前抛异常），双设备全绿，FINDING 行保留触发条件记录。 | 图集创建在 API37 可用性恢复依赖 #20 合并；#14 元数据空图集夹具继续有效（不依赖 createAlbum）。 |
| O3 | 真实多卷、其他应用媒体、进程死亡/取消/持久失败的所有组级规格尚未逐例证实。 | 明确 fixture/设备/授权，先做可控测试，真实设备缺口单独列明。 | 自有媒体探针通过不能替代这些要求。 |
| O4 | 性能 PR6 仍 draft，旧 paused run 不能证明有效基线；没有冻结性能数字。 | 验证工具成功/失败 fixture，获实际授权后按版本协议测量。 | C5/C6 的性能结论门槛。 |

## C1 消费者盘点（2026-10-28，基线 6293c16）

| 簇 | 当前键 | 读写者 | 身份缺口 | 迁移建议 |
| --- | --- | --- | --- | --- |
| albums_metadata.json | 显示名 name（颜色/排序/隐藏/屏蔽/createdAt） | AlbumManager.loadMetadata/saveMetadata；refreshAlbumsFromSystem 消费 | 同名桶共享一行——与 Album 层显式按名合并一致；桶拆分后元数据无法区分同名桶 | 保持 name 键为"合并 Album 身份"的产品行为；如未来拆分再迁移；记录该决定 |
| AlbumUsageTracker | albumId=name（使用频率+手动排序锁） | refreshAlbumsFromSystem 排序；Deck/AlbumView 写入 | 同上，随合并 Album | 同上 |
| AppPreferences 冷却/专清 | albumId=name | 专清/随机漫步冷却与进度 | 同上；清理进度关联 bucket 内容而非身份 | 同上；TC58 相关注册到哈希簇 |
| ImageHashCache | mediaId → 感知哈希，**无内容版本** | SimilarGroupDetector/RecommendationEngine/专清 | F20/TC58：同 ID 改内容旧哈希不失效；暂时不可读无重算边界 | **C1 首个迁移簇**：键加 DATE_MODIFIED 版本，读取失败按有界策略重算，不覆盖无关冷却/进度 |
| RecommendationEngine | 内存 similar-group 缓存（按 id） | invalidateSimilarGroupCache 钩子已接移除/恢复/刷新 | 内容编辑不失效（TC58 编辑场景） | 随 ImageHashCache 版本化一并接入失效 |
| StorageStatsManager | bucket 统计 | 图集清理页面 | 同名桶合并统计 | 随 Album 层合并保持；拆分时再议 |
| UI 选择/持久进度 | albumId=name（selectedAlbumId、pendingArchive.albumId 等） | MainActivity/DeckScreen/AlbumViewScreen | 与合并 Album 一致 | 随合并 Album 保持 |

结论：Album/元数据/偏好/统计/UI 六簇跟随"按名合并 Album"的显式产品行为（不迁移即为有依据决定）；**ImageHashCache+RecommendationEngine 是 C1.1 首个真实迁移簇**（TC58/F20 直接缺口）。

## 保存点格式

继续在此表追加或更新当前子行；详细日志放现有 CI artifact/测试报告，避免把全部日志塞进上下文。

| 子项 / 状态 | 行为与改动提交 | 分支 / PR / 父 PR | 验收断言与实际证据 | 未完成 / 阻塞 / next_action |
| --- | --- | --- | --- | --- |
| 计划初始化 / ready | 仅重构执行文档，代码基线见上 | 由本计划 PR 的真实链接及 SHA 补录，不预写不存在的编号 | 文档链接、原 T/TC 范围、连续执行循环与依赖静态核对 | 启动实现后更新游标，执行 C0 的具体动作。 |
| C0.1 C0-DETAIL-EMPTY-RETRY / `review_pending`（本地实现完成） | AlbumViewScreen：空态分支顶部 loadError 时显示错误+重试（新增私有 AlbumLoadErrorRow，非空分支复用）；重试经既有 albumLoadRevision，取消不覆盖沿用 T05 | 本分支 docs/continuous-development-2026-10-28 独立提交（SHA 见 git log） | 静态断言：空态分支 loadError 渲染横幅（源码核对本提交）；`:app:compileReleaseKotlin` BUILD SUCCESSFUL；`testReleaseUnitTest` 176 项 1 失败=基线既有 atomicReadRestoresLegacyBackup（未改动基线同样失败） | 设备 UI 断言 BLOCKED（CI 触发权限）；重试成功路径与切图集覆盖待真实 UI；next C0.2 差额表 |
| C0.2 空相册首次失败+重试显示真实照片 / `accepted_local_increment` | CloudCleanupUiTest 新增 albumDetailEmptyAlbumLoadFailureShowsErrorAndRetryShowsRealImage：空图集经 `AlbumManager.updateAlbum` 仅元数据构造（refreshAlbumsFromSystem 步骤 4 恢复为 imageCount=0，绕开 O2 createAlbum）；注入后断言空态专属文案「图集加载失败，未能获取照片」+重试；解除注入并向该图集路径插入真实合成照片后重试，断言照片 displayName（网格项 contentDescription）出现、空态错误行消失 | [test/empty-album-retry-device-2026-10-29](https://github.com/ertutio/TabulaV3/tree/test/empty-album-retry-device-2026-10-29) → [PR #14](https://github.com/ertutio/TabulaV3/pull/14)（基于 master@6adf988，head a9c5962） | run [40910570752](https://github.com/ertutio/TabulaV3/actions/runs/40910570752)（head a9c5962）build+API36+API37 全绿：双设备 VolumeDeletion OK(3)、CloudCleanupUiTest OK(5)（含新用例）、CloudRestoreUiTest OK(3)；run-cloud-ui-probe.sh 逐例预期同步声明为 5。首两轮失败已诊断：40904171420→suspend 调用编译错（02577be 修复）；40907190309→用例数预期未声明（双设备测试实际全过，a9c5962 补声明）；40910570752 首轮 API36 模拟器在 5 例全过后掉线（device offline，基础设施），`--failed` 重跑全绿 | 切图集/取消后旧结果不覆盖待真实 UI；O2 先做合成最小诊断，不能据未确认的 scoped-storage 归因冻结实现方案 |
| C1.2 推荐组缓存元数据版本失效 / `accepted_local_increment` | RecommendationEngine 缓存抽为 SimilarGroupsCache；按 `(id,dateModified,size)` 全字段排序的版本列表精确比较，保留 TTL、显式失效、组冷却和顺序不敏感。初审发现日期+1/大小-31的线性折叠碰撞，已改精确相等并补行为回归。 | [PR #15](https://github.com/ertutio/TabulaV3/pull/15) 已合并；最终 head `87dd93c5`，同步 master 后修复；merge `b2d6b43e`。 | 缓存测试源码 8 项及真实 JPEG 引擎测试 2 项；最终 [41003765715](https://github.com/ertutio/TabulaV3/actions/runs/41003765715) 完整单测/构建与双设备全绿，单测实际执行、精确总数未知。独立只读复核未发现本次实现阻断。原 [40908964501](https://github.com/ertutio/TabulaV3/actions/runs/40908964501) 属修复前历史绿灯，不证明碰撞已修；该 run 的 API36 首轮完成 3+4+3 后崩溃、重跑全绿仍保留。作者原本地记录 220 项/1 个 atomicReadRestoresLegacyBackup 失败，本轮未在本机复跑。 | TC13–TC16、其他 ImageFile 字段刷新与字节变更但元数据不变仍未覆盖；不能把本局部验收当作 C1 完成。 |
| C0.3 T11A-RETRY 取消夹具不落缓存 / `accepted_local_increment` | PreloadImageFeaturesCancellationTest：取消中的 preload（首条流阻塞+latch 控制）夹具断言 ImageFeatureCacheStore 未缓存；该断言没有直接区分取消和同一失败输入的其他不可缓存原因；对照：未取消经 motion 路径的 definitive 结果照常入缓存（HDR 重型检测在 Robolectric 抛异常→正确产生不可信阴性） | [test/feature-preload-cancel-2026-10-29](https://github.com/ertutio/TabulaV3/tree/test/feature-preload-cancel-2026-10-29) → [PR #16](https://github.com/ertutio/TabulaV3/pull/16)（基于 master@6adf988，head d15f52f） | 本地 2/2 通过；run [40910286445](https://github.com/ertutio/TabulaV3/actions/runs/40910286445)（head d15f52f）build+API36+API37 全绿（双设备 3+4+3） | 同失败输入未取消的反事实仍未直接断言；Composable 重入重试接线保持结构核对（单元源集无 Compose 测试依赖，不为此新增）；真实 HDR 增益图路径与卡片重入重试待真实 UI |
| C0.4 返回/重开/顺序切换 / `accepted_local_increment` | debug 接缝 `AppPreferences.debugAlbumLoadDelayMs`（生产不读取，@Before 防御复位）+ MainActivity getAlbumImages 查询后延迟返回；设备用例 albumDetailSlowLoadSwitchShowsNewAlbumContentAndCancelIsSafe：设置延迟后返回不崩溃（没有查询进入延迟窗口的握手）、重开慢加载后真实照片出现、切换后新页只显示新图集照片（旧图集照片 find 为 null）；探针逐例预期声明更新为 6 | [test/album-switch-cancel-device-2026-10-29](https://github.com/ertutio/TabulaV3/tree/test/album-switch-cancel-device-2026-10-29) → [PR #17](https://github.com/ertutio/TabulaV3/pull/17)（**堆叠于 PR #14**，base a9c5962，head 55a678c） | run [40915446458](https://github.com/ertutio/TabulaV3/actions/runs/40915446458)（c36e292）设备失败已诊断：预测性返回动画期间图库背景层已组合，返回后立即点按落在过渡层被吞（失败转储证据：超时时活动窗口为图库）→ 55a678c 修复（waitUntilGone("返回") 后再点按）；run [40919790196](https://github.com/ertutio/TabulaV3/actions/runs/40919790196)（head 55a678c）build+API36+API37 全绿，双设备 CloudCleanupUiTest OK(6)（含新用例） | 已按 #14→#17 顺序合并，merge 提交分别为 83dda1e、177fc08；强制并发竞态复现不在断言范围（Compose 取消语义下跨页旧结果写入为结构防护，本断言锁定可观察行为） |
| O2.1 图集创建合成诊断 / `review_pending` | CreateAlbumDiagnosticDeviceTest（无 UI，唯一合成名，manifest 登记+哨兵清理）：真实 createBucket（含 getAlbumRelativePath 前置探测）+ 旧占位链逐阶段复现（insert pending → 写最小 JPEG → unpublish → delete），dot 名与非 dot 名双变体隔离文件名影响；真实 createAlbum 入口断言成功路径（imageCount=0 + Tabula 路径约定）或失败路径 abort-before-save 不变量；API36 为基线断言（两路必须全成功），API37 只记录不猜测；探针声明 2 例 | [test/create-album-o2-diagnostic-2026-10-30](https://github.com/ertutio/TabulaV3/tree/test/create-album-o2-diagnostic-2026-10-30) → [PR #18](https://github.com/ertutio/TabulaV3/pull/18)（基于 master@b2d6b43，head 333e49d） | 首轮 run [41082467467](https://github.com/ertutio/TabulaV3/actions/runs/41082467467)：API37 real createBucket=NULL（无异常）、replica insert/write/unpublish OK、**delete SecurityException**；API36 失败于本测试自身的 dirExists 断言——后经 86b0832 确认根因是 UiAutomation 字符串入口按空白分词，`&&/||` 未被解释使 dirExists 恒 false（shell 语法假象，非设备行为）→ 改经 base64 sh -c 封装；第二轮 run [41086854893](https://github.com/ertutio/TabulaV3/actions/runs/41086854893) 双设备全绿，API37 `dotFailures=[delete] dotlessFailures=[]` → FINDING：delete 仅在前导点占位名下失败；修正后 run [41101238407](https://github.com/ertutio/TabulaV3/actions/runs/41101238407) API37 记录 `createAlbum-dir dirExists=true`、`real-createBucket-dir dirExists=true`——失败后目录确实留存，"空目录不持久"旧观察作废 | 失败阶段已定位；dot→delete 因果含"非 dot 变体后跑"的顺序性注意（同目录数秒内对照），FINDING 行继续记录；生产修复见 O2.3 |
| C0.5 T11A-RETRY 取消因果对照 / `review_pending` | PreloadImageFeaturesCancellationTest 第 3 例 sameFailingInputWithoutCancellationCachesDefinitiveNegative：与取消用例完全相同的阻断流输入但不取消——检测完整走完（job 未取消）且 motion 路径产生 definitive 阴性照常入缓存；由此取消用例的空缓存可归因于取消本身而非失败输入 | [test/preload-cancel-causality-2026-10-30](https://github.com/ertutio/TabulaV3/tree/test/preload-cancel-causality-2026-10-30) → [PR #19](https://github.com/ertutio/TabulaV3/pull/19)（基于 master@b2d6b43，head 57dcddc） | 本地 3/3 通过；run [41082979292](https://github.com/ertutio/TabulaV3/actions/runs/41082979292)（head 57dcddc）build+API36+API37 全绿 | 其余取消阶段细分与重入 UI 仍为交接保留差额；两条"不落缓存"机制（取消 vs 不可信阴性）已由独立断言分离 |
| O2.3 createBucket 占位名修复 / `review_pending` | SystemAlbumSyncManager.createBucket 占位名 `.tabula_placeholder_<ts>.jpg` → `tabula_placeholder_<ts>.jpg`（+4/-1 含诊断出处注释）；消除 API37 对 unpublish 后前导点自有媒体的 delete SecurityException，使 createBucket/createAlbum 恢复可用 | [fix/create-bucket-placeholder-name-2026-10-30](https://github.com/ertutio/TabulaV3/tree/fix/create-bucket-placeholder-name-2026-10-30) → [PR #20](https://github.com/ertutio/TabulaV3/pull/20)（**堆叠于 PR #18**，base 333e49d，head 634cc22） | run [41088956649](https://github.com/ertutio/TabulaV3/actions/runs/41088956649)（head 634cc22）build+API36+API37 全绿；API37 诊断日志记录 `real-createBucket OK` 与 `createAlbum OK systemAlbumPath=... imageCount=0`（同镜像修复前抛异常），FINDING 行保留触发条件记录；全部既有设备探针（3+6+3）双设备通过 | 真实多卷/其他应用媒体不在本修复范围；合并顺序 #18 → #20 |
| C0.6 旧结果晚到竞争确定性握手 / `review_pending` | 接缝强化（仅 debug）：`debugAlbumLoadDelayMs` 延迟前写 logcat 标记 `albumDetailLoad entering delay window`（握手），延迟改 NonCancellable 模拟"结果在调用方取消后才完成"的最坏情形；新用例 albumDetailLateResultAfterSwitchDoesNotOverwriteNewPage：打开旧图集→轮询握手确认旧查询在窗口内→返回打开新图集→第二次握手→新照片出现且旧窗口到期后，断言旧图集照片不出现在新页、新照片保持；探针声明 6→7 | [test/late-result-race-handshake-2026-10-30](https://github.com/ertutio/TabulaV3/tree/test/late-result-race-handshake-2026-10-30) → [PR #21](https://github.com/ertutio/TabulaV3/pull/21)（基于 master@b2d6b43，head 95d21fe） | 两轮失败均已诊断并记录：41092875221 基线在导航完成后采样（自家标记已计入）→ ccae121 前移基线；41097770580 仍失败 → 定位 UiAutomation 字符串入口按空白分词（acceptance.md 已载明），管道/重定向被当字面参数使计数恒 0 → 95d21fe 在 Kotlin 侧计数；最终 run [41100985640](https://github.com/ertutio/TabulaV3/actions/runs/41100985640)（head 95d21fe）build+API36+API37 全绿，双设备 CloudCleanupUiTest OK(7) | 接缝构造的是守卫必须处理的确定性最坏情形，不是对真实竞态窗口大小的测量；真实 MediaStore 查询取消时序不在断言范围 |

每条验证至少记录：case ID 与断言、源码 SHA/相关输入、命令或 run URL、环境/fixture、实际结果（含失败/跳过/缓存）、报告位置。每条阻塞记录：所缺条件、累计失败与假设、已试结果、需要谁做什么、解除条件、可继续的独立项。收到评审意见后更新原子项，不另起一个无法对账的任务名。

## 2026-10-30 审计、修复与合并验收

本轮按 #14 → #17 → #16 → #15 合入；前三项 merge 为 `83dda1e`、`177fc08`、`e86f741`，最后为 `b2d6b43e`。#15 修复首次组合验证即通过，没有重复派发工作流。四项均为局部增量；C0–C3 和总任务仍未完成。计划 PR #12 保持单独开放，生产代码已通过上述独立 PR 合入。

最终 [41003765715](https://github.com/ertutio/TabulaV3/actions/runs/41003765715) 每设备 12 项通过；六个命名健康窗口均 issues=[]，boot_id 不变，system_server/SurfaceFlinger PID 与 starttime 稳定，未发现 Tabula ANR/fatal 或 DeadSystem 标记。API36 的专用预检条件跳过属设计内行为，其实际用例未跳过。环境仍有 GMS 后台 ANR（API36 两次启动超时、API37 一次 SIM_STATE_CHANGED 广播超时），未影响本次 instrumentation，通过记录不能写成全系统无 ANR。

#17 缺查询进入延迟窗口的握手，且重开旧页照片出现后才切换新图集；通过的是返回/重开/顺序切换，旧慢查询晚到新页的竞争仍待专用断言。#16 缺相同失败输入的未取消对照，不能把两个不同输入的结果当作取消因果的完整证明。O2 的旧运行引用相互矛盾已纠正为待诊断；下一步先确认真实失败阶段，再选择实现。
