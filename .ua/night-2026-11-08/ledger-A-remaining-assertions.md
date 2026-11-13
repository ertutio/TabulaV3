# 包A交付：剩余断言台账（T01–T13）

- 日期：2026-11-08。基线：master `43a8da1037ca50f34c9fabd8a41c2ac505c93824`（PR51+PR52 已合并）。
- 组合验证：CI run 42461895694（tested head `7544ad8137a4ea3010d5b797f81842ff3a17c555` 全绿，API36/37 各 24 项设备测试，最终 master 文件树与该版本一致）。
- 方法：先读 acceptance.md 组级矩阵（断言权威定义，旧 TC 无逐例规格只按组级描述映射）→ `git ls-files`/grep/读文件核对当前 master 实际代码与测试 → 每组给结论。**所有"已覆盖"指针均按当前 master 树核对过文件存在性；PROGRESS 登记仅作 run ID 引用，状态以代码为准。**
- 测试统计（当前 master）：app/src/test 60 个测试类（62 文件含 2 个非测试文件），app/src/androidTest 14 文件。
- 断言状态取值：已覆盖 / 待验证 / 缺实现 / 外部阻塞 / 依赖测量。证据 run ID 为 PROGRESS 登记的精确值，未逐个重查远端（标 * 者为登记值，未核对 run 页）。

## 一、组级结论总表

| 组 | 结论 | 一句话依据 |
| --- | --- | --- |
| T01 / TC01–TC07 | 已覆盖（合成可测面）+ 外部阻塞 | 逻辑层清单全命中实际测试文件；真跨卷/进程死亡/他人媒体授权留 blocked |
| T02 / TC08–TC12 | 已覆盖（合成可测面）+ 外部阻塞 | 快照/软删除/恢复/序列化测试齐 + MainActivity 实际接线；同 T01 设备缺口 |
| T03 / TC13–TC16 | 已覆盖（簇级）+ 少量登记项 | 全消费者簇经 PR25–34/44 关闭；余 A4 itemBounds、exitingRightCard 等登记项 |
| T04 / TC17–TC22 | 已覆盖（合成可测矩阵）+ 外部阻塞 | 三入口同 `deleteInBatches` 协议 + 2001 项主链/回收站双断言；真实授权路径 blocked |
| T05 / TC23–TC26 | 已覆盖 | 详情完整模型/N+1 消除/秒转毫秒/错误重试均有测试与设备证据 |
| T06 / TC27–TC31 | 已覆盖（主体）+ 登记项 | 所有权/键/生命周期测试齐（PR33/34/35/44）；余 2 个登记级小项 |
| T07 / TC32–TC35 | 已覆盖（主体）+ 外部阻塞 | 前台刷新/像素/FULL 恢复已闭环；PARTIAL 真机、撤销强停 blocked |
| T08 / TC36–TC42、B01/B02 | 待验证（工具在开放 PR）+ 依赖测量 | master 无测量工具（tools/ 仅设备探针）；PR45→42→6 未合并；真实测量待授权 |
| T09 / TC43–TC44 | 部分已覆盖 + 待验证 | Release/R8/身份由 CI 门禁覆盖；官方兼容矩阵无证据文件；覆盖安装 checklist 未执行 |
| T10 / TC45–TC47 | 缺实现 | StorageStatsManager 仍每次全库 getAllImages；无增量/合并对账实现 |
| T11 / TC48–TC51 | T11a 已覆盖；T11b 缺实现 | 特征检测/缓存正确性测试齐；有界缓存/single-flight 优化未实现 |
| T12 / TC52–TC53 | 部分已覆盖 + 待验证 | Coil/协程取消有定点回归；EXIF/media3 无定点测试；未发现引未用库 |
| T13 / TC54–TC55 | 待验证 + 外部阻塞 | 交付身份可由组合 run 佐证；覆盖安装/旧数据人工清单未执行；发布未授权 |

## 二、开放/登记断言明细（稳定 case ID）

### T03 残余（映射 TC13–TC16）

| Case ID | 断言 | 状态 | 指针 |
| --- | --- | --- | --- |
| T03-DECK-PREFETCHKEY | 清理模式预取请求键与卡片消费键一致（身份+版本） | **已覆盖**（PR44 已合并 master） | `app/src/main/java/com/tabula/v3/ui/screens/DeckScreen.kt:440` 调 `deckCleanupPrefetchRequest`；定义 `app/src/main/java/com/tabula/v3/ui/util/ThumbnailCacheKeys.kt:155`；测试 `app/src/test/java/com/tabula/v3/ui/util/CleanupPrefetchRequestSpecTest.kt`；PROGRESS U11 PR44 行、master 合并 CI run 42248849013* |
| T03-DECK-EXITRIGHT | 回牌动画右卡淡出按完整身份而非裸 ID | **登记不修**（非定位缺陷：同 ID 跨卷约一帧误抑制、自愈） | `app/src/main/java/com/tabula/v3/ui/components/DrawCardState.kt:41`（`exitingRightCard: Long?`）、`CardStack.kt:2632`（`!= rightCard.id`）；PR33 审计登记（PROGRESS U5 行） |
| T03-DECK-SAMESIZE-NOINVALIDATE | deck 同 size 同 index 内容变化触发重校验 | **登记（共有局限）** | CardStack LaunchedEffect 键不含内容版本；PR33 审计登记②。当前无测试钉，属待验证级登记 |
| T03-DRAGSELECT-ITEMBOUNDS | StringDragSelectState.itemBounds 离场行清理 | **待验证→小提交候选** | `app/src/main/java/com/tabula/v3/ui/util/StringDragSelectState.kt:26,40`（只增不删）；测试 `StringDragSelectStateTest.kt` 仅 1 例不覆盖此项；PROGRESS A4 行 |
| T03-A2-DEADCODE | AlbumViewScreen 裸 ID 回退路径 | **已关闭（口径：生产不可达死代码）** | `app/src/main/java/com/tabula/v3/ui/screens/AlbumViewScreen.kt:217-218`（else 分支 `allImages.filter { it.id in imageIds }`，getAlbumImages 恒提供）；PR32 盘点证据关闭 |
| T03-CROSSVOL-DEVICE | 真实多卷热拔插/其他应用媒体 | **外部阻塞** | acceptance.md:62 口径；PROGRESS 各单元统一登记 |
| T03-MIGRATE-KILL | 身份键迁移跨进程死亡 | **外部阻塞** | PR32 登记口径（迁移真实 kill 模拟属设备 blocked 簇） |

已关闭簇（复用不重做，均经独立复审）：投影/软删除 PR25（run 41274727140*）、图集批量删除 PR26（41292141083*）、哈希/相似组按卷 PR27（41292765803*）、组身份/专清持久化 PR28（41322746035*）、网格/缩略图键 PR29（41307027175*）、封面簇 PR30（41350120490*）、冷却池 PR32（41374048554*）、回牌定位 PR33（41668707033*）。

### T06 残余（映射 TC27–TC31）

| Case ID | 断言 | 状态 | 指针 |
| --- | --- | --- | --- |
| T06-DRAW-PRELOAD-BURST | DRAW 模式 preloadNextCards 不冗余连发 | **待清理（登记级，非缺陷）** | `app/src/main/java/com/tabula/v3/ui/components/CardStack.kt:1769-1783`（循环 1..4 连发 4 次 preloadAround，前 3 次被防抖取消）；调用点 `CardStack.kt:1917`；PR35 审计 CONCERN-5、PR44 范围决策"维持登记不动" |
| T06-FOCUS4-CALLERPIN | 调用方不回收借用位图（所有权半边） | **登记（静态审读无测试钉）** | 主代码 24 处 `.recycle()` 全部作用于自有副本（ImageFeatureDetector.kt:119、PerceptualHash.kt:91-108、FluidCloudService.kt:110 等自解码对象）；借用缓存位图 0 处 recycle；测试 `app/src/test/java/com/tabula/v3/ui/util/OwnedBitmapsTest.kt` 只钉生产侧 |
| T06-COMPOSE-E2E | 两种卡片模式真实 Compose 交互/解码 | **审读口径（无单测基建）** | PR34/PR35 证据边界声明；像素级链路由 T07 PixelRefreshUiTest 部分覆盖 |

已关闭：deck 查看器键 PR34（`DeckViewerThumbnailKeyTest` 5/5，run 41684261410*）、防抖窗口时序 PR35（`PreloadingManagerTest` 19/19，run 41693468519*）、回牌身份 PR33（`DrawCardRecallIdentityTest` 6/6）、清理预取键 PR44。

### T04 / T05 / T01 / T02

| Case ID | 断言 | 状态 | 指针 |
| --- | --- | --- | --- |
| T04-ENTRIES-COMPOSITION | 三删除入口同协议 + 2001 项分批 | **已覆盖** | MainActivity.kt:633 `deleteRecycleBinSelection`、:769/:813 `deleteInBatches`；`RecycleBinDeletionTest`、`BatchDeletionTest`、`MediaDeletionBatchTest`；主链 PR31 run 41359827937*、回收站 2001 项 PR47 run 42241300356* |
| T04-REAL-AUTH / RESTORE | 真实系统授权、恢复路径 | **外部阻塞** | acceptance.md T04 行"真实系统授权及恢复路径"；PROGRESS C2 行 blocked 簇 |
| T05-DETAIL-MODEL | 完整模型无 N+1、秒转毫秒、错误/重试 | **已覆盖** | `LocalImageRepository.kt:158`（`* 1000` 毫秒）、`AlbumViewScreen.kt:209-218`（直消费 ImageFile）；`AlbumDetailImagesTest`；PR23 设备证据 run 41169544885*（SHA dff9898，历史 run） |
| T01-LOGIC / TC57 | 复制安全/证据账本/并发事务 | **已覆盖** | `MediaStoreCopySafetyTest`、`CopyEvidenceStoreTest`、`ArchiveCommitEvidenceTest`、`CleanupEvidenceTest`；TC57 并发=CopyEvidenceStore Mutex+设备 `CloudCleanupUiTest.kt:229` laterCandidateIsNotDeleted、:529 invalidatedCopyKeepsConfirmedOriginal |
| TC56-RESULT | 移动/复制逐项结果分类 | **已覆盖（合成）** | MainActivity.kt:1510/1519 `moveImagesByIdentity`/`copyImagesByIdentity`；`MoveCopyItemResultTest`、`MoveCopyResultMessagesTest`、`AlbumIdentityMoveCopyTest`；PR43 合并 CI 42342498768*（merge c6e8f84） |
| TC56-REALAUTH | 实际移动授权真实反馈 | **外部阻塞**（与 T07 PARTIAL 同门控） | PR43 登记"CopiedButSourceRetained 不在本路径"；真机授权 blocked |
| T02-LOGIC-UI | 快照/软删除/恢复 + UI 接线 | **已覆盖** | `ProjectSnapshotTest`、`RestoreDecisionTest`、`RecycleBinSerializationTest`、`RecycleBinManagerTest`、`SnapshotCrossVolumeSoftDeleteTest`、`CloudRestoreUiTest`（androidTest）；恢复三处 `matchesSameMediaIdentity`（O5④ 盘点，PROGRESS 边界表） |

### T07（映射 TC32–TC35）

| Case ID | 断言 | 状态 | 指针 |
| --- | --- | --- | --- |
| T07-FOREGROUND-EDIT | 外部编辑回前台刷新+最新元数据 | **已覆盖** | `ForegroundPermissionUiTest`（androidTest，外部编辑用例 CI 实跑 run 41706417934*）；repository 层 `ExternalEditMetadataAdoptionTest`；竞争守卫 `MediaSnapshotLoadRaceTest`（3 例） |
| T07-PIXEL | 像素级显示刷新 | **已覆盖**（PR51 已合并） | `PixelRefreshUiTest`（androidTest）；CI 42389241204*/42445582269*；组合 run 42461895694 |
| T07-FULLRECOVERY | FULL 缺席/恢复两阶段 | **已覆盖**（PR52 已合并） | `FullRecoveryPhase1Test`/`AbsenceTest`/`ContinuationTest`/`ReadbackTest`（androidTest 4 文件）；CI 42386984637*/42455626792* |
| T07-PARTIAL-SET | PARTIAL 真实选中集合变化刷新 | **外部阻塞**（真机人工；pm 不可夹具化） | 三态映射已钉：`MediaPermissionPolicy.kt:12,32-37`（NONE/PARTIAL/FULL）+ `MediaPermissionPolicyTest`（9 例）；U17 盘点③、U15 盘点③ |
| T07-REVOKE-PROCESSDEATH | 撤销权限强停后门控恢复 | **外部阻塞**（撤销强停杀 instrumentation 实证） | 应用侧路径=onCreate 级别检查→门控（单测映射）；ForegroundPermissionUiTest KDoc 载明边界 |
| T07-CLOUDCLEANUP-API37 | CloudCleanupUiTest API37 稳定通过 | **待诊断（真实就绪）** | PROGRESS U18/U19：RecoverableSecurityException 间歇（media/26 归属），迭代间 OK(8)↔失败 |

### T08–T13

| Case ID | 断言 | 状态 | 指针 |
| --- | --- | --- | --- |
| T08-TOOLING | 测量工具负例/成功例经真实入口 | **待验证**（在开放 PR，master 无实现） | `tools/` 仅 run-volume-device-probe.py、run-cloud-ui-probe.sh；PR45（c85c2aa，35/35 python，独立复审已过）堆叠 PR42（fa675f2）堆叠 PR6（eb8b94b，measure job paused），均未合并 |
| T08-MEASURE | 获授权真实测量/A-A 基线 | **依赖测量 + 外部阻塞** | PROGRESS："实际测量待授权"；paused job 不是有效基线（acceptance.md:22） |
| T09-RELEASE-R8 | Release/R8/身份/体积 | **已覆盖**（CI 门禁持续执行） | `.github/workflows/build-volume-confirmation.yml`（Release 单测+R8/身份/体积+双 API 探针）；组合 run 42461895694 |
| T09-COMPAT-MATRIX | Kotlin/AGP/Gradle/JDK 官方兼容成立证据 | **待验证（无证据文件）** | 实际版本 Gradle 8.11.1 / AGP 8.9.0 / Kotlin 2.3.0（gradle/libs.versions.toml:3-4、wrapper:3）；仓库内无兼容矩阵文档（docs 分支有盘点表引用 master 测试，未落 master） |
| T09-OVERWRITE-INSTALL | 覆盖安装/旧记录恢复 | **待验证（人工清单未执行）** | `docs/upgrade-verification-checklist.md` 仅在 docs 计划分支（master 无此文件）；PROGRESS U11 任务3"待人工执行" |
| T10-INCR-SCAN | 连续归档不逐张全库重扫、计数不保全 ID、增量对账 | **缺实现** | `app/src/main/java/com/tabula/v3/data/repository/StorageStatsManager.kt:99,138`（performScan 每次 `imageRepository.getAllImages()` 全量）；无增量/对账实现与测试 |
| T11a-FEATURE | 不可信阴性不入缓存、变更失效 | **已覆盖** | `ImageFeatureDefinitiveTest`、`ImageFeatureCacheStoreTest`（8 例）、`PreloadImageFeaturesCancellationTest` |
| T11b-BOUNDED-SINGLEFLIGHT | single-flight、有界缓存、HDR/实况保留 | **缺实现** | `app/src/main/java/com/tabula/v3/ui/util/ImageFeatureCacheStore.kt`（34 行，无 Mutex/maxSize/Lru/TTL/single-flight）；HD 键已有（`ViewerHdRequestTest`）但缓存无界化未做；收益判定依赖测量 |
| T12-COIL / COROUTINES | Coil 键语义、协程取消定点回归 | **已覆盖** | `ThumbnailCacheKeyTest`/`DeckViewerThumbnailKeyTest`/`ViewerHdRequestTest`/`CoverImageRequestTest`/`PreloadImageFeaturesCancellationTest`/`MediaSnapshotLoadRaceTest`；Coil 2.7.0 |
| T12-EXIF / MEDIA3 | EXIF、实况/动画生命周期定点回归 | **待验证（未发现专属测试，未核对旧规格）** | 使用点 `PerceptualHash.kt`/`ImageFeatureDetector.kt`（exifinterface 1.3.7）、`SwipeableViewerOverlay.kt`/`MotionPhotoPlayer.kt`（media3 1.5.1）；app/src/test 无 exif/media3 命中 |
| T13-DELIVERY-IDENTITY | 当前交付 ref/SHA/APK/哈希一致 | **已覆盖（组合态）** | run 42461895694 tested head 7544ad8 + master 文件树一致（背景事实）；正式发布物身份未单独登记 |
| T13-RELEASE | 正式发布 | **外部阻塞**（未授权，保持待授权） | `publish-release.yml` 存在；docs/release-3.3.4.md 为历史 |

## 三、队列校正

### 已过期（应从队列删除）
| 队列项 | 判定 | 证据 |
| --- | --- | --- |
| "1：C1/C2 O5③ → PR26 待人工评审合并" | 已过期：PR26 已于 2026-11-01 合并（merge e6bd2cc0） | PROGRESS 已合并基线链 |
| "2：O5④ 恢复回填盘点" | 已过期：已闭环登记 | 边界表 O5④ 行 |
| "4：C2/T04 其余删除入口 → PR31 待合并" | 已过期：PR31（2026-11-02）+ PR47（2026-11-07）均已合并 | 边界表 U3 行、U12 行 |
| next_action "等待复审 PR51/PR52" | 已过期：二者已合并进 master 43a8da1 | git log；run 42461895694 |
| "回收站入口 2001 项组合断言（可选候选，U3 遗留）" | 已过期：PR47 已交付 | `RecycleBinDeletionTest.twoThousandOneRecycleRecordsSplitThroughProductionComposition` |

### 重复/应降级（多处登记，收敛为一条）
| 项 | 处理建议 |
| --- | --- |
| A3 缩略图缓存键（队列项3"首查"清单 vs B 簇行） | 已由 PR29/PR30 关闭，从"首查"删除，仅存证据指针 |
| deck 查看器键错配（C1/T03 与 C3/T06 双登记） | PR34 已关闭，双处均可销账 |
| 队列项 3（C1/T03 后续消费者 `ready`） | **降级**：簇主体已关闭（上表 T03）；剩余仅 T03-DRAGSELECT-ITEMBOUNDS 小提交 + 两条不修登记 + 设备 blocked |
| 队列"独立：C3/T06 ready（先定位缺口）" | **降级**：缺口已定位且主体关闭；剩 T06-DRAW-PRELOAD-BURST 清理与 T06-FOCUS4-CALLERPIN 登记 |
| C4/T07（"权限/外部修改同步……未闭环"） | **大部分已闭环**（PR36 链+PR41+PR49+PR50+PR51+PR52）；剩 PARTIAL 真机/撤销强停/CloudCleanupUiTest API37 三条 |

### 仍在的真实就绪工作（可直接开工，按价值排序）
| # | 工作项 | 依据 |
| --- | --- | --- |
| 1 | PR45 复审收尾并合并 → 依序 PR42 → PR6（整条 T08 工具链落 master） | PR45 独立复审已过、35/35 python；是 T08 全部后续的唯一依赖 |
| 2 | CloudCleanupUiTest API37 间歇 RecoverableSecurityException 诊断 | 已登记待办；影响设备门禁稳定性与 16→24 项计数的可信度 |
| 3 | T03-DRAGSELECT-ITEMBOUNDS：itemBounds 离场行清理（小提交） | StringDragSelectState.kt:26/40 已定位；有现成测试文件；低风险 |
| 4 | T06-DRAW-PRELOAD-BURST：preloadNextCards 冗余连发清理（小提交） | CardStack.kt:1769 已定位；PR44 同形先例（规格统一、不加生命周期） |
| 5 | T09 兼容矩阵 + T12 EXIF/media3 定点回归（检查型工作） | 断言定义明确（acceptance.md T09/T12 行）；无实现依赖，可立即产出证据 |
| 6（次） | T10-INCR-SCAN / T11b 有界缓存+single-flight（真正的实现单元） | 均缺实现；建议在 #1 落地后以有效基线为前置（T10 对账正确性部分可先行） |

外部门控（不可开工，维持登记）：真实测量授权（T08）、真跨卷/热拔插设备（T03/T04）、他人媒体授权、PARTIAL 真机人工验收（T07）、正式发布授权（T13）、撤销权限强停路径（OS 行为实证不可 in-process）。
