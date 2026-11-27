# Tabula 4.0 预览版之后的性能与依赖计划

最后一条文案 PR [#104](https://github.com/ertutio/TabulaV3/pull/104) 合入后，以该轮最终的 `master` 提交作为 4.0 预览版源码基准。届时在首份性能报告里登记完整 SHA、实际构建版本和 APK 摘要。本计划的源码核查基于 `03ff45da28a608fa5f0bc9d5785c2bb85d3324a7`，其中已包含批量撤销。预览版收尾可以继续，基线工具和测试数据可以先准备。

目标是缩短大图库里的等待、减少重复读图和计算，并让依赖升级能逐项验证和回退。开发顺序为 **刷新基线 → 两个小型代码优化 → 大图库与 OCR 优化 → 按结果调整后续排期**。依赖盘点可与基线准备并行，实际升级在预览版基线可复现后单独提交。

## 开发与验收集中在云端

本地开发环境计划清理，仅保留 Android SDK 等基础设施。性能基线的工具开发与调试集中在云端工作区，APK 构建、Android 模拟器测量和报告生成统一在 GitHub Actions 执行。后续性能 PR 和依赖 PR 的基线对照也沿用这条路径，可以在本地 AVD、Gradle 缓存、测试照片和日志清理后继续推进。

从全新云端 runner 验证流程可以重建。工作流按仓库配置准备 JDK、SDK 和系统镜像，用仓库里的生成器创建合成数据。把基线报告、APK 与环境摘要、原始日志和 trace 上传到 Actions 产物，并在 PR 中记录 run 链接与对应提交。需要长期复现的夹具参数和报告摘要随仓库保存。真机验证留作后续补充。

## 现有结果

- 仓库已有 [API 36 云模拟器协议](../benchmark/protocol-v1.json)、[Macrobenchmark](../benchmark/macrobenchmark/src/main/java/com/tabula/v3/benchmark/TabulaPerformanceBenchmark.kt) 和 [GitHub Actions 工作流](../.github/workflows/performance-baseline.yml)。[历史 A/A 报告](../benchmark/results/aa-2026-11-13/README.md) 的应用 SHA 是 `474129ed`，两轮各用 96 张合成 JPEG。冷启动首帧中位数为约 1,763 ms，同一构建的两轮热启动中位数相差 42.3%。这组数据可验证测量流程和估计云端波动。4.0 预览版的性能待测。 [历史运行](https://github.com/ertutio/TabulaV3/actions/runs/43387329019)
- OCR 试点在本地 x86_64 模拟器约为 419–583 ms/张，报告另记载一组 2 核 CI 约 1,062 ms/张。该试点的四 ABI 通用 Release APK 增加约 29.35 MiB。重新测量预览版的 APK 体积、首批分析与缓存命中情况。日常手机上的耗时留待真机对照。 [OCR 试点报告](intent-batch-pilot-report-2026-11-25.md)
- 近期玻璃 CI 出现过 `adb` 离线或设备消失。复用已有失败分类，分别记录产品断言失败和环境中断。旧扫描晚返回、部分图库权限和跨卷身份已有回归资产，数据加载优化继续使用这些验证。 [玻璃 CI](https://github.com/ertutio/TabulaV3/actions/runs/46022650375) · [旧扫描验收](notes-stale-scan-2026-11-15.md)

## 1. 在 GitHub Actions 刷新预览版基线

1. 复用现有手动工作流、数据生成器和测量模块。当前工作流固定历史应用 SHA。增加可复现的基线输入，并记录预览版完整 SHA、候选 SHA、测量工具 SHA、模拟器 build、渲染模式、编译模式、依赖锁定信息与夹具摘要。旧协议和历史结果继续可追溯。使用保留 R8 的 Release 派生可测构建及隔离测试环境。
2. 先跑 **A/A** 验证新基线的波动，然后在同一会话、同一模拟器以 **A→B / B→A** 对照候选。两侧使用同一份合成照片、应用设置、权限和缓存条件。报告每项中位数、P95、样本数、两会话差异、原始日志和 trace。当前端动作未命中、帧无有效样本或设备断连时，标明未完成。首轮以可复现为准，积累重复数据后再定合入阈值。
3. 保留现有启动首帧、切牌、摸牌、大图和 PSS 快照。另记**首张照片可见**的时点。新增较大图库的固定夹具，先试 1,000 张并记录来源、回收站和同大小候选的分布。OCR 另用仓库的合成截图，避免把 1,000 张照片逐张识别变成每次 CI 的固定成本。夹具规模根据首轮耗时调整并冻结。
4. 新增三条定向测量。**副本扫描**记录库行数、哈希文件数、读取字节和主线程筛选耗时。**暂存清理与批量撤销**记录打开到候选可操作、提交到可撤销、撤销到列表恢复，以及 MediaStore 查询次数与返回行数。**凑一批**记录首次模型准备、解码和 OCR 次数、命中条数、缓存写入次数、首批可操作耗时。Release 包记录总字节及 ABI、模型资源体积。玻璃关闭作为日常基准，单列开关开启时的查看器试验，采到有效帧后再评价渲染。

## 2. 代码优化队列

| 顺序 | 源码发现与改法 | 验收重点 |
| --- | --- | --- |
| 1 · 副本候选 | `MainActivity` 的副本扫描在每个大小匹配的图库候选上重新构建回收站身份集合。先把集合提到循环外，后台筛选同大小候选，保留取消。测量仍有大量重复文件读取时，再评估按卷、媒体 ID、内容版本做有界摘要缓存。 [扫描入口](../app/src/main/java/com/tabula/v3/MainActivity.kt#L986) · [文件摘要](../app/src/main/java/com/tabula/v3/data/duplicate/ContentDigestCalculator.kt#L41) | 同一夹具下候选与提示结果一致，逐行重复建集合消除，比较筛选耗时与读取量。确认永久删除前仍重新核验副本可读性、内容及本次删除集合。 |
| 2 · 批量提交与撤销 | 「凑一批」提交前和批量撤销的核验都调用 `loadAllData()`。它会把全库行转成对象，并派生卡片列表和系统图集。先把这两处改为对本批可确认的卷和媒体 ID 定向查询，按实际结果更新。 [提交](../app/src/main/java/com/tabula/v3/MainActivity.kt#L2939) · [撤销](../app/src/main/java/com/tabula/v3/MainActivity.kt#L1493) · [全库查询](../app/src/main/java/com/tabula/v3/data/repository/LocalImageRepository.kt#L528) | 少量选择下报告返回行数与两段耗时。覆盖跨卷同 ID、旧聚合 URI、部分权限、系统已入回收站、原文件被改或删、已恢复后重新入站，以及连续两批独立撤销。未能确认的项沿用保守处理。 |
| 3 · 暂存清理与图库刷新 | 暂存入口在主线程汇总来源；换范围、跳过或撤销后又筛选全库并按日分组。来源汇总先分桶再展开，随后多次遍历求名称、数量和时间。先把纯计算移到后台，用同一代图库快照复用来源汇总，减少重复遍历。另给回前台的全库快照增加分段计时，确认重扫成本后再评估合并刷新或增量查询。 [清理入口](../app/src/main/java/com/tabula/v3/MainActivity.kt#L1046) · [来源汇总](../app/src/main/java/com/tabula/v3/data/cleanup/StagedCleanupRules.kt#L160) · [前台刷新](../app/src/main/java/com/tabula/v3/MainActivity.kt#L1329) | 大图库入口和切换来源的首次可交互耗时。快速切换只提交最新结果。保留部分授权集合变化、外部编辑或删除、日期跨天、回收站对账及旧扫描晚返回的回归。 |
| 4 · OCR 与写缓存 | 「凑一批」逐张处理所选来源，推荐与低优先级合计的 10 张上限在组批时才生效。只要有未命中，当前写回会遍历批结果；每条写入又复制偏好快照并可能单独淘汰。第一步按实际新算条目整批写入，一次检查容量和淘汰。第二步测量关闭辅助、开启辅助和换服务时的重复 OCR，收益稳定后再试同会话复用。 [分析](../app/src/main/java/com/tabula/v3/data/intent/IntentBatchAnalyzer.kt#L100) · [现有缓存](../app/src/main/java/com/tabula/v3/data/intent/IntentVerdictCache.kt#L69) | 对比首开与重开，以及授权前后实际解码、OCR、写入和请求次数。保留跨卷身份、媒体指纹、规则版本、引擎与服务隔离，保留取消、保护类扣留和授权失效。会话内实验仅在内存复用识别结果，保持现有外发数据范围。 |
| 按数据排期 · 大图和相似组 | 普通查看器的高清请求尺寸随动画缩放更新。先数双击、拖动、复原实际触发的请求和解码；如有重复，再试停稳后分档请求。相似推荐已有时间范围和 dHash 缓存，先采相似组重建次数、候选对和哈希命中率，再决定是否细化缓存失效。 [高清请求](../app/src/main/java/com/tabula/v3/ui/components/ViewerOverlay.kt#L638) · [相似组检测](../app/src/main/java/com/tabula/v3/data/repository/SimilarGroupDetector.kt#L110) | 检查高清停稳后的画质、HDR/实况、玻璃采样及回退。相似推荐保留原组身份、冷却和抽样行为。 |

## 3. 依赖升级与发布体积

先登记当前版本、官方兼容范围、候选版本和升级原因。核查时使用的 [版本目录](../gradle/libs.versions.toml) 包含 AGP 8.9.1、Kotlin 2.3.0、Compose BOM 2024.12.01、Coil 2.7.0、Media3 1.5.1、协程 1.10.2、Tesseract4Android 4.8.0；[Gradle Wrapper](../gradle/wrapper/gradle-wrapper.properties) 为 8.11.1，应用 minSdk 31、compile/target SDK 36。构建工具链优先核对 Gradle、AGP、JDK、Kotlin、Compose 插件和 D8/R8 的组合，尤其是 Release 构建。候选版本按实施时的官方兼容表和回归结果选定。 [Kotlin 与 AGP 兼容表](https://developer.android.com/build/kotlin-support) · [AGP 发布说明](https://developer.android.com/build/releases/gradle-plugin)

- **按关联拆小 PR。** 构建工具链先独立验证 Debug、Release、R8、设备测试 APK 和现有性能任务。Jetpack / Compose 一组，复测卡片、弹层、回收站与玻璃门控。协程若升级，补取消与旧扫描晚返回。Coil 和 Media3 分开升级，分别复测缩略图缓存、大图、EXIF/HDR、实况播放。涉及跨主版本的迁移单列。
- **OCR 与 APK 体积单独决策。** 用 4.0 预览版实测通用包和各 ABI 的成本，再比较继续发布通用 APK 与提供按 ABI 构建的下载方案。把 GitHub 下载体验、设备覆盖、离线模型、R8 和签名/升级兼容都列入决策。需要调整包分发或 OCR 引擎时另开 PR。
- **每组保留可回退的边界。** 附依赖差异、验证过的构建与设备配置、受影响路径的 A/B 结果。性能变化落在 A/A 波动内时，记录为差异未确定。云模拟器先用于代码归因和回归筛查；ARM OCR、GPU 帧耗时、HDR 和实际滑动手感在日后真机补测。

## 首批交付

1. **测量 PR** 固定 4.0 预览版源码基准，复用现有工作流，补副本扫描和批量提交/撤销场景，交第一份可重复 A/A 报告。
2. **代码 PR 1** 消除副本候选筛选的重复集合构建，附结果一致性和同环境 A/B。
3. **代码 PR 2** 缩小提交与撤销的媒体查询范围，附边界回归、查询行数和耗时。
4. **依赖 PR 1** 基线可复现后，从兼容性收益明确的第一组开始。暂存清理和 OCR 依第一批测量结果排期。
