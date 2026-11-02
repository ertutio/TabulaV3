> 历史分析记录。现行唯一开发路线为 [PR #9](https://github.com/ertutio/TabulaV3/pull/9)；此文件保留重构时的原始内容，不作为竞争计划。

# PR #8 重评记录

结论：保留照片安全优先和编号，改为每轮一个子任务；补齐共享安全边界，T11正确性前移，T08/T13按真实阶段拆分。拟更新正文见 PR8_BODY_DRAFT.md，此记录只保存依据。

## 基线与证据

固定 master d2038b8e0dc8c99fbdf2c754e88dec0a8078a725；PR8 head fe3c59800e7072c67fd688ad476286982bbb19a8，open draft、0 changed files。145文本文件有索引，28核心文件追踪关键行为，117支持文件为 surface。源码无架构/验收文档，采用真实配置、测试和CI；F01–F20均为静态结论。

远端元数据在 evidence，CI在 evidence/ci。成功CI的 tree 等价和产物 digest 已核验，75单测与两端各3模拟器探针可复用。本轮没有触发CI或执行Android测试；没有有效性能基线。

## 逐项评估

| 原任务 | 当前结论 | 重构决定 | 单轮边界/依赖 | 证据 |
| --- | --- | --- | --- | --- |
| T01 阻止原图清理删除唯一完整副本 | F01、F02、F03、F18、F19 静态缺口仍存在 | 保留首位并拆成单轮可交付子任务；扩展所有删源调用者 | 无；最小媒体身份与 T03 共定；T01a 止损 → T01b 复制 → T01c 账本 → T01d 清理/归档闭环，超大时继续拆分 | F01 [DeckScreen.kt:1296](https://github.com/ertutio/TabulaV3/blob/d2038b8e0dc8c99fbdf2c754e88dec0a8078a725/app/src/main/java/com/tabula/v3/ui/screens/DeckScreen.kt#L1296-L1321)；F02 [SystemAlbumSyncManager.kt:515](https://github.com/ertutio/TabulaV3/blob/d2038b8e0dc8c99fbdf2c754e88dec0a8078a725/app/src/main/java/com/tabula/v3/data/repository/SystemAlbumSyncManager.kt#L515-L567)；F03 [SystemAlbumSyncManager.kt:687](https://github.com/ertutio/TabulaV3/blob/d2038b8e0dc8c99fbdf2c754e88dec0a8078a725/app/src/main/java/com/tabula/v3/data/repository/SystemAlbumSyncManager.kt#L687-L732) |
| T02 修复回收站重载和恢复的一致性 | F04、F11、F12 静态缺口仍存在 | 保留；明确单一快照派生与批量恢复 | 无；最终集合与 T03 的身份兼容；一次只修快照投影、恢复核验或序列化中的一个边界 | F04 [MainActivity.kt:628](https://github.com/ertutio/TabulaV3/blob/d2038b8e0dc8c99fbdf2c754e88dec0a8078a725/app/src/main/java/com/tabula/v3/MainActivity.kt#L628-L638)；F11 [MainActivity.kt:839](https://github.com/ertutio/TabulaV3/blob/d2038b8e0dc8c99fbdf2c754e88dec0a8078a725/app/src/main/java/com/tabula/v3/MainActivity.kt#L839-L856)；F12 [ImageFile.kt:9](https://github.com/ertutio/TabulaV3/blob/d2038b8e0dc8c99fbdf2c754e88dec0a8078a725/app/src/main/java/com/tabula/v3/data/model/ImageFile.kt#L9-L45) |
| T03 建立相册与媒体的真实身份并迁移旧索引 | F02、F06、F20 静态缺口仍存在 | 保留；先冻结身份契约，再迁移所有消费者 | 无；先冻结契约，接入时兼容 T01 账本；先定义身份契约；之后每轮迁移一个消费者簇 | F02 [SystemAlbumSyncManager.kt:515](https://github.com/ertutio/TabulaV3/blob/d2038b8e0dc8c99fbdf2c754e88dec0a8078a725/app/src/main/java/com/tabula/v3/data/repository/SystemAlbumSyncManager.kt#L515-L567)；F06 [LocalImageRepository.kt:291](https://github.com/ertutio/TabulaV3/blob/d2038b8e0dc8c99fbdf2c754e88dec0a8078a725/app/src/main/java/com/tabula/v3/data/repository/LocalImageRepository.kt#L291-L335)；F20 [ImageHashCache.kt:70](https://github.com/ertutio/TabulaV3/blob/d2038b8e0dc8c99fbdf2c754e88dec0a8078a725/app/src/main/java/com/tabula/v3/data/cache/ImageHashCache.kt#L70-L83) |
| T04 统一删除入口并隔离无法核对的任务 | F05、F09 静态缺口仍存在 | 保留并按依赖拆分，复用既有删除算法 | 最终接入依赖 T02/T03；分批准备可提前；依次接入一个删除入口，再独立处理日志与 UNKNOWN 隔离 | F05 [MainActivity.kt:470](https://github.com/ertutio/TabulaV3/blob/d2038b8e0dc8c99fbdf2c754e88dec0a8078a725/app/src/main/java/com/tabula/v3/MainActivity.kt#L470-L495)；F09 [build.gradle.kts:25](https://github.com/ertutio/TabulaV3/blob/d2038b8e0dc8c99fbdf2c754e88dec0a8078a725/app/build.gradle.kts#L25-L33) |
| T05 移除图集主线程补读并统一时间单位 | F07、F08 静态缺口仍存在 | 保留；收窄为领域模型读取与单位修复 | 无；身份适配跟随 T03；一次修复同一详情读取链的完整模型与时间口径 | F07 [AlbumManager.kt:537](https://github.com/ertutio/TabulaV3/blob/d2038b8e0dc8c99fbdf2c754e88dec0a8078a725/app/src/main/java/com/tabula/v3/data/repository/AlbumManager.kt#L537-L539)；F08 [ImageFile.kt:7](https://github.com/ertutio/TabulaV3/blob/d2038b8e0dc8c99fbdf2c754e88dec0a8078a725/app/src/main/java/com/tabula/v3/data/model/ImageFile.kt#L7-L24) |
| T06 修复 Bitmap 所有权和预加载生命周期 | F14、F15 静态缺口仍存在 | 保留；分别验收 Bitmap 所有权和预加载生命周期 | 无；请求身份/版本与 T03 对齐；先 Bitmap 所有权，再预加载请求与 scope 生命周期 | F14 [PreloadingManager.kt:32](https://github.com/ertutio/TabulaV3/blob/d2038b8e0dc8c99fbdf2c754e88dec0a8078a725/app/src/main/java/com/tabula/v3/di/PreloadingManager.kt#L32-L71)；F15 [TabulaApplication.kt:14](https://github.com/ertutio/TabulaV3/blob/d2038b8e0dc8c99fbdf2c754e88dec0a8078a725/app/src/main/java/com/tabula/v3/TabulaApplication.kt#L14-L18) |
| T07 同步前台媒体变化并如实反馈移动结果 | F04、F10、F13、F18、F20 静态缺口仍存在 | 保留并聚焦快照协调和真实移动结果；安全删源前移 T01 | T01/T02/T03；与 T04/T11a 共定接口；依次做前台快照、缓存失效、移动授权/结果中的一个子项 | F04 [MainActivity.kt:628](https://github.com/ertutio/TabulaV3/blob/d2038b8e0dc8c99fbdf2c754e88dec0a8078a725/app/src/main/java/com/tabula/v3/MainActivity.kt#L628-L638)；F10 [MainActivity.kt:131](https://github.com/ertutio/TabulaV3/blob/d2038b8e0dc8c99fbdf2c754e88dec0a8078a725/app/src/main/java/com/tabula/v3/MainActivity.kt#L131-L145)；F13 [SystemAlbumSyncManager.kt:1001](https://github.com/ertutio/TabulaV3/blob/d2038b8e0dc8c99fbdf2c754e88dec0a8078a725/app/src/main/java/com/tabula/v3/data/repository/SystemAlbumSyncManager.kt#L1001-L1051) |
| T08 验收性能基线工具和可重复工作负载 | 维护/交付未实施 | 拆为工具准备、既定参考与新安全基线三条有版本的记录 | 工具准备独立；新安全基线需选定正确性任务验收；工具验收、既定轻量参考、新安全基线分别领取 | 维护/交付任务，依据当前构建配置及已有 CI |
| T09 对齐 Kotlin 和 Android 构建工具链 | 维护/交付未实施 | 保留为独立维护项，解除与“基线工具修复”的循环 | 默认先完成可运行的 T08 安全参考；实证阻塞时提前最小兼容修复；一次只调整一组实际相互依赖的构建工具 | 维护/交付任务，依据当前构建配置及已有 CI |
| T10 减少连续归档的全库扫描和统计内存 | F17 静态缺口仍存在 | 保留为证据驱动的优化 | T03/T07、可信测量和固定工具链；一次只改统计刷新路径 | F17 [AlbumManager.kt:1134](https://github.com/ertutio/TabulaV3/blob/d2038b8e0dc8c99fbdf2c754e88dec0a8078a725/app/src/main/java/com/tabula/v3/data/repository/AlbumManager.kt#L1134-L1147) |
| T11 合并特征检测并建立有界版本缓存 | F16 静态缺口仍存在 | 拆分并把正确性部分前移 | T11a 可提前；T11b 需 T06、可信测量与固定工具链；T11a 正确性前移；T11b 优化后置 | F16 [ImageFeatureState.kt:18](https://github.com/ertutio/TabulaV3/blob/d2038b8e0dc8c99fbdf2c754e88dec0a8078a725/app/src/main/java/com/tabula/v3/ui/util/ImageFeatureState.kt#L18-L32) |
| T12 逐项维护基础依赖 | 维护/交付未实施 | 保留逐项维护，候选版本在实施时核验 | T09；每次一个依赖；每次一个依赖，实施时核对版本 | 维护/交付任务，依据当前构建配置及已有 CI |
| T13 分阶段验证并交付可覆盖安装的版本 | 维护/交付未实施 | 改为贯穿每批交付的门槛，保留原编号 | 当前选定交付批次的实际依赖和验收；按当批补丁验收；验证入口准备与发布动作分别领取 | 维护/交付任务，依据当前构建配置及已有 CI |

## 新发现

### F18 移动的复制回退也可能在未得到完整目标时删源

更新路径返回 0 后，moveImageToBucket 调用 copyImageToPath；后者遇 null 流仍可发布非空 URI，调用者随后尝试 deleteOriginalImage。另一移动入口也复用按名称/大小判等的复制函数。删源成功时可失去唯一完整源；是否触发取决于 Provider/故障输入，尚未设备复现。

关联：T01、T07。证据：[SystemAlbumSyncManager.kt:398](https://github.com/ertutio/TabulaV3/blob/d2038b8e0dc8c99fbdf2c754e88dec0a8078a725/app/src/main/java/com/tabula/v3/data/repository/SystemAlbumSyncManager.kt#L398-L425)；[SystemAlbumSyncManager.kt:1022](https://github.com/ertutio/TabulaV3/blob/d2038b8e0dc8c99fbdf2c754e88dec0a8078a725/app/src/main/java/com/tabula/v3/data/repository/SystemAlbumSyncManager.kt#L1022-L1034)；[SystemAlbumSyncManager.kt:1110](https://github.com/ertutio/TabulaV3/blob/d2038b8e0dc8c99fbdf2c754e88dec0a8078a725/app/src/main/java/com/tabula/v3/data/repository/SystemAlbumSyncManager.kt#L1110-L1129)。

### F19 归档结果未回传 UI，action 领取也不是一个原子状态转换

反馈缺口可确认：Deck 在排队时移除当前卡片并显示已添加；Main 的提交回调忽略 Boolean，复制失败时 action 已出队。另有条件性并发风险：synchronizedList 只保护单次调用，分开的 find/remove 不是原子领取；两个提交或撤销若交叠可能继续同一 action。实际事件交叠及副作用次数尚未动态验证。纳入 T01 的反馈、幂等领取和恢复验收。

关联：T01。证据：[DeckScreen.kt:1315](https://github.com/ertutio/TabulaV3/blob/d2038b8e0dc8c99fbdf2c754e88dec0a8078a725/app/src/main/java/com/tabula/v3/ui/screens/DeckScreen.kt#L1315-L1326)；[MainActivity.kt:659](https://github.com/ertutio/TabulaV3/blob/d2038b8e0dc8c99fbdf2c754e88dec0a8078a725/app/src/main/java/com/tabula/v3/MainActivity.kt#L659-L669)；[MainActivity.kt:710](https://github.com/ertutio/TabulaV3/blob/d2038b8e0dc8c99fbdf2c754e88dec0a8078a725/app/src/main/java/com/tabula/v3/MainActivity.kt#L710-L715)；[AlbumManager.kt:1121](https://github.com/ertutio/TabulaV3/blob/d2038b8e0dc8c99fbdf2c754e88dec0a8078a725/app/src/main/java/com/tabula/v3/data/repository/AlbumManager.kt#L1121-L1152)。

### F20 感知哈希按 ID 持久缓存，内容变化或可读性恢复后不重新计算

哈希与失败状态只以 imageId 为键，ensureHashesComputed 对任何已有条目均不重试；同 ID 编辑后的旧哈希、临时读取失败均可能持续影响相似分组。T03 必须盘点此消费者，T07 接入版本失效和可读性恢复后的有界重试。

关联：T03、T07。证据：[ImageHashCache.kt:70](https://github.com/ertutio/TabulaV3/blob/d2038b8e0dc8c99fbdf2c754e88dec0a8078a725/app/src/main/java/com/tabula/v3/data/cache/ImageHashCache.kt#L70-L83)；[ImageHashCache.kt:122](https://github.com/ertutio/TabulaV3/blob/d2038b8e0dc8c99fbdf2c754e88dec0a8078a725/app/src/main/java/com/tabula/v3/data/cache/ImageHashCache.kt#L122-L139)；[ImageHashCache.kt:176](https://github.com/ertutio/TabulaV3/blob/d2038b8e0dc8c99fbdf2c754e88dec0a8078a725/app/src/main/java/com/tabula/v3/data/cache/ImageHashCache.kt#L176-L202)；[SimilarGroupDetector.kt:221](https://github.com/ertutio/TabulaV3/blob/d2038b8e0dc8c99fbdf2c754e88dec0a8078a725/app/src/main/java/com/tabula/v3/data/repository/SimilarGroupDetector.kt#L221-L235)。

## 依赖和验证判断

T04最终接入依赖T02/T03；T07依赖T01安全结果、T02投影和T03身份。T01/T03共定契约，不要求迁移先于止损；T07与T04/T11a是接口与集成关系，不互设全量阻塞。T09只有实际失败证明阻塞时才最小前移；工具链变化后重校准受影响测量，未变证据复用。

每轮一个子任务和主要实现者。共享文件唯一写入，默认串行；同一任务内部仍完成必要端到端行为。后续只重读受影响路径，不重复全库分析。

## 本轮完成与限制

- 145文件一一映射、graph schema/引用/层/行号校验和145文件 fingerprints 通过；源完整性按codeload ZIP逐项比较。
- DeepSeek独立复核只覆盖F18–F20，主代理核对事实，F19保持条件性，未将报告的简化修复处方固化为设计。默认状态目录写受限后换工作区状态，沙箱DNS失败后按批准网络调用成功，过程保留。
- Luna基线任务完成；支持索引任务触及用量限制，由主代理补齐117支持文件。
- TC01–TC55仍为NOT_RUN规格，新增TC56–TC58亦未执行。下一轮只建议T01a，尚未实施。
- 原业务文件、项目文档和GitHub内容未修改；无提交/推送/发布/Hook，产品运行时请求增量为0。

## 实施时确认

1. 副本证明：卷/具体URI别名、源版本变化和实况附加字节，需夹具验证。
2. 身份迁移：歧义旧键保留可恢复，按消费者簇迁移。
3. 权限、真实进程终止及第三方照片授权：现有自有媒体探针不覆盖。
4. 工具链和依赖：对应批次核对真实解析与当前官方支持，旧候选不当作固定结论。
