> 历史分析记录。现行唯一开发路线为 [PR #9](https://github.com/ertutio/TabulaV3/pull/9)；此文件保留重构时的原始内容，不作为竞争计划。

# PR #8 重构草案：先保全照片，按小批次推进正确性与维护

本 PR 是后续开发计划，保留 T01–T13、F01–F17/B01–B02 和 TC01–TC55 的映射。本轮仅重新分析并起草正文，没有实施应用修复、修改依赖、触发 CI、合并或发布。

## 使用方式：每轮只领取一个子任务

为控制 Plus 用量，默认每轮完成 **一个可独立验收的子任务**，完成后交接并停止。T01–T13 是任务组，不代表一轮完成整个任务组。用户指定下一子任务后再继续；额外改进先列待办。

- 默认一个主要实现者；仅在可独立交付、交接有收益且符合 AGENTS.md 时委派。协作偏好继续适用，不自动铺开多个实现代理或重复审查。
- 只读取本轮代码、调用者、持久化和失败路径。利用既有索引定位，源码变化时更新相关知识；跨模块结构确实变化才重做全库分析。
- 先做能暴露旧问题的定点回归；功能与接口稳定后集中做必要集成验收。输入未变且证据适用时复用成功结果。项目强制 CI/钩子照常执行。
- 本轮交付：子任务编号、改动文件/提交、验证证据、未测项及建议的下一子任务。局部完成不冒充整个任务组完成，也不为省用量遗漏当前行为链的必要修复。
- 若仍横跨多个共享接口、迁移及 UI 接入，先拆为可保持应用安全运行的交付边界；同轮不附带依赖升级、性能优化或全面重构。

## 当前基线与证据

- 审查 master：d2038b8e0dc8c99fbdf2c754e88dec0a8078a725（2026-10-17 核实）。PR #8 仍是 open draft，head fe3c59800e7072c67fd688ad476286982bbb19a8，文件差异为 0，全部任务仍待实施。
- 成功 [CI run 38676233496](https://github.com/ertutio/TabulaV3/actions/runs/38676233496) 的提交 7cc5ddcbcfd4578bc2ecbd1f4e8dccd088053574 与此 master 具有相同文件树 120001e8b1e4809b7e942c466686a3813bbaf3c3。产物哈希一致，XML 为 **75 项单测通过**，API36/37 模拟器各 **3 项 MediaStore/日志核对探针通过**。这些不能代替未来修复的新回归或 ColorOS 真机验收。
- F01–F17 的关键静态路径仍可定位。F15 属缓存命中且缩放同尺寸时的条件路径。TC01–TC55 是待实施规格，均未因本次分析变为 PASS。
- [PR #6](https://github.com/ertutio/TabulaV3/pull/6) head 仍为 eb8b94b821635838b00dfbb957d6969ca48a4583。最新 [run 38573946674](https://github.com/ertutio/TabulaV3/actions/runs/38573946674) 仅 paused job 成功，build/measure/report 跳过且无 artifacts。目前没有有效冻结性能数字。保留原文记录的独立恢复请求，本次不执行恢复。

## 为什么调整

| 调整 | 源码依据 | 对计划的影响 |
| --- | --- | --- |
| T01 覆盖全部可能删源路径 | [SystemAlbumSyncManager.kt:1022](https://github.com/ertutio/TabulaV3/blob/d2038b8e0dc8c99fbdf2c754e88dec0a8078a725/app/src/main/java/com/tabula/v3/data/repository/SystemAlbumSyncManager.kt#L1022-L1034)；[SystemAlbumSyncManager.kt:1110](https://github.com/ertutio/TabulaV3/blob/d2038b8e0dc8c99fbdf2c754e88dec0a8078a725/app/src/main/java/com/tabula/v3/data/repository/SystemAlbumSyncManager.kt#L1110-L1129) | 移动回退也可能使用未验证目标，不能等 T07 才封住。 |
| T01 分成止损、复制、账本及闭环 | [AlbumManager.kt:1121](https://github.com/ertutio/TabulaV3/blob/d2038b8e0dc8c99fbdf2c754e88dec0a8078a725/app/src/main/java/com/tabula/v3/data/repository/AlbumManager.kt#L1121-L1152)；[MainActivity.kt:659](https://github.com/ertutio/TabulaV3/blob/d2038b8e0dc8c99fbdf2c754e88dec0a8078a725/app/src/main/java/com/tabula/v3/MainActivity.kt#L659-L715)；[DeckScreen.kt:1315](https://github.com/ertutio/TabulaV3/blob/d2038b8e0dc8c99fbdf2c754e88dec0a8078a725/app/src/main/java/com/tabula/v3/ui/screens/DeckScreen.kt#L1315-L1326) | 先保留源，再逐轮补证据；排队与最终成功分开，失败须回传。 |
| T03 覆盖身份与版本消费者 | [ImageHashCache.kt:70](https://github.com/ertutio/TabulaV3/blob/d2038b8e0dc8c99fbdf2c754e88dec0a8078a725/app/src/main/java/com/tabula/v3/data/cache/ImageHashCache.kt#L70-L83)；[SimilarGroupDetector.kt:221](https://github.com/ertutio/TabulaV3/blob/d2038b8e0dc8c99fbdf2c754e88dec0a8078a725/app/src/main/java/com/tabula/v3/data/repository/SimilarGroupDetector.kt#L221-L235) | 纳入哈希、冷却、使用频率、专清断点和统计；每轮迁移一簇。 |
| T11 正确性前移 | [ImageFeatureDetector.kt:73](https://github.com/ertutio/TabulaV3/blob/d2038b8e0dc8c99fbdf2c754e88dec0a8078a725/app/src/main/java/com/tabula/v3/data/repository/ImageFeatureDetector.kt#L73-L83)；[ImageFeatureState.kt:65](https://github.com/ertutio/TabulaV3/blob/d2038b8e0dc8c99fbdf2c754e88dec0a8078a725/app/src/main/java/com/tabula/v3/ui/util/ImageFeatureState.kt#L65-L73) | 繁忙被缓存为非 HDR 是正确性缺口；single-flight 等优化后置。 |
| T08 分流，T13 贯穿交付 | PR #6 现状与历史分支限定 CI | 工具准备、轻量参考、新安全基线分别领取；补丁不等全部优化。 |

新增静态映射：**F18** 移动回退未验证目标即尝试删源；**F19** 归档最终结果未反馈，action 领取有条件性交叠风险；**F20** 感知哈希只按 ID 缓存，内容变化或暂时不可读恢复后缺少重算条件。三项由独立只读会话复核，再由主代理核对源码；均未宣称设备复现。

## 推荐顺序与领取边界

主线：**T01 各小批次 → T02 → T03 → T04 → T05 → T06 → T11a → T07 → 当批 T13 验收 → T08 新安全基线 → T09 → 必要的同工具链重新校准 → T10 / T11b / T12 各自小批次 → 相应 T13 验收**。

这是默认领取顺序，技术依赖与共享文件冲突分别判断。T05、T06、T11a、T08 工具准备在接口明确后可独立，按用户当轮选择提前，默认串行。T01/T03 共定最小身份，不要求先迁移整个库才保全原图。T07 与 T04/T11a 共定接口，不设置无依据的全量前置。

| 任务组 | 每轮边界 | 依赖 / 门槛 | 测试规格 |
| --- | --- | --- | --- |
| T01 阻止原图清理删除唯一完整副本 | T01a 止损 → T01b 复制 → T01c 账本 → T01d 清理/归档闭环，超大时继续拆分 | 无；最小媒体身份与 T03 共定 | TC01、TC02、TC03、TC04、TC05、TC06、TC07；新增 TC56、TC57 |
| T02 修复回收站重载和恢复的一致性 | 一次只修快照投影、恢复核验或序列化中的一个边界 | 无；最终集合与 T03 的身份兼容 | TC08、TC09、TC10、TC11、TC12 |
| T03 建立相册与媒体的真实身份并迁移旧索引 | 先定义身份契约；之后每轮迁移一个消费者簇 | 无；先冻结契约，接入时兼容 T01 账本 | TC13、TC14、TC15、TC16；新增 TC58 |
| T04 统一删除入口并隔离无法核对的任务 | 依次接入一个删除入口，再独立处理日志与 UNKNOWN 隔离 | 最终接入依赖 T02/T03；分批准备可提前 | TC17、TC18、TC19、TC20、TC21、TC22 |
| T05 移除图集主线程补读并统一时间单位 | 一次修复同一详情读取链的完整模型与时间口径 | 无；身份适配跟随 T03 | TC23、TC24、TC25、TC26 |
| T06 修复 Bitmap 所有权和预加载生命周期 | 先 Bitmap 所有权，再预加载请求与 scope 生命周期 | 无；请求身份/版本与 T03 对齐 | TC27、TC28、TC29、TC30、TC31 |
| T11 合并特征检测并建立有界版本缓存 | T11a 正确性前移；T11b 优化后置 | T11a 可提前；T11b 需 T06、可信测量与固定工具链 | TC48、TC49、TC50、TC51 |
| T07 同步前台媒体变化并如实反馈移动结果 | 依次做前台快照、缓存失效、移动授权/结果中的一个子项 | T01/T02/T03；与 T04/T11a 共定接口 | TC32、TC33、TC34、TC35；新增 TC56、TC58 |
| T08 验收性能基线工具和可重复工作负载 | 工具验收、既定轻量参考、新安全基线分别领取 | 工具准备独立；新安全基线需选定正确性任务验收 | TC36、TC37、TC38、TC39、TC40、TC41、TC42 |
| T09 对齐 Kotlin 和 Android 构建工具链 | 一次只调整一组实际相互依赖的构建工具 | 默认先完成可运行的 T08 安全参考；实证阻塞时提前最小兼容修复 | TC43、TC44 |
| T10 减少连续归档的全库扫描和统计内存 | 一次只改统计刷新路径 | T03/T07、可信测量和固定工具链 | TC45、TC46、TC47 |
| T12 逐项维护基础依赖 | 每次一个依赖，实施时核对版本 | T09；每次一个依赖 | TC52、TC53 |
| T13 分阶段验证并交付可覆盖安装的版本 | 按当批补丁验收；验证入口准备与发布动作分别领取 | 当前选定交付批次的实际依赖和验收 | TC54、TC55 |

## 下一轮：只做 T01a 安全止损

目标：没有当前独立完整目标副本证据时保留源，覆盖原图清理和移动复制回退。允许暂时报告“副本未验证，已保留原图”；完整清理由后续子任务恢复。本批不做全量身份迁移或依赖升级。

入口：AlbumManager.getCleanableUrisForAllAlbums/getCleanableUrisForAlbum、SystemAlbumSyncManager.moveImageUsingMediaStore/moveImageToBucket 及必要调用层。先确认已有证据；旧 source_images.json 只存源 URI，不构成目标证明。

完成条件：

1. 同源/别名、目标已删除/不可读、目标未验证或复制失败时，不发起源删除，保留原记录及源字节。
2. 直接更新路径成功与复制回退分开处理；回退留下源时如实返回或显示状态，不称为移动完成。
3. 建立可在旧实现失败的定点回归，关联 TC01、TC02、TC56 中本批覆盖的断言；其余复制/崩溃规格保持未完成。
4. 应用保持可构建，执行实际改动所需检查；交付说明暂未恢复的清理场景，然后停止。此轮不同时启动 T01b。

## 任务组最终完成标准

### T01 阻止原图清理删除唯一完整副本

- T01a：安全止损。清理和移动回退没有当前独立完整副本证据时保留源，明确说明暂不能清理；不迁移全部数据。
- T01b：复制事务。统一现有两个 MediaStore 复制路径的非空流、长度/内容、发布结果、同源/去重、失败回滚和取消语义。
- T01c：副本证据账本。原子保存 source→verified destination 与版本/校验证据，保守迁移旧账本；此轮不开放清理。
- T01d：清理闭环。清理前重新校验目标，符合证据才恢复清理；处理归档领取、失败反馈与恢复。若领取和 UI 接入无法在同轮独立验收，拆成 T01d1/T01d2。

范围：SystemAlbumSyncManager.kt、AlbumManager.kt、原图清理对话框及入口、MainActivity.kt、DeckScreen.kt、moveImageUsingMediaStore、moveImageToBucket、copyImageToPath。

- 同源/别名、同名同尺寸不同内容、null/短读写、发布失败及取消均不能产生可删源的成功证据；移动回退执行同一约束。
- 原子保存并恢复源→独立完整目标及版本/校验证据；旧账本无证据时保守保留，清理前复核目标。
- 并发提交/撤销只有一个有效 action 转换；失败和待恢复回到 UI；健康归档、清理及崩溃恢复均有回归。

证据入口：F01 [DeckScreen.kt:1296](https://github.com/ertutio/TabulaV3/blob/d2038b8e0dc8c99fbdf2c754e88dec0a8078a725/app/src/main/java/com/tabula/v3/ui/screens/DeckScreen.kt#L1296-L1321)；其余按关联 F 编号查源码。测试编号见上表，均为待实施规格。

### T02 修复回收站重载和恢复的一致性

范围：MainActivity.kt、RecycleBinManager.kt、RecycleBinScreen.kt。

- 每次加载都由原始媒体快照减软删除集合派生浏览/推荐；加入回收站、重载、重建和恢复一致。
- 普通非 pending 条目恢复前也核验 PRESENT/MISSING/UNKNOWN；未知保留，缺失不加入图库。
- orientation 新旧 JSON 往返正确；批量恢复共享记录事务，部分失败/并发/重启保留未确认项。

证据入口：F04 [MainActivity.kt:628](https://github.com/ertutio/TabulaV3/blob/d2038b8e0dc8c99fbdf2c754e88dec0a8078a725/app/src/main/java/com/tabula/v3/MainActivity.kt#L628-L638)；其余按关联 F 编号查源码。测试编号见上表，均为待实施规格。

### T03 建立相册与媒体的真实身份并迁移旧索引

范围：ImageFile.kt、LocalImageRepository.kt、AlbumManager.kt、AlbumCleanupImageResolver.kt、使用 albumId 的页面和持久化。

- 图集显示名与真实身份分离，媒体持有卷/具体 URI/内容版本；同名多目录或多卷分别查询、统计、归档及清理。
- 每轮迁移一簇旧键，冲突保留待解析；失败可恢复且重复执行幂等，保持兼容读取。
- 消费者清单含元数据、AlbumUsageTracker、AppPreferences 冷却/专清、ImageHashCache、RecommendationEngine、StorageStatsManager 及 UI 选择；逐项决定迁移或失效。

证据入口：F06 [LocalImageRepository.kt:291](https://github.com/ertutio/TabulaV3/blob/d2038b8e0dc8c99fbdf2c754e88dec0a8078a725/app/src/main/java/com/tabula/v3/data/repository/LocalImageRepository.kt#L291-L335)；其余按关联 F 编号查源码。测试编号见上表，均为待实施规格。

### T04 统一删除入口并隔离无法核对的任务

范围：MainActivity.kt、FileOperationManager.kt、BatchDeletion.kt、RecycleBinDeletion.kt、DeletePermissionSession.kt。

- 三类删除入口复用现有串行 deleteInBatches，按项结果驱动各自记录适配器；详情不再只返回整体 Boolean。
- 2001项可分批、取消后不请求后续批次、部分成功保留；仅无效请求可拆分，服务/权限错误不盲目重试。
- 按任务/媒体隔离 UNKNOWN，保留相关记录且不阻断无关照片；真实授权、进程恢复及日志写失败不重复删除。

证据入口：F05 [MainActivity.kt:470](https://github.com/ertutio/TabulaV3/blob/d2038b8e0dc8c99fbdf2c754e88dec0a8078a725/app/src/main/java/com/tabula/v3/MainActivity.kt#L470-L495)；其余按关联 F 编号查源码。测试编号见上表，均为待实施规格。

### T05 移除图集主线程补读并统一时间单位

范围：AlbumManager.kt:getImageMappingsForAlbum、AlbumViewScreen.kt、LocalImageRepository.kt。

- 详情直接消费仓库的完整 ImageFile，必要补读在 IO 上执行，不因映射丢字段产生逐 URI N+1。
- DATE_MODIFIED 统一秒转领域毫秒，缓存与新读混合排序正确。
- 加载错误有明确状态；切相册或重建取消后旧结果不覆盖新页面。

证据入口：F07 [AlbumManager.kt:537](https://github.com/ertutio/TabulaV3/blob/d2038b8e0dc8c99fbdf2c754e88dec0a8078a725/app/src/main/java/com/tabula/v3/data/repository/AlbumManager.kt#L537-L539)；其余按关联 F 编号查源码。测试编号见上表，均为待实施规格。

### T06 修复 Bitmap 所有权和预加载生命周期

范围：PreloadingManager.kt、CardStack.kt 两种模式、ImageCard.kt、CoilSetup.kt。

- 两种 CardStack 均不回收 Coil 借用对象；同尺寸缩放、动画完成/替换/取消/退出分别验证所有权。
- 预加载身份包含 URI/版本/选项，排队与成功/失败分开；防抖取消同中心、同索引换批和失败可重试。
- 持有并释放过期请求 Disposable 与 manager 自有 scope，旧请求不影响新批次，保持显示质量和手势。

证据入口：F14 [PreloadingManager.kt:32](https://github.com/ertutio/TabulaV3/blob/d2038b8e0dc8c99fbdf2c754e88dec0a8078a725/app/src/main/java/com/tabula/v3/di/PreloadingManager.kt#L32-L71)；其余按关联 F 编号查源码。测试编号见上表，均为待实施规格。

### T11 合并特征检测并建立有界版本缓存

范围：ImageFeatureState.kt、ImageFeatureDetector.kt、ImageCard.kt。

- T11a：忙碌/失败/取消不缓存为可靠阴性，内容版本与开关失效正确，覆盖必要重试及取消；作为前置正确性修复验收。
- T11b：在可靠语义上做 single-flight、有界缓存、EXIF/XMP 复用与请求构建优化，再测资源收益。
- TC49/TC50 的语义和版本断言前移；TC48/TC51 按正确性/优化断言拆分，保留 HDR 与实况能力。

证据入口：F16 [ImageFeatureState.kt:18](https://github.com/ertutio/TabulaV3/blob/d2038b8e0dc8c99fbdf2c754e88dec0a8078a725/app/src/main/java/com/tabula/v3/ui/util/ImageFeatureState.kt#L18-L32)；其余按关联 F 编号查源码。测试编号见上表，均为待实施规格。

### T07 同步前台媒体变化并如实反馈移动结果

范围：MainActivity.kt、MediaPermissionPolicy.kt、SystemAlbumSyncManager.kt、AlbumManager.kt、移动操作页面。

- 区分 NONE/PARTIAL/FULL，覆盖部分集合 A→B、全量→部分及外部拍照/移动/删除；只提交较新快照，保持正在交互批次。
- 刷新同步失效推荐/专清与感知哈希，内容编辑或临时不可读恢复后使用正确版本；小组件先明确计数口径。
- 移动通过可恢复写授权继续，逐项返回 Moved/CopiedButSourceRetained/NeedsPermission/Failed；拒绝或源删除失败准确反馈且保留源。

证据入口：F04 [MainActivity.kt:628](https://github.com/ertutio/TabulaV3/blob/d2038b8e0dc8c99fbdf2c754e88dec0a8078a725/app/src/main/java/com/tabula/v3/MainActivity.kt#L628-L638)；其余按关联 F 编号查源码。测试编号见上表，均为待实施规格。

### T08 验收性能基线工具和可重复工作负载

范围：PR #6 的 workflow、protocol、scripts、Macrobenchmark。

- B01/B02：拉取失败、缺/空/不可解析 trace、迭代/文件错配、协议参数与执行不一致时拒绝 valid；完整成功 fixture 仍通过。
- A/A 保存逐轮 trace/原始值/真实动作/环境及 APK/协议/数据摘要，先估计噪声再比较；TTID、解码/查询、PSS快照和授权等待分开。
- 既定轻量参考与安全修复后扩展协议分版本；工具链改变后重新校准其受影响比较，历史身份不覆盖。

三条记录分开：B01/B02 工具验收可先做；原独立恢复请求保持96张 JPEG、两次会话与原指标，为当前 master 建可区分参考；正确性修复及 T11a 验收后的安全 SHA 再建新版本，可另扩展1k/10k、跨批次和连续归档负载。保留旧474129e…身份。本次没有授权恢复测量或跑 CI。

### T09 对齐 Kotlin 和 Android 构建工具链

范围：gradle/libs.versions.toml、gradle/wrapper/gradle-wrapper.properties、build.gradle.kts、CI验证配置。

- 实施时核对实际 Kotlin/Compose 插件、AGP/R8、Gradle/JDK 及当前官方支持；旧候选不直接冻结为目标。
- 独立调整工具链并验证 Release 单测/构建、R8、包名/签名及覆盖安装；可独立回退。
- 已有成功构建与官方兼容声明分别记录。仅实证阻塞时按 EX01 提前最小修复；新工具链上的优化重新校准。

### T10 减少连续归档的全库扫描和统计内存

范围：AlbumManager.kt:commitPendingArchive/refreshAlbumsFromSystem、LocalImageRepository.kt:getAllBucketsWithInfo。

- 连续归档合并统计刷新，必要时增量计数/封面，保留最终 MediaStore 对账。
- 固定输入记录 Provider 查询次数，避免每张全库重扫与仅计数却保存全部 ID；异常/重启能重建一致结果。
- 固定工具链和有效基线下比较时延/分配；未超噪声只报告操作次数变化。

证据入口：F17 [AlbumManager.kt:1134](https://github.com/ertutio/TabulaV3/blob/d2038b8e0dc8c99fbdf2c754e88dec0a8078a725/app/src/main/java/com/tabula/v3/data/repository/AlbumManager.kt#L1134-L1147)；其余按关联 F 编号查源码。测试编号见上表，均为待实施规格。

### T12 逐项维护基础依赖

范围：gradle/libs.versions.toml、app/build.gradle.kts、依赖锁定与CI。

- 每次一个基础依赖，按当时官方发布/真实解析选择版本，单项验证、单项回退。
- 分别覆盖 EXIF方向/实况、协程取消/并发或 Konfetti 生命周期，再完成必要 Release 检查。
- Compose/Coil 大版本迁移、引入未使用 Media3 不在此组默认范围；有阻碍则明确延期。

### T13 分阶段验证并交付可覆盖安装的版本

范围：通用验证工作流、版本说明、发布产物与校验清单。

- 每批交付明确范围与所需回归、真实设备/迁移证据；源码 SHA、实际 APK 与签名可追溯，旧数据保留。
- 验证覆盖实际开发 ref/SHA，通用验证入口与发布动作分轮；安全补丁不等待全部远期任务。
- 后续发布复用既有 fork 签名；历史固定3.3.4的首次签名工作流不可直接套用，合并/发布仍依届时授权。

## 新增测试规格

### TC56 移动回退的目标完整性与删源结果

关联：T01、T07；状态：NOT_RUN。

输入：合成媒体/Fake Provider：更新路径返回 0；分别注入 null 流、短复制、写失败、发布失败、同源别名及删源失败。

验收：目标未被证明独立完整时不删源；删源失败返回 CopiedButSourceRetained；重启恢复不会补发不可逆删除。

### TC57 归档 action 的并发、撤销和失败反馈

关联：T01；状态：NOT_RUN。

输入：同一 action 两个并发提交与撤销交错；复制失败、记录失败、排队后重建分别注入。

验收：每 action 至多一次有效副作用，失败/待恢复状态可见；排队不冒充最终归档成功，卡片与账本可恢复一致。

### TC58 感知哈希内容版本及可读性恢复

关联：T03、T07；状态：NOT_RUN。

输入：同媒体 ID 修改内容，及一次临时读取失败后恢复访问；覆盖进程重启、旧缓存和当前专清/推荐消费者。

验收：旧内容哈希失效，暂时失败按明确有界策略重新计算，不覆盖无关冷却/清理进度。

## 阶段交付与共用约束

- T01a 回归及必要集成通过即可评估止损补丁；说明保守保留的清理能力，不等待迁移/跑分。T01b–d 的证据、迁移和恢复通过后，再开放完整清理。
- 正确性按所选子任务验收，必要处补权限、进程死亡、多卷和目标真机证据；性能/工具链/依赖另保留对应来源。无测量时只报告操作次数事实。
- 发布候选再执行该批所需 Release、R8、签名/包名、覆盖安装和旧数据恢复。合并/发布按当时授权执行，不因本计划自动开展远端动作。
- 保留 #5 的 AtomicFile、互斥、意图日志、错误分类/授权恢复，以及 #7 的 recentVolumes 减 mountedVolumes。PRESENT/MISSING/UNKNOWN 分开，未知记录保留。
- 保持 com.tabula.v3、现有 fork 签名、用户照片与记录、原作者鸣谢、分享、HDR/实况、两种卡片样式和手势。歧义迁移保留待解析。
- 删除/移动/故障注入只用测试自建且有 manifest 的合成媒体，按白名单清理。自有媒体探针不代替其他应用照片的系统授权。
- 静态结论、测试代码、单测、模拟器、真机分别记录。TC 状态附 SHA、实际命令/工作流、环境和证据；NOT_RUN/BLOCKED/FAIL 不写成 PASS。
- 每个可写文件只有一名写入者；MainActivity、AlbumManager、SystemAlbumSyncManager 和版本目录的接入串行整合。文档沿用本 PR 编号，不每轮另建全库报告。
- 产品保持确定性本地处理，本计划未引入运行时模型请求。不承诺具体 Plus 消耗或可完成轮数，用单任务、按需读取和验证复用控制范围。

## 暂缓方向

整包 R8 keep 收紧、真正 ART Baseline Profiles、大图库分页/元数据索引、Compose/Coil 大版本迁移，在独立问题与证据明确后立项。PR #4 继续由 PR #8 取代。

## 技术依据

- [MediaStore 删除请求上限](https://developer.android.com/reference/android/provider/MediaStore)：target Baklava 及以上每次请求最多 2000 URI；支持 T04 边界。
- [Bitmap 缩放返回值](https://developer.android.com/reference/android/graphics/Bitmap)：相同目标尺寸可返回源 Bitmap；支持 F15 条件分支。
- [部分照片访问](https://developer.android.com/about/versions/14/changes/partial-photo-video-access)：前台恢复重新核对权限与媒体结果；支持 T07。
- [Kotlin/AGP/R8 兼容](https://developer.android.com/build/kotlin-support)：实施 T09 时结合实际 R8/AGP 版本核验，不复述旧候选为当前最低要求。
- [AGP 8.13 发布说明](https://developer.android.com/build/releases/agp-8-13-0-release-notes)：8.13.2 的 Kotlin 2.3 支持可作候选证据，不能代替本仓库构建验收。
