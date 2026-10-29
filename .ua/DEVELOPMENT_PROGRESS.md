# 持续开发进度与续接记录

本文件记录事实和下一动作；执行规则见[总计划](DEVELOPMENT_ROADMAP.md)，验收规格见[验收约定](../docs/acceptance.md)。历史条目保留原记录，当前状态以本节与下表为准；旧的 PASS、accepted 或关闭不表示整个 T 组完成。

## 当前游标

| 字段 | 当前值 |
| --- | --- |
| 总任务状态 | `in_progress` |
| 已接受代码基线 | `master@6adf988bb5ac08cf0d34eeeddbd5417f99e2d8a2`，子 PR [#13](https://github.com/ertutio/TabulaV3/pull/13) |
| 验证输入及运行 | `efc2f51af425fe9934ceed4ac7400c1794ef9dfd`，云运行 [40815236361](https://github.com/ertutio/TabulaV3/actions/runs/40815236361)；单测实际执行 `211`；逐设备结果见运行工件 |
| 当前检查点 | C0–C3 各有局部改进；各组仍 `PARTIAL`，详见下表 |
| next_action | 按 C0 首次空相册失败与重试后图片显示补可控设备断言；并继续 C1 身份消费者及推荐缓存失效盘点。C2/C3 缺口按下表另行验证。 |
| 尚未验证 | 真实跨卷与其他应用媒体、进程死亡和持久化失败；不得以合成探针代替。 |

## 本次合入的局部范围与剩余工作

| 检查点 | 本次局部边界 | 未完成、不得标为整组 PASS |
| --- | --- | --- |
| C0 | 空态错误与重试显示、恢复和副本安全的局部修正及已有证据 | 详情设备错误探针是非空相册；首次空态失败、重试后真实图片显示仍需断言；其他 T01/T02/T05/T11a 差额仍按验收规格逐项核对。 |
| C1.1 | 哈希内容版本与失败重试的局部改进 | 六簇并非完整身份迁移，跨卷身份消费者仍待核验；RecommendationEngine 的五分钟组缓存及内容变更失效仍待改进和验证。 |
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
| C0-DETAIL-EMPTY-RETRY | 详情加载失败错误/重试可见；解除注入后重试成功 | run [40746123075](https://github.com/ertutio/TabulaV3/actions/runs/40746123075)（sha e13fcea）双设备 CloudCleanupUiTest.albumDetailLoadFailureShowsBannerAndRetryRecovers 通过（debug 注入接缝 AppPreferences.debugForceAlbumLoadFailure；空相册分支因 API37 createAlbum scoped-storage 失败（O2 证据）改走非空相册路径，空态分支保留静态断言） | PARTIAL（非空路径设备通过；空态分支仅静态） |
| C0-T11A-CACHE | 忙碌/快速模式/失败阴性不入缓存；版本/开关失效；阳性恒缓存 | ImageFeatureCacheStoreTest 7 项 | PASS |
| C0-T11A-DETECT | 快速模式阴性 definitive=false；无失败动态阴性 definitive=true | ImageFeatureDefinitiveTest 2 项 | PASS |
| C0-T11A-RETRY | 必要重试与取消不落缓存 | 卡片重入重试/取消语义为结构行为，无显式断言 | PARTIAL |
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
| O1 | 已修复（本地）：AlbumContentView 空态分支在 loadError 时显示 AlbumLoadErrorRow（错误+重试），非空分支沿用 T05 横幅；静态核对该分支此前确被空态遮盖。 | 设备/云 UI 断言 BLOCKED：build-volume-confirmation.yml push 仅匹配旧分支、workflow_dispatch 需 workflow 权限（gh token 无）。 | C0/T05；重试成功显示真实图片、切图集不覆盖两项待真实 UI。 |
| O2 | **已复现并记录**：run 40735652083 中 API37 `createAlbum` 抛"无法创建图集文件夹，请检查存储权限"（scoped storage 下 File.mkdirs 失败；API36 同路径成功）。与先前 createBucket 观察一致：生产 createAlbum 在 API30+ 依赖直通文件系统路径。 | 立项修复：改走 MediaStore contributed-media 建目录（与 photo() 夹具同法），需单独 PR 与双设备验证。 | 图集创建在 API30+ 真机的可用性；探针空相册分支因此改走非空路径。 |
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

每条验证至少记录：case ID 与断言、源码 SHA/相关输入、命令或 run URL、环境/fixture、实际结果（含失败/跳过/缓存）、报告位置。每条阻塞记录：所缺条件、累计失败与假设、已试结果、需要谁做什么、解除条件、可继续的独立项。收到评审意见后更新原子项，不另起一个无法对账的任务名。
