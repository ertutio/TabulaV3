# TabulaV3 agent 阅读入口

基线：d2038b8e0dc8c99fbdf2c754e88dec0a8078a725，2026-10-17 核实。来源见本目录 source-manifest.json。分析时采用固定 SHA 归档；这些产物现已提交到仓库，源码根是本目录上一级。

正式开发路线：[https://github.com/ertutio/TabulaV3/pull/9](https://github.com/ertutio/TabulaV3/pull/9)。每轮只领取一个子任务，下一轮仅 T01a。本目录 PR8_BODY_DRAFT.md 保留历史草案，DEVELOPMENT_ROADMAP.md 是正式正文快照；计划更新以 PR #9 正文为准。

## 开始开发

1. 读 PR #9 正文的单轮规则和当前子任务；按需查本目录分析结果。
2. 核对实际 checkout、工作区和当轮授权；变化时用 source-hashes.json 定位受影响路径。
3. 按下表在 agent-analysis.json 读取相应 flows/tasks/findings/files；函数/类位置从 knowledge-graph.json.nodes 的 filePath/lineRange 查询。
4. 追踪本轮调用者、磁盘状态、失败和恢复，选必要验证；完成后停止。

## 按行为定位

| 行为 | 首读入口 | 一起核对 |
| --- | --- | --- |
| 归档/原图安全 | AlbumManager.commitPendingArchive；SystemAlbumSyncManager 两个 copy | MainActivity 归档/清理回调、Deck 排队/撤销、源账本、移动回退删源 |
| 回收站 | RecycleBinManager、RecycleBinDeletion、MainActivity.deleteRecycleBinImages | AtomicFile/Mutex、pending、DeletePermissionSession、普通恢复和重载投影 |
| 身份/详情 | ImageFile、LocalImageRepository.loadAllData、AlbumManager | 名称/卷/URI、旧键、元数据/使用/冷却/专清/哈希/统计/UI，详情 Main 补读与秒/毫秒 |
| 前台/移动 | MainActivity.onResume/加载 effect、moveImageToBucket | 授权集合、快照版本、缓存、逐项结果和源保全 |
| 显示/动画/预加载 | CardStack 的 SwipeableCardStack/DrawModeCardStack、PreloadingManager | CoilSetup、ImageCard、Bitmap 借用、Disposable/scope、失败重试 |
| HDR/实况/推荐 | ImageFeatureState、ImageFeatureDetector、RecommendationEngine | 繁忙不等于阴性；SimilarGroupDetector/ImageHashCache 内容版本与失败 |
| 构建/发布 | app/build.gradle.kts、版本目录、.github/workflows | 历史分支、独立 debug 包、Release R8、签名与旧发布元数据 |

## 状态约束

- MediaStore 提供物理媒体/路径；recycle_bin.json 是本地软删除状态，重载须投影；recycle_bin_pending.json 保存不可逆请求与待核对项。恢复只核对，不再次删除，UNKNOWN 保留。
- source_images.json 当前仅 albumId→source URI，缺目标证明；albums_metadata.json 仅名称索引元数据。旧 AlbumMapping 注释不是当前主账本。
- URI 非空、同名或同大小不单独构成完整独立副本。所有可能删源的调用者共享安全结果。
- ImageFile.dateModified 为毫秒，actualWidth/actualHeight 考虑旋转；Coil 缓存 Bitmap 属借用，同尺寸缩放可返回源实例。
- 当前无独立 ViewModel/统一操作状态层，MainActivity、AlbumManager 和 Deck 协作；不将全面架构改写作为本轮前置。

## 覆盖与证据

145 文本文件全库索引，含90个生产 Kotlin、14单测和1 instrumentation 文件。28核心文件按关键行为追踪，117支持文件为 surface；119文件有结构提取，26无结构解析器的文本文件直接补充。详情见 coverage.json，全库索引不等于逐行缺陷审计。

图谱942节点、1999关系、10层；165 imports 来自技能，33 calls 经选定源码追踪。declaration-index 节点只作定位，不能据此推断全程序调用。schema/引用校验通过仅说明产物结构有效；未启用 dashboard、教学 tour 或 Hook。

CI 证据见 evidence/ci/ci-summary.md、test-results-summary.json、tree-equivalence.json：相同源码树可复用75单测及 API36/37 各3探针。没有新增 Android 测试或跑分。F18–F20 独立复核见 evidence/independent-review-network-result.json，主代理已核对；请求模型 deepseek-flash[1m]，消息实际报告 deepseek-flash。

## 按需阅读

- agent-analysis.json：只取当前 files/flows/findings/tasks，含固定提交行号/URL。
- evidence/handoff-tests.json：TC01–TC55 完整规格；extraTests：TC56–TC58。
- PR8_REASSESSMENT.md：重评依据；DEVELOPMENT_ROADMAP.md：PR #9 正文快照；PR8_BODY_DRAFT.md：历史草案。
- .ua 保留 scan、fingerprints、meta。归档无 Git 历史，转到真实 checkout 后核对身份，再选择刷新方式。

## 上传范围

完整结果清单与摘要见 PUBLICATION_MANIFEST.json；含原始结构提取、分批结果与 CI/复核证据。tmp 中保留的是分析结果，临时输入/生成脚本/空日志及 worker 状态未上传。机器私有绝对路径转换为相对位置或 local-user，凭据不在结果包内。后续 agent 按当前任务读取，不整体载入所有图谱/证据。
