# 持续开发进度与续接记录

本文件维护当前状态、队列和唯一 `next_action`。执行循环与升级条件见[总计划](DEVELOPMENT_ROADMAP.md)，T/TC 规格见[验收约定](../docs/acceptance.md)，启动文本见[智谱续接说明](ZHIPU_HANDOFF.md)。下方“历史交付登记”起均为历史，只供证据追溯。

## 当前游标：北京时间 2026-11-03

| 字段 | 当前值 |
| --- | --- |
| 总任务状态 | `in_progress`；[PR25](https://github.com/ertutio/TabulaV3/pull/25)、[PR26](https://github.com/ertutio/TabulaV3/pull/26)、[PR27](https://github.com/ertutio/TabulaV3/pull/27)、[PR30](https://github.com/ertutio/TabulaV3/pull/30)、[PR31](https://github.com/ertutio/TabulaV3/pull/31) 已按用户授权合并进 master（跨卷软删除全链+O5③ 图集删除/移动/复制身份消费+哈希/相似组按卷消歧+封面身份/内容版本收尾+C2/T04 差额 2001 项生产组合级分批断言，仅测试），O5④ 经盘点确认由 PR25 闭环。[PR32](https://github.com/ertutio/TabulaV3/pull/32)（C1/T03-DECKCOOLDOWN 冷却池身份键）已按用户指示于 2026-11-04 合并进 master；[PR33](https://github.com/ertutio/TabulaV3/pull/33)（C1/T03-DECKRECALL 回牌身份定位）已按用户指示于 2026-11-04 合并进 master。[PR34](https://github.com/ertutio/TabulaV3/pull/34)（C3/T06-VIEWERKEY deck 查看器缩略图键）已按用户指示于 2026-11-04 合并进 master（merge `fda3567d`，head `d014df0d`，合并前 head 检查三 job success）。[PR35](https://github.com/ertutio/TabulaV3/pull/35)（C3/T06 预加载生命周期盘点+时序回归，仅测试）已按用户指示于 2026-11-04 合并进 master（merge `a42fdfbf`，head `4d6d574e`，合并前核对 head 未移动、diff 仅测试文件、CI 41693468519 全绿）。其余约 12 个原 T 组仍有实现或组级验收差额。 |
| 计划 ref | [PR #12](https://github.com/ertutio/TabulaV3/pull/12)，`docs/continuous-development-2026-10-28`；本次更新前父版本 `6ed985e`。PR12 保持开放、不能整体合并；PR6/PR9 不在本次合并范围（未动）。 |
| 已合并基线 | **`master@a42fdfbf2d3551084ab2ce042959f0b3337d69de`**（2026-11-04 合并 PR35：merge `a42fdfbf`，head `4d6d574e` 为 master 祖先；合并前核对 head 未移动、diff 仅 PreloadingManagerTest.kt 单文件+49 测试、CI run 41693468519 三 job 全绿）。其下 **`master@fda3567d8d283e2f544c94591fb5a91390ed24ea`**（2026-11-04 合并 PR34：merge `fda3567d`，head `d014df0d` 为 master 祖先；合并前 head 检查三 job success）。其下 **`master@83dc48b182c3a5b90c34ba0602193e25a7c4f947`**（2026-11-04 合并 PR33：merge `83dc48b1`，head `5a527beb` 为新 master 祖先；合并前 base 已从父分支改指 master、差异恰为本轮 3 文件（DrawCardState.kt/CardStack.kt/新增 DrawCardRecallIdentityTest.kt）、MERGEABLE/CLEAN、head 提交三 check success）。其下 **`master@2e2d67e6e2cb474cdc9aa77765dfc5e9618a9b3a`**（2026-11-04 合并 PR32：merge `2e2d67e6`，head `0ee903a9` 为新 master 祖先；合并前核对 head 未移动、MERGEABLE/CLEAN、head 提交三 check success）。其下 **`master@b5137ca01b25e094a09c379e8fc03db46d138764`**（2026-11-02 合并 PR31：merge `b5137ca`，head `d2303d9`；合并后组合态 CI [run 41362213547](https://github.com/ertutio/TabulaV3/actions/runs/41362213547)（head b5137ca）三 job 全绿、双设备各 16 项零 skip）。其下 **`master@c6200684050a41fd2a21bb5ef5928d55eb8b0972`**（2026-11-02 合并 PR30：merge `c6200684`，head `1e53008`；合并后组合态 CI [run 41352188277](https://github.com/ertutio/TabulaV3/actions/runs/41352188277)（head c620068）三 job 全绿、双设备各 16 项零 skip）。其下 **`a91db5dacd28dcb404af5cdf6177c4d8c9fafc90`**（2026-11-02 合并 PR28/PR29：merge `921ee112`(#28, head 464cd23) → `a91db5da`(#29, head 8dcfc11)；组合态 CI run 41328257430（head a91db5d）三 job 全绿）。**`e6bd2cc062b95993479ba1f18afe2eae5741ed2d`**（2026-11-01 合并 #25/#26/#27：merge 顺序 `908a4eca`(#25, head 5c0645af) → `2c90b2b2`(#26, head 48f24404，base 已切回 master 进入 master) → `e6bd2cc0`(#27, head fad160a)；三个指定 head 均为 master 祖先）。此前基线 b455f45 上的已交付边界描述继续成立（#24 限制说明保留）。 |
| 当前依赖候选 | [PR32](https://github.com/ertutio/TabulaV3/pull/32) **已合并（2026-11-04 用户指示，merge `2e2d67e6`，分支保留供 PR33 base）**：复验轮二最终 head `0ee903a9`（24dbfdc 基线 FAIL×2 → 5450af9 预过滤候选集判定唯一性 → 0bf93b9 KDoc 划界 → 0ee903a off-deck 歧义保留），最终 CI [run 41374048554](https://github.com/ertutio/TabulaV3/actions/runs/41374048554)（head 0ee903a9）三 job 全绿、303 单测、PickCooldownIdentityTest 12/12、双设备各 16 项零 skip。**无待合并候选**（PR32/33/34/35 均已合并）。[PR33](https://github.com/ertutio/TabulaV3/pull/33) 已合并（2026-11-04 用户指示，merge `83dc48b1`）：head `5a527beb`（=`8e3369a` 基线 FAIL+`5a527be` 修复，独立 2 提交），合并前 base 由父分支改指 master（差异核对仅 3 文件、无夹带）、CI [run 41668707033](https://github.com/ertutio/TabulaV3/actions/runs/41668707033)（head 5a527beb）三 job 全绿、双设备各 16 项零 skip、单测实际执行非 FROM-CACHE。合并后组合态 CI 未重跑（master 合并前仅含同源提交，head 检查覆盖该代码组合）。PR12 保持开放仅作计划。 |
| 当前检查点 | **C2/T04 入口统一及 UNKNOWN 隔离：TC17–TC22 合成可测矩阵以证据关闭**（三入口均接同一 deleteInBatches 结果约定+确认集合无扩大，独立审计确认；2001 项组合级缺口已由 PR31 补齐）。剩余：真实系统授权/恢复路径、真跨卷、进程死亡维持既有 blocked 簇；回收站入口 2001 项组合断言列为可选后续候选。C1/T03 残余消费者盘点：封面 8 站点/组身份/哈希/网格键/删除恢复链均复用 PR25–30 证据关闭；Lazy key=UUID/月份键、URI 零裸拼、A2 死代码以证据关闭；冷却池已由 PR32 修复并合并 master；**deck 抽卡 DealtCard.cardId 裸 ID（回牌定位错行）已由 PR33 修复并合并 master（C1/T03-DECKRECALL 关闭）**。deck 查看器键错配（album_grid_ 裸 ID vs card_）已由 PR34 修复并合并 master（C3/T06-VIEWERKEY 关闭）；预加载生命周期盘点+防抖窗口时序回归已由 PR35 交付（仅测试，待评审合并）。维持登记：exitingRightCard 裸 ID 比较（回牌动画右卡淡出辅助，自愈非定位缺陷，PR33 审计登记）、deck 同 size 同 index 内容变化不触发重校验（两版共有局限）、A4 itemBounds 离场残留与 PR30 复审非阻塞四项维持登记；DeckScreen.kt:405 清理模式预取 uri 裸键无消费者（PR34 审计新登记，同形缺陷属清理簇边界，小提交候选）。 |
| next_action | **等待 PR36（含 P2 复审轮）人工评审（不自动合并）**；合并后继续最早 ready 项：C3/T06 余登记项（预加载生命周期余面、DRAW preloadNextCards 冗余连发清理候选、DeckScreen:405 清理预取裸键、R-2 模式判据小提交）或 C5/T08 工具 fixture。生产分支从最新 master `a42fdfbf` 建立（PR36 合并后从其 merge 提交建立）。 |
| 执行状态 / 所有权 | 本轮（C4/T07 前台权限刷新）：分支 fix/foreground-permission-refresh-2026-11-04 基于 master `a42fdfbf`，两提交 `ded70d9`（policy MediaAccessLevel 三态+hasMediaReadPermission 委托、MainActivity 级别跟踪+mediaAccessRevision 前台/级别变化触发+TabulaApp 键入既有加载链、设备套件 ForegroundPermissionUiTest 2 例+探针声明）→ `485b4cb`（独立审计阻断项修复：deck currentBatch 按 LaunchedEffect(allImages) 身份键剪枝+currentIndex 对齐+剪空走 needsInitialBatch，对齐 AlbumViewScreen 既有范式）。单测 7/7；全量 320 units 唯一失败=已知环境项；androidTest 编译过。独立只读审计（新会话）：首轮不可合并（1 必须修复：deck 可见批次不随刷新，与设备用例 2 硬冲突）→ 修复 → 原审计者复核：可合并、阻断项解除（剪枝数学三情形+PR33 交互验证正确；上溢边角建议钳制已在 cf0261a 落实）；5+6 条可登记（对话框后冗余二次加载/取消窗口 initialize 单例写入/unknown 回收站重复 reconcile+Toast/清理 fallback 未复用 content()/全量重查性能/PR35 措辞修正）。**未执行的真实系统场景**：①PARTIAL 真实选中集合 pm 不可夹具化（级别映射单测钉住）；②权限撤销路径探针不可执行（OS 强停杀 instrumentation，实证登记）；③设置 UI 手工路径以 pm 等价操作代替。**审计闭环**：首轮不可合并（deck 批次硬冲突）→ 485b4cb 修复 → 原审计者复核**可合并**（剪枝数学三情形+PR33 交互验证正确；上溢边角建议钳制）。**CI 证据链（含误读更正）**：run 41703684508（485b4cb1）设备双败——用例 2 startActivitySync 超时（测试基建）→ `cf0261a`（am start+轮询+审计建议的剪枝 currentIndex 钳制）；run 41706417934（cf0261a5）**外部删除回前台刷新用例双 API 实跑通过**（前台重载+deck 批次剪枝全链真实系统验证）；revoke 用例双 API 实证**不可经 in-process 探针执行**（Android 撤销存储权限强停应用进程，instrumentation 随宿主死亡；此前"用例 1 通过"系误读 run 1 流，已更正）→ `c26b04d` 移除 revoke 用例（KDoc 载明边界），探针声明 1 例。应用侧撤销路径 = 进程被杀后重开 → onCreate 级别检查 → 门控（既有路径+单测映射）；真实撤销手工验收维持 blocked 口径。run 41709101622（head c26b04d4）：套件设备日志实跑通过（OK (1 test)）但探针计数门误写复数（OK (1 tests)）误报失败 → `745b269`（tests? 可选复数，2/3/8 项套件不受影响）。**最终 head `745b269e78454d78140961b606d23721733c4b76`，CI [run 41711927202](https://github.com/ertutio/TabulaV3/actions/runs/41711927202)（head 745b269e）三 job 全绿：单测非 FROM-CACHE，双设备各 OK(3)+OK(8)+OK(3)+OK(2)+OK(1)=17 项零 skip（含新套件）**。
| U8 / PR36 P2 复审轮：前台刷新落地的存活行更新/清理同步/查看器校验（C4/T07 P2） | tested `c9d8ebb`（分支 fix/foreground-permission-refresh-2026-11-04；`4d528dc` → `c9d8ebb`）。复审确认三 P2：①存活行持旧对象（外部编辑后 dateModified/尺寸陈旧）→ syncRowsToSnapshot 按卷|ID 身份整体替换为快照最新行；②清理模式跳过同步 → 统一同步 currentCleanupBatch/preloadedNextBatch/selectedCleanupMonth.images（groupIds 完成语义不变、边界按组重算、整组失效空段；整批失效走既有 handleCleanupBatchCompletion 完成通道，不退出清理不重置进度；预取批失效置 null 重取）；③查看器不校验 → viewerState.image 按快照校验（失效关/存活 copy 保查看位置）。**审计两轮**：首轮不可合并（C-1 必须修复：清理模式 deck 空间/清理空间索引混算，用户删除提交即触发视图回跳/已删卡片复现）→ relocatedIndexAfterSync（以 deck 当前行身份为锚重定位，两空间分列，普通/清理统一）→ 复核**可合并**（C-1/C-2/C-3 闭合；登记 R-1 对照池超集化、R-2 currentCleanupBatch!=null 耦合建议加判据、pool 全空提前 return、查看器高清层像素陈旧既有、同提交共改流程注记）。**回归测试**：ForegroundRefreshSyncTest 9/9（版本更新/失效剔除/跨卷同 ID 双向隔离/清理边界空段组定位/同实例短路/锚定重定位三情形含删 A 留 B 场景）；全量 329 units 唯一失败=已知环境项；androidTest 编译过。**接线层（DeckScreen effect）无单测基建——由两轮审计独立推演覆盖**；实机权限覆盖未完成项维持登记。**CI [run 41721541786](https://github.com/ertutio/TabulaV3/actions/runs/41721541786)（head c9d8ebbd）结果见 PR/该 run**。回退：按提交逐个 revert（4d528dc→c9d8ebb）。 |
| U9 / PR36 P2 复审轮二：有效空快照 + 引擎消费入口校验（C4/T07 P2 二） | tested `18d6325`（同分支；单提交）。**P2-a 有效空快照**：同步 effect 原在 pool.isEmpty() 直接返回（混淆未加载/加载后为空）→ 改为 `pool.isEmpty() && isLoading` 才跳过——加载完成后的空快照各面按空池同步（查看器关闭/普通批次清空重置 needsInitialBatch/清理批整批失效走完成通道 → COMPLETED+清断点，completionHandled 防重）。**P2-b 引擎消费入口**：getNextBatch 批次构建前按调用方快照逐组校验（存活行替换为最新 ImageFile、整组失效 markGroupsProcessed 后跳过——剩余计数如实；**组对象保持原样**——SimilarGroup.id 为成员身份键摘要，剪枝 copy 漂移完成标记对账）；getCheckpointBatch 任一行缺失 → 既有"组成变化放弃断点"语义清断点返回 null，存活行替换最新对象（行数/边界/索引不变）。**回归**：新增 AlbumCleanupForegroundRefreshTest **6/6**（真实引擎+真实 prefs，种子经 analyzeAlbum 同组写入口→先走真实 restore 路径再走 in-memory 陈旧缓存路径；三顺序批次后台删除/编辑、整组失效标记、断点编辑恢复同对象、断点删除放弃+重开干净、跨卷同 ID 不顶替、空快照标记完成；组尺寸互异 12/11/10——prefs 组目录 StringSet 无序）；ForegroundRefreshSyncTest 9/9；全量 **335 units** 唯一失败=已知环境项；androidTest 编译过。**独立只读审计（新会话全程只读）：可合并，无必须修复项**；登记 6 条（C1 空快照+活跃清理批永久标记全部剩余组、瞬时空结果需重新整理恢复无数据删除；C2 COMPLETED 态重复触发幂等；C3 restore 成员丢失组 id 漂移对账缺口（既有，本轮仅在交付层避免新增漂移）；C4 组内部分删除后计数偏大（既有）；C5 isLoading 未区分加载失败（异常路径空跑无副作用）；C6 markGroupsProcessed 在 cacheMutex 外的并发丢标记理论窗口（下次取批自愈））。**CI [run 41733888921](https://github.com/ertutio/TabulaV3/actions/runs/41733888921)（head 18d63258）结果见 PR/该 run**。回退：revert 18d6325。 |上轮（PR35 收尾合并）：核对 head 未移动/diff 单文件/CI 全绿 → merge `a42fdfbf` → 本登记（保留 Thread.sleep 可靠性限制，T06 不标完成）。C4/T07 开发前段：分支 fix/preload-lifecycle-2026-11-04 基于 master `fda3567d`，单提交 `4d6d574`（仅测试 +49：pause/reset/dispose×防抖窗口 3 时序回归——窗口内操作后 0 入队、reset 可复用/dispose 永久停用区分；amend 按审计把机制措辞降级为行为语义）。盘点证据：四焦点中回调/状态/重试/位图所有权面由既有 16+5 项钉死，两模式 recycle 站点与接线由审计独立复核。门禁 19/19+5/5；全量 317 units 唯一失败=已知环境项（--no-build-cache）。独立只读审计：可合并、无 FAIL、6 条 CONCERN 全部可登记（措辞已改；preloadNextCards 冗余连发/焦点④调用方半边无测试钉/Thread.sleep 理论假红/scope 间接断言/reset·preloadImmediate 无生产调用者）。上轮（C3/T06-VIEWERKEY）：分支 fix/deck-viewer-thumb-key-2026-11-04 基于 master `83dc48b1`，两提交 `11199f9`（基线：verbatim 提取 deckViewerThumbnailRequest+基线 FAIL——错配纯内容差异 album_grid_5 vs card_、跨卷同键、内容陈旧、spec 策略；控制组绿）→ `d014df0`（修复：键→cardMemoryCacheKey 借用+内存 READ_ONLY+spec 测试补齐；amend 修正测试注释中 diskCacheKey 类型描述）。本地同门禁 DeckViewerThumbnailKeyTest 5/5+相邻 ThumbnailCacheKeyTest 3/3+CoverImageRequestTest 3/3；全量 314 units 唯一失败=已知环境项（--no-build-cache）。独立只读审计（未参与实现新会话，含 coil-base-2.7.0 javap 与 Coil 源码核对）：可合并、A–F 全 PASS、0 FAIL、登记 4 条非阻塞。上轮（PR32 收尾）：核对 head 未移动 → 更新 PR 正文 → merge `2e2d67e6` → 登记。本轮（PR33 收尾合并）：核对 head 未移动（5a527beb）→ base 由父分支改指 master（差异核对仅 3 文件无夹带、CLEAN）→ 三 check success（run 41668707033 绑定该 head）→ merge commit 合并（`83dc48b1`，分支保留）→ 本登记。未重跑组合态 CI（head 检查即组合态）；未开始下一轮代码开发、未发布、未接触签名。上轮：PR32（C1/T03-DECKCOOLDOWN）最终 head 0ee903a、CI 41374048554 绿。本轮（C1/T03-DECKRECALL）：PR33 两提交 `8e3369a`（基线：内联重定位 verbatim 提取为 revalidateDealtStack+基线 FAIL——跨卷同 ID 回牌 expected external_secondary but was external_primary+控制组绿）→ `5a527be`（修复：DealtCard 增 cardVolumeName 发牌捕获，重校验按 (卷,ID) 复用 resolveCoverRow 三态协议，缺失/歧义按缺失丢弃不回退裸 ID；纯内存态无迁移）。本地同门禁 DrawCardRecallIdentityTest 6/6；全量 309 units 唯一失败=已知环境项（--no-build-cache）。独立只读审计（未参与实现新会话）：可合并、A–F 全 PASS、0 blockers、登记 4 条非阻塞。恢复时核对实际 refs、未提交文件、写者及运行中 CI；本快照不代替现场检查。 |

## 分支和依赖处理

1. 启动先核对实际 master、PR25 head/合并状态和计划 ref。若已合并且包含上述候选，生产分支从最新 master 建立。
2. 若 PR25 仍未合并，O5③依赖它：核对父 PR、精确 SHA 与独立审计/验证是否仍适用；依路线图使用明确以 PR25 为 base 的堆叠分支和 PR，记录祖先 master 与父 SHA，只新增本单元改动。保持父 PR 冻结，交付中标清依赖。
3. 若父候选变化、审计问题未清或无法管理堆叠，暂停此依赖，转 C3/T06 或 C5/T08 的独立就绪项并登记恢复条件。其他独立单元从最新 master 建分支。
4. PR12 仅提供文档；保持开放。PR25 及后续实现交人工评审合并，执行者不自动合并或直接推送默认分支。

## 已交付边界与精确证据：复用，不重做

| 边界 | 当前证据与局限 |
| --- | --- |
| PR23 / T01、T05、T11a、O2 | 已合入。tested `dff9898ba3518df3658788b39b627b61c4df369f`，[run 41169544885](https://github.com/ertutio/TabulaV3/actions/runs/41169544885)：228 Release units；API36/37 各 Volume 3 + Cleanup 8 + Restore 3 + CreateAlbum 2 = 16，零 skip。包含真实归档失败完整 Toast/源与目标不变式、详情错误/重试与受控旧请求晚到、同失败输入未取消对照、API37 dotless 创建事务。证据只覆盖各自版本和断言。 |
| PR24 | 已合入 master，独立复审未见引入阻塞；CI [41202877201](https://github.com/ertutio/TabulaV3/actions/runs/41202877201) 对精确 `a60a3d04` 通过。未使用 helper 的一致性修正，不外推为同 ID 跨卷消费已完成。 |
| PR25 / UNKNOWN 隔离修复 | 最终 tested `5c0645af5d5ed9f24555f5c8f1e14b23db2eeeb8`，父 `cf7e15ecb36ca02770941bf48f8c94eeb708f1ed`。MainActivity 实际调用共享 `deleteRecycleBinSelection`：恢复核验仍 UNKNOWN 的项不进新请求、保留记录与 journal，所选 UNKNOWN 计 remaining，无关项继续，未选历史 UNKNOWN 不计本次 remaining。manager/batch 协议未改。 |
| PR25 / 定点 FAIL → PASS | 编译成立的有效基线：RecycleBinDeletionTest 14 项，4 FAIL / 10 PASS；修复后 14/14 PASS（5 新回归、9 既有）。新增回归覆盖混选成功、服务错误、权限取消、未选 UNKNOWN、重建与后续 existing/deleted。共享生产协调函数 + 内存存储模拟重建，不是 Activity/Compose E2E 或进程死亡测试。 |
| PR25 / 最终 Linux CI | [run 41274727140](https://github.com/ertutio/TabulaV3/actions/runs/41274727140) 对上述最终 SHA build/API36/API37 全成功；41 JUnit XML、244 units、0 failure/error/skip，RecycleBinDeletionTest 14/14、RecycleBinManagerTest 10/10。Windows `atomicReadRestoresLegacyBackup` 失败已由同源 Linux PASS 定位为本地 AtomicFile rename 夹具差异，未修改/跳过测试；不再列为活动产品失败。 |
| PR25 / 设备范围 | API36 与 API37 各 VolumeDeletionDeviceTest 3/3，逐例成功、无 skip；自有合成 JPEG + 真实 MediaStore/journal 对账。两台 external_primary mounted，actuallyUnmounted=[]；**没有实际卸载卷**，不外推为真跨卷热拔插验收。该 run 的逐例核验只确认每 API 这 3 项，不沿用 PR23 的 16 项作为本次执行数量。 |
| PR25 / 独立审计与卡点记录 | 未参与实现的原独立审计者对最终冻结补丁未发现新增阻塞。旧 cf7 “全绿/可合并”仍存在实际入口重发 UNKNOWN、失败/取消可清旧 journal 的缺陷，其结论已失效。该隔离目标曾有 cf7 一次针对性修正未达条件（源码审查），本次唯一局部修正已有效通过；卡点已解决。环境锁/临时目录失败与预期 baseline FAIL 单列，未计产品修正失败。新单元的新问题另立条件，未解决的旧卡点仍跨会话累计。 |
| O5③ / PR26 图集批量删除身份消费 | tested `49fc1adab159aec80a489e86ea38e5f6c6a1d62b`（父 `5c0645af`，祖先 master `b455f45`）。有效基线 FAIL（cc75e66）：`SnapshotCrossVolumeSoftDeleteTest.albumBatchDeleteResolvesOnlyConfirmedIdentityRows` →「只解析确认选中（主卷）的那一行 expected:<1> but was:<2>」（旧口径按数值 ID 解析，另一卷同 ID 行进入删除请求）；修复后同门禁 11/11。全量 Windows 本地 `:app:testReleaseUnitTest` 245 units 唯一失败 = 已知 `atomicReadRestoresLegacyBackup`（AtomicFile 环境差异，CI 绿）；`compileDebugAndroidTestKotlin` 通过。**CI [run 41277892304](https://github.com/ertutio/TabulaV3/actions/runs/41277892304)**（head 49fc1ad）三 job 全绿，双设备各 Volume 3 + Cleanup 8 + Restore 3 + CreateAlbum 2 = 16 项零 skip。改动面：AlbumViewScreen 选择集/网格 key/边界注册/回写剪裁与移动复制解析、MainActivity onDeleteImages 消费、SwipeableViewerOverlay Pager key、LocalImageRepository 新增 `resolveImagesByIdentitySelection`。独立只读复审（未参与实现新会话）：**可合并，A–E 全 PASS**，非阻塞建议 4 条已转入后续候选。**复验轮（用户审计，2026-11-01）**：上述验收含错误结论——移动/复制被划为"T03 后续簇"但选择集已是身份键，`AlbumViewScreen.map{it.id}.distinct()` + `AlbumManager.find{id==}` 在跨卷同 ID 时**只选 B 操作 A、双选折叠成一张**（P1，消费端身份再次丢失）。复验轮：`588b6ee` 基线 FAIL×6（移动/复制 × 只 B/双选/失效回退，真实 AlbumManager+受控跨卷 Provider，复制断言目标磁盘字节 `expected:<content-B> but was:<content-A>`，移动/复制走真实 update 与 insert+流+SHA-256 全链）→ `48f2440` 修复：新增 `moveImagesByIdentity/copyImagesByIdentity`（另名避免 List 擦除冲突；执行前按完整身份从当前媒体集合重解析，失效选择跳过计 retained 不回退，逐行卷限定 URI；旧 Long 接口保留），选择→确认→MainActivity→AlbumManager 全链身份键。**复验 CI [run 41292141083](https://github.com/ertutio/TabulaV3/actions/runs/41292141083)**（head 48f2440）三 job 全绿、双设备 16+16 零 skip；本地全量 254 units 唯一失败=已知环境项；独立复审（新会话）**可合并，A–E PASS，无必须修复项**（非阻塞：失败分支用例、重复键口径注释）。按名合并语义不变；封面/组 ID/冷却/专清不扩展。回退：按提交逐个 revert（cc75e66…48f2440）。 |
| O5④ 恢复回填 / 盘点闭环（不另立单元） | 现场（49fc1ad）核对：MainActivity 恢复路径三处（内存态移除/回插门槛/待整理回插门槛）均已用 `matchesSameMediaIdentity`，纯 ID 版 `removedIds` 已不存在——原 O5④ 缺陷已由 PR25 消除。跨卷判重既有回归钉死（`recycleBinKeepsCrossVolumeSameIdRecordsIndependently`“恢复一条只移除对应记录”）；未知卷兼容由退化语义+`legacyNullVolumeRecordStillFiltersById`+f6a4e81 恢复候选分区覆盖；重复恢复幂等由同款身份门槛保护（二次恢复 `none` 检查跳过回插）；进程死亡/重建与真实设备场景仍属既有 blocked 簇（C0 表），不因本盘点关闭或新增。按队列指示登记并继续，不重复造测试。 |
| B 簇 / PR27 哈希缓存与相似组按卷消歧（C1/T03-HASHVOLUME） | tested `f8ac08e`（独立基于 master `b455f45`，不依赖 #25/#26）。基线 FAIL（ccdd3e2）：`ImageHashCacheVersionTest` 8 项中 2 失败——`crossVolumeSameIdEntriesStayIndependent`（主卷条目被另一卷同 ID 保存覆盖+版本键互踢）、`volumeQualifiedEntriesSurviveCleanupWithSameIdInValidSet`（裸 ID 清理无法按卷判定）；修复后 8/8。新 `SimilarGroupDetectorVolumeIdentityTest`（真实渐变 JPEG 双卷同 ID）：独立计算/独立版本读取/按卷清理。全量 Windows 本地 232 units 唯一失败=已知 AtomicFile 环境项（base 同源失败）；`compileDebugAndroidTestKotlin` 通过。**CI [run 41281426693](https://github.com/ertutio/TabulaV3/actions/runs/41281426693)**（head f8ac08e）三 job 全绿，双设备各 Volume 3 + Cleanup 8 + Restore 3 + CreateAlbum 2 = 16 项零 skip（41280204616 为 84cb7d0 中间段证据）。改动面：ImageHashCache（HashKey 身份键+旧 Long API 移除+toHashKey）、SimilarGroupDetector（ensureHashesComputed/检测链全键化）、AlbumCleanupEngine（孤立图判定身份化）。独立复审（新会话，含临时 clone 实测基线红/修复绿）：**可合并**，A 项 KDoc 失实 CONCERN 已以 f8ac08e 闭环，B–E PASS。非阻塞建议①（组恢复/持久化裸 ID 同族残留）转入下一单元候选；②（queryImagesWithBundle 无卷字段）登记。**复验轮（用户审计，2026-11-01）**："排序列表保留重复条目故无伪命中"论证被证伪——条目保留数无法区分成员所属卷：A 卷照片被 B 卷同 (id,dateModified,size) 照片替换时指纹相等，引擎返回含 A 卷旧 URI 的缓存组绕过 HashKey 检测链（P2）。复验轮：`c9b7684` 基线 FAIL×3（缓存级 `volumeSwapWithSameMetadataMisses`、`versionSizeOwnershipSwapBetweenSameIdRowsMisses` + 真实引擎回归 `volumeSwapWithSameMetadataDoesNotServeStaleGroup`——A10+A11 成组后换同三元组、内容不相似的 B10+A11，不手动清缓存仍返回含 A10 旧 URI 的组）→ `fad160a` 成员指纹改 `(volumeName,id,dateModified,size)`（保留内容版本/大小/重复成员/顺序无关/TTL；null 卷与具名卷不等）。**复验 CI [run 41292765803](https://github.com/ertutio/TabulaV3/actions/runs/41292765803)**（head fad160a）三 job 全绿、双设备 16+16 零 skip；本地全量 236 units 唯一失败=已知环境项；独立复审（新会话）**可合并，A–E PASS，无必须修复项**。组 ID/冷却/专清持久化按用户指示**不扩展**，仍为后续候选。回退：按提交逐个 revert（ccdd3e2…fad160a）。**已合并（2026-11-01 用户授权，merge `e6bd2cc0`）。** |
| U1 / PR28 相似组身份与专清持久化/恢复闭环（C1/T03-GROUPID-CLEANUP） | tested `5a33084`（独立基于 master `e6bd2cc`）。基线 FAIL（bb4de30）8 项：重排组身份漂移、跨卷同 ID 两组共用身份→冷却连带/永久标记连带（批次空）、重建引擎恢复折叠（`expected 4 uris but was 3`，P10 丢失 S10 串入）、旧跨卷歧义被猜选、旧唯一标记无迁移、幂等连锁；3 控制组 PASS（旧单卷恢复/缺员丢弃/歧义标记保留）。**回退验证**：最终测试对修复前生产（仅 seed 类型适配）8 FAIL/3 PASS 红因一一对应。修复（793dc7a）：`similarGroupId`=排序成员身份键集 SHA-256 摘要（重排稳定/跨卷可区分）、成员持久化身份键、恢复身份解析三态（新键精确/旧裸 ID 唯一解析/缺失丢弃/歧义 return null 重算）、旧永久标记唯一映射迁移+歧义保留、checkpoint 旧 ID 别名。复审 CONCERN 闭环（a9cb663）：getCheckpointBatch 同口径迁移+按身份 ID 比对+新回归 `checkpointWithMigratedMarkerDoesNotResurrectProcessedGroup`。全量 273 units 唯一失败=已知环境项；androidTest 编译通过。**CI [run 41303051903](https://github.com/ertutio/TabulaV3/actions/runs/41303051903)**（head 5a33084）三 job 全绿、双设备各 16 项零 skip。独立复审（新会话，含临时 worktree 实测绿 11/11+红 8/3）：**可合并，无必须修复项**（非阻塞：别名 last-wins 已注释、saveGroupImageIds 单组 Long 死代码、SimilarGroup.id 重算可缓存——登记后续）。夹具关键发现：Robolectric 位图解码对任意字节都 Success(0)（探针实测），必须真实渐变 JPEG+流注册。回退：按提交逐个 revert（bb4de30…5a33084）。**复验轮二（用户验收审计，2026-11-01，审计 head 5a33084）**两项问题修复：①旧 checkpoint 歧义猜选组——getCheckpointBatch 旧别名仅唯一候选才解析（last-wins 移除），多候选明确放弃续接（仅清断点+null 安全重算，上层已核走重新分析；不清永久意图/不自动标记/不跳照片）；②迁移口径分裂（只改 processed_，groups_/group_size_/group_images_ 残留旧 ID→已处理数 1→0）——迁移覆盖全部组 ID 主键记录（processed_/groups_/group_images_ 成员值改写身份键/group_size_/checkpoint 引用保序保 index），歧义保留；崩溃自愈顺序（复审建议闭环）：先改名成员/数量最后改写 groups_（旧条目充当触发器，rename 缺源键无操作幂等）。基线 FAIL（470d569）×2 → 修复（59931eb）→ 顺序闭环（20e56de）。独立复审（新会话，双向实测红 2/绿 14）：**可合并，无必须修复项**。**CI [run 41314238368](https://github.com/ertutio/TabulaV3/actions/runs/41314238368)**（head 20e56de）三 job 全绿、双设备各 16 项零 skip；全量 275 units 唯一失败=已知环境项。PR28 最终 head ~~20e56de~~ → **`464cd23`**。回退：按提交逐个 revert（470d569…464cd23）。**复验轮三（用户验收审计，断点位置有效性，2026-11-02）**：审计场景（A=[a1,a2]、B=[b1,b2]，断点 [A,B] index=1，删除 a2 后重进清理）经回归测试**真实执行确认**（红基线 batch=[s10,s12] index=1——b1 跳过、B 整组提前标记；同族：保存后内容编辑使组内排序移动同样错位）。修复（97b55e3，最小方案）：getCheckpointBatch 不再盲目 coerce 旧索引——两道放弃检查（①批次组成变化：任一保存组未能原样进入批次；②断点保存后分析重跑 analyzed_>checkpoint_time_，组内排序可能移动）→ 仅清断点+null 安全重开，回退路径已核（孤儿 a1 经孤儿组进批次不遗漏；不提前永久标记）。复审 CONCERN 闭环（464cd23）：迁移改写断点引用保留原 checkpoint_time_（新 replaceAlbumCleanupCheckpoint）——否则旧格式首迁移入口绕过时序判据。**CI [run 41322746035](https://github.com/ertutio/TabulaV3/actions/runs/41322746035)**（head 464cd23）三 job 全绿、双设备各 16 项零 skip；全量 277 units 唯一失败=已知环境项。独立复审（新会话，临时 worktree 实测红 2/绿 16+全量复核）：**可合并，无必须修复项**（A/B/D/E PASS）。回退：按提交逐个 revert（470d569…464cd23）。 |
| U2 / PR29 系统图集网格 key 与缩略图缓存键身份收尾（C1/T03-THUMBKEY） | tested `8dcfc11`（独立基于 master `e6bd2cc`）。基线 FAIL（b9289b0）2 项：`crossVolumeSameIdRowsHaveDistinctKeys`（跨卷同 ID 缩略图键相同→串图）、`contentEditChangesKey`（内容编辑键不变→陈旧缩略图）；1 控制组 PASS。修复（7a77052）：`gridThumbnailCacheKey`=前缀+mediaIdentityKey+dateModified 毫秒（两处私有 PhotoGridItem 接线）；SystemAlbumViewScreen 网格 items key 改 mediaIdentityKey（重复 key 崩溃）。复审 B 项 CONCERN 闭环（8dcfc11）：SwipeableViewerOverlay 查看器键统一到 `grid_` 命名空间（恢复网格→查看器温命中设计意图、关闭查看器自身跨卷串图）。**范围声明（如实）**：键函数单测钉死两处 PhotoGridItem 实际键源；Compose 重组/槽位复用/"无重复 key 崩溃"无测试基建，仅审读核验，不宣称界面级证明。划界核对：deck 卡片预加载键 `card_uri_dateModified` 已卷限定+版本感知（无需动）；封面簇（CategorizedAlbumsView/AlbumSelectionSheet/AlbumTagManagementScreen 裸 coverImageId）与 deck ViewerOverlay 读 album_grid_ 而 deck 写 card_ 的既有错配——登记后续不扩大。全量 264 units 唯一失败=已知环境项；androidTest 编译通过。**CI [run 41307027175](https://github.com/ertutio/TabulaV3/actions/runs/41307027175)**（head 8dcfc11）三 job 全绿、双设备各 16 项零 skip。独立复审（新会话）：**可合并，无必须修复项**。环境坑（已记忆）：切分支后 Gradle build cache 会还原已删除源的测试类（幽灵类），全量门禁需 `--no-build-cache`。回退：按提交逐个 revert（b9289b0…8dcfc11）。 |
| U1+U2 复验 / PR30 复验轮（用户验收审计，2026-11-02） | tested `dc601e9`（PR30 追加 5b92370 基线 FAIL + dc601e9 修复）。三项验收修复：①updateAlbum 保留既有元数据封面（属性编辑不冻结自动封面——基线 FAIL 实测 `expected 11 but was 10`）+ 新 `setAlbumCover(albumName, image)` 显式通道（写完整身份、保留其余属性，刷新/重启保持）；②ATMS tag_cover_/exclude_cover_ 与专清 album/month 四站点接 resolveCoverRow（allImages 自 MainActivity 接线；卷已知精确/旧口径唯一/歧义→Emoji 占位不猜卷；imageItemUri(null,id) 聚合向量移除）；③四封面键改 gridThumbnailCacheKey（身份+DATE_MODIFIED）。附带声明：updateAlbum 同时保留 isHidden/isExcludedFromRecommend（旧版每次属性编辑重置 false 的既有数据丢失缺陷修正，方向安全）。CI [run 41342442479](https://github.com/ertutio/TabulaV3/actions/runs/41342442479)（head dc601e9）三 job 全绿、双设备各 16 项零 skip；全量 287 units 唯一失败=已知环境项。独立复审（第三轮，新上下文）：**可合并，无必须修复项**（回退必红代码路径验证；非阻塞：用户封面幸存/隐藏态幸存两条回归、rename name-keyed 迁移、fromCursor 死工厂退役——均已登记）。**复验轮三（封面内容版本失效收尾，2026-11-02）**：审计点 OtherAlbumsScreen AlbumRow 与 CAV SystemBucketRow 请求仅 URI 构造无身份+版本键（内容编辑后陈旧）——提取共享生产构造器 `coverImageRequest`（键=gridThumbnailCacheKey 身份+DATE_MODIFIED，规格参数化保留各站点原视觉）接线两审计点及 Deck/AVS dialog/Draggable 共五站点；基线 FAIL（7d26310，CoverImageRequestTest 直接构造生产请求：同 URI 不同 dateModified 键不变/跨卷不隔离——执行确认红，两键均 null）→ 修复后 3/3；四版本键站点（tag/exclude/cleanup/month）补 diskCacheKey（复审 CONCERN 闭环）；c54452f 提交信息失实（误称三站点已接线）由 c94b48f 显式更正。**CI [run 41350120490](https://github.com/ertutio/TabulaV3/actions/runs/41350120490)**（head 1e53008）三 job 全绿、双设备各 16 项零 skip；全量 290 units 唯一失败=已知环境项。回退：按提交逐个 revert（7d26310…1e53008）。 |
| U3 / PR31 C2/T04 差额——2001 项生产组合级分批断言（仅测试，无生产改动） | tested `d2303d920949bb5a2657a85ea9b2d649521c4dcd`（分支 fix/c2-t04-delete-gap-2026-11-02，基于 master `c620068`；提交 `d758f36` 新增测试 → `d2303d9` 审计 P3-1 注释闭环）。**无 baseline FAIL**：盘点结论为生产行为已正确、仅缺测试断言，按"补断言并如实登记，不人为制造修复"执行。缺口判定：三入口（详情/清理/回收站）已接同一 deleteInBatches 结果约定且确认集合无扩大，焦点断言（部分成功/取消不发后续批/授权拒绝/错误分类/仅无效可拆分/UNKNOWN 隔离/日志失败重启不重复删）均有既有有效证据；唯一实缺口=2001 项缺经 deleteInBatches+deleteMediaUrisBatch 生产组合的直接断言。新增 `twoThousandOneItemsSplitThroughProductionAdapterKeepingPartialSuccess`：21 请求（20×100+1）顺序无丢失重复、逐批 NeedsPermission 授权+核验、按批增量交付 20 次合计前 2000 项、末批 SERVICE 停止、2000 项保留 remaining=1 error 如实反馈；镜像 performDeleteBatch/performDeleteUris 实际组合（fake 仅落在 request/permission/verify 三生产接缝）。本地 Windows：受影响类 11/11（改动前后各一次）+ 全量 291 units 唯一失败=已知环境项（--no-build-cache）。**最终 SHA CI [run 41359827937](https://github.com/ertutio/TabulaV3/actions/runs/41359827937)（head d2303d9）三 job 全绿**：build 的 testReleaseUnitTest 实际执行非 FROM-CACHE，API36/API37 各 OK(3)+OK(8)+OK(3)+OK(2)=16 项零 skip、无 assumption。独立只读审计（未参与实现新会话，全程只读）：**可合并，0 blockers**；P3-1 注释表述超范围已由 d2303d9 闭环，P3-2（kind→canSplit 误分类对 1 项批不敏感）属范围事实、套件层已钉死，登记不修。回退：按提交逐个 revert（d758f36…d2303d9），测试文件 revert 即完全回退。遗留登记：真跨卷/其他应用媒体/进程死亡维持既有 blocked；MainActivity Compose 级接线维持审读口径；回收站入口 2001 项组合断言可选后续候选。**已合并（2026-11-02 用户授权，merge `b5137ca`）。** |
| U4 / PR32 残余消费者盘点 + 冷却池身份键修复（C1/T03-DECKCOOLDOWN） | **已合并（2026-11-04 用户指示，merge `2e2d67e6`，最终 head `0ee903a9` = master 祖先；最终 CI run 41374048554 三 job 全绿、303 单测、双设备各 16 项零 skip；复验轮二 24dbfdc→5450af9→0bf93b9→0ee903a 见 PR 正文）。** tested `802874a96b48987ff3d70ea1e8ba02c229f118c3`（分支 fix/c1-t03-residual-consumers-2026-11-02，基于 master `b5137ca`；`f2af0e7` 基线红 → `802874a` 修复绿）。**盘点**：沿实际可达入口全库扫描裸 ID 查找/去重/集合键/持久键/URI 构造——封面 8 站点 resolveCoverRow、组身份 PR28、哈希 PR27、网格/缩略图键 PR29/30、删除恢复链 PR25/26 均复用证据关闭；Lazy key=UUID/月份键、URI 零裸拼、A2 死代码（不可达）证据关闭；**冷却池为唯一未盘点真实缺陷簇**（裸数值 ID 键，不在按名保留决定内）。**有效基线 FAIL**（f2af0e7，现状代码 2 FAIL/2 PASS）：①卷 A 已看 42 进冷却→卷 B 同 ID 未看照片被抑制（批次=[c,a] 确定推演+实测红）；②未看回填删裸键→他卷已看记录被清。**修复**（802874a）：AppPreferences 身份键写/readIndex 双视图/migrate 单事务幂等（身份键已存在仅清裸键）；RecommendationEngine 批前迁移——唯一可解析→迁移（迁完零写入）、歧义→保留裸键该 ID 各卷行均抑制（过抑制方向安全，不猜卷不丢记录）、缺失→不动；去重/补充排序按身份键；DeckScreen/MainActivity 未看回填传行。退役 4 个零调用者方法（grep 证实）；cleanupExpiredPickRecords 两格式通用未改。**同门禁 PASS**：PickCooldownIdentityTest 7/7（新增唯一迁移幂等/歧义保留/缺失不动/身份键落盘）+ RecommendationEngineContentInvalidationTest 3/3；全量 298 units 唯一失败=已知环境项（--no-build-cache）。**最终 SHA CI [run 41365153032](https://github.com/ertutio/TabulaV3/actions/runs/41365153032)（head 802874a）三 job 全绿**、双设备各 16 项零 skip、testReleaseUnitTest 实际执行。独立只读审计（未参与实现新会话，缺陷真实性独立推演+修复 8 项+测试区分力+范围纪律）：**可合并，0 blockers**；P3×2 登记不修（迁移期重扫性能候选；removeFromCooldown 不触发迁移=已文档化降级）。回退：按提交逐个 revert（f2af0e7…802874a）。遗留：deck 抽卡 DealtCard.cardId 裸 ID 登记（随 deck/T06）；迁移真实 kill 模拟属既有设备 blocked 口径；真跨卷设备维持 blocked。 |
| U5 / PR33 回牌定位按完整媒体身份（C1/T03-DECKRECALL） | **已合并（2026-11-04 用户指示，merge `83dc48b1`，head `5a527beb` = master 祖先；合并前 base 改指 master、差异核对仅 3 文件无夹带；适用验证=最终 CI run 41668707033 三 job 全绿+同门禁 6/6+全量 309 units 唯一已知环境项失败；剩余缺口=审计登记 4 条非阻塞+真实多卷设备/进程死亡既有 blocked 簇+Compose 动画层无单测基建，见下行）。** tested `5a527beb70c5dcf0e8171eb9b609ffb0cdcea1f0`（分支 fix/deck-recall-identity-2026-11-03，建立时堆叠于 PR32 分支 head `0ee903a`（已随 PR32 合并进 master），base=父分支 `fix/c1-t03-residual-consumers-2026-11-02`（合并前已改指 master）；`8e3369a` 基线红 → `5a527be` 修复绿；独立于 PR32 仅 2 提交）。**根因**：DealtCard.cardId 为裸 ImageFile.id，deck 变化（删图移位）时 DrawModeCardStack LaunchedEffect 按裸 ID indexOfFirst 重定位 dealtStack——多卷 deck（MediaStore _id 仅卷内唯一）不同卷同数值 ID 时回牌（previewReturningCard/recallReturningCard→images[originalIndex]）飞回另一卷同 ID 照片；DRAW 模式生产可达（Settings/Personalization 可选）。**有效基线 FAIL**（8e3369a：内联重定位 verbatim 提取为纯函数 revalidateDealtStack 行为保持，保证基线可编译可执行）：recallRelocatesCrossVolumeSameIdCardsByFullIdentity → expected:<content://media/external_secondary/images/media/5> but was:<content://media/external_primary/images/media/5>；控制组 singleVolumeRecallKeepsPositionsAfterDeckShrink 绿。**修复**（5a527be）：DealtCard 增 cardVolumeName（reducer Draw 从 deck 行捕获，纯内存 Compose 态无持久化无迁移）；重校验按 (卷,ID) 复用已有 resolveCoverRow 三态协议（卷已知精确匹配/卷未知唯一候选才解析/跨卷歧义不猜选，与 C1/T03-COVER 同款，不另造身份协议）；缺失/歧义按缺失丢弃不回退裸 ID；LaunchedEffect 键/进入条件/动画清除与旧实现一致，回牌顺序/动画/单卷行为不变。改动面：DrawCardState.kt、CardStack.kt（内联块→函数调用+注释）、新增 DrawCardRecallIdentityTest。**同门禁 PASS**：DrawCardRecallIdentityTest 6/6（跨卷同 ID 精确定位/单卷控制组/发牌卷捕获/缺失不误定位/旧口径唯一解析/旧口径歧义丢弃）0 skip；全量 Windows `:app:testReleaseUnitTest --no-build-cache` 309 units（0ee903a 基线 303+6）唯一失败=已知 AtomicFile 环境项。**最终 SHA CI [run 41668707033](https://github.com/ertutio/TabulaV3/actions/runs/41668707033)（head 5a527beb，workflow_dispatch）三 job 全绿**：build 的 testReleaseUnitTest 实际执行非 FROM-CACHE，API36/API37 各 OK(3)+OK(8)+OK(3)+OK(2)=16 项零 skip、无 assumption。独立只读审计（未参与实现新会话，缺陷真实性/verbatim 等价/协议一致性/测试区分力/范围纪律/遗漏消费者/复杂度七面）：**可合并，A–F 全 PASS，0 blockers**；登记 4 条非阻塞：①exitingRightCard 仍裸 ID 比较（回牌动画右卡淡出辅助，同 ID 跨卷约一帧误抑制、自愈、非定位缺陷）；②deck 同 size 同 index 内容变化不触发重校验（两版共有局限）；③legacy 唯一解析用例为兼容 pin（旧逻辑同绿，设计如此）；④revalidateDealtStack O(N×M) 当前规模可接受。回退：按提交逐个 revert（8e3369a→5a527be）。本单元不含 viewer 键错配等 deck/T06 其余登记项（维持登记）。 |
| U6 / PR34 deck 查看器缩略图键一致性（C3/T06-VIEWERKEY） | tested `d014df0d71a9395c9eea50f737f5daf53c15b296`（分支 fix/deck-viewer-thumb-key-2026-11-04，独立基于 master `83dc48b1`；`11199f9` 基线红 → `d014df0` 修复绿；amend 仅修正测试注释中 diskCacheKey 类型描述）。**根因**：deck 查看器（ViewerOverlay，两种卡片模式经 DeckScreen onCardClick 唯一打开）底层缩略图请求用裸 ID 键 `album_grid_${id}`（sys_grid_ 死声明）——全库无写入者（温命中失效）、自写自读下跨卷同 ID 串图（A5 后开 B5 命中 A5 位图）、无内容版本（编辑后陈旧）；deck 生产/消费键为 cardMemoryCacheKey（`card_${uri}_${dateModified}`）。**有效基线 FAIL**（11199f9：内联请求 verbatim 提取为 deckViewerThumbnailRequest 行为保持；修正断言复核）4 FAIL/1 控制组 PASS——错配纯内容差异 expected:<card_content://media/external_primary/images/media/5_1000> but was:<album_grid_5>、跨卷同键、内容陈旧、spec 策略 ENABLED≠READ_ONLY。**修复**（d014df0）：键→cardMemoryCacheKey（与 ImageCard/PreloadingManager 同函数同值：卷限定 URI 跨卷隔离+DATE_MODIFIED 编辑失效+温命中卡片位图）；内存策略 READ_ONLY（240px 占位解码借用不回写，不挤占卡片 720px 条目；Coil 2.7 语义经审计 javap+源码核对：setCacheValue writeEnabled 门控）；磁盘键同 card_（无变换请求磁盘存源字节/本应用 content:// 下 Coil 磁盘实际空转，均无别名）；解码规格不变；不强行统一 HD 层（URI 默认键卷限定）。改动面：ThumbnailCacheKeys.kt、ViewerOverlay.kt、新增 DeckViewerThumbnailKeyTest。**同门禁 PASS**：DeckViewerThumbnailKeyTest 5/5（跨卷同 ID 键隔离/内容更新键变化/生产消费键匹配/单卷控制组/占位解码规格保持）+ ThumbnailCacheKeyTest 3/3 + CoverImageRequestTest 3/3；全量 Windows `:app:testReleaseUnitTest --no-build-cache` 314 units（master 309+5）唯一失败=已知 AtomicFile 环境项。**最终 SHA CI [run 41684261410](https://github.com/ertutio/TabulaV3/actions/runs/41684261410)（head d014df0d，workflow_dispatch）三 job 全绿**：build 的 testReleaseUnitTest 实际执行非 FROM-CACHE，API36/API37 各 OK(3)+OK(8)+OK(3)+OK(2)=16 项零 skip、无 assumption。独立只读审计（未参与实现新会话，含 coil-base-2.7.0 javap 与 Coil EngineInterceptor/MemoryCacheService 源码核对）：**可合并，A–F 全 PASS，0 FAIL**；登记 4 条非阻塞：①温命中依赖 card_ 条目为软件位图（ImageCard 硬件位图窄窗口内借用被配置校验拒绝→冷解码，PreloadingManager 稳态成立）；②card_ 条目缺失时每开冷解码 240px 不回写（基线缺陷条目自温命中消失属预期）；③DeckScreen.kt:405 清理模式预取 uri 裸键无消费者（既有遗留同形缺陷，属清理簇边界，登记小提交候选）；④测试注释类型描述已按审计修正。**证据边界**：键/策略测试只钉真实生产 ImageRequest；Compose 重组/实际命中路径/真实解码无单测基建由源码审读核验。回退：按提交逐个 revert（11199f9→d014df0）。不触及预加载生命周期/PR33 重校验缺口/依赖/性能。 |
| U7 / PR35 预加载生命周期盘点+防抖窗口时序回归（C3/T06 preload lifecycle，仅测试） | **已合并（2026-11-04 用户指示，merge `a42fdfbf`，head `4d6d574e` = master 祖先；合并前核对 head/diff/CI 一致）。注意：Thread.sleep(300) 判定存在测试线程 >100ms 停顿的理论假红窗口（审计 CONCERN-3，与既有测试同风格，CI 观察项）；T06 整组未标完成——预加载/查看器簇尚余 DRAW preloadNextCards 冗余连发清理候选、焦点④调用方半边无测试钉、真实设备/Compose 级验收维持既有口径。** tested `4d6d574eb51584f2a7b464d3d68a2c317822999c`（分支 fix/preload-lifecycle-2026-11-04，独立基于 master `fda3567d`；单提交仅测试 +49/-0，amend 按审计修正措辞）。**盘点**（无生产改动）：四焦点中①同索引换图/换版本 token 守卫、②排队/成功/失败分离+重试、③reset/pause/dispose 过期回调不回写、④借用 Bitmap 所有权契约均已由既有 PreloadingManagerTest 16 项+OwnedBitmapsTest 5 项钉死；两模式（SWIPE/DRAW）dispose/pause/resume 接线齐全、全部 recycle 站点仅作用自有副本（ownedScaledCopyOrNull/createGenieBitmapFast 来源，借用缓存位图全库 0 处 recycle）由独立审计复核。**唯一缺口=防抖窗口（100ms）内 pause/reset/dispose 的时序**（既有测试均先等入队）——新增 3 回归：pauseWithinDebounceWindowHoldsEnqueueUntilResume / resetWithinDebounceWindowHoldsEnqueueAndManagerRemainsReusable / disposeWithinDebounceWindowHoldsEnqueueAndPreventsSubsequentWork（enqueue 仅发生在 delay(100) 后，窗口内操作+睡 300ms 确定性判定；测试钉可观察行为，generation/disposed 守卫与 debounceJob 取消为冗余双保险不区分）。**同门禁 PASS**：PreloadingManagerTest 19/19（实现已正确，无生产修复）、OwnedBitmapsTest 5/5；全量 Windows 317 units（master 314+3）唯一失败=已知 AtomicFile 环境项（--no-build-cache）。**最终 SHA CI [run 41693468519](https://github.com/ertutio/TabulaV3/actions/runs/41693468519)（head 4d6d574e，workflow_dispatch）三 job 全绿**：build 的 testReleaseUnitTest 实际执行非 FROM-CACHE，API36/API37 各 OK(3)+OK(8)+OK(3)+OK(2)=16 项零 skip、无 assumption。独立只读审计（未参与实现新会话，读 PreloadingManager 全文+独立复核接线/回收站点）：**可合并，无 FAIL**；6 条 CONCERN 可登记：①测试措辞已按审计降级为行为语义；②焦点④"借用不回收他人资源"半边为调用方纪律仅静态核对无测试钉；③Thread.sleep(300) 理论假红窗口（与既有测试同风格）；④disposeCancelsAllRequests 未直接断言 scope 取消（间接覆盖）；⑤DRAW preloadNextCards 连发 4 次 preloadAround 前 3 次被防抖取消（既有冗余，清理候选）；⑥reset()/preloadImmediate 无生产调用者（测试专用 API 维持）。**证据边界**：时序回归钉可观察行为，不冒充 Compose 交互/真实解码验收。回退：revert 4d6d574 即完全回退。范围：不改缓存容量/依赖/高清请求/性能基线。T06 状态：本单元只关闭预加载生命周期盘点面，**不标 T06 整组完成**（余项见上）。 |

PR25 的本地执行证据保存于 `C:/Users/yuxuan/Documents/Codex/2026-10-31/wi/work/tabula25-validation/HANDOFF.md` 及同目录 XML/artifacts；远端执行者可用上方精确 run/artifact，不要求访问此本机路径。build artifact ID `13071440539`，SHA-256 `462e5f16e905401f03c1b0f4d4f5c007f781eb786b490a60d1f3ccd6209e1043`。复用前核对输入、代码和环境，文档变更本身不触发产品全量重跑。

## 当前队列与通过条件

| 顺序 / 范围 | 状态 | 行为边界与通过条件 |
| --- | --- | --- |
| 1：C1/C2，O5③ 图集批量永久删除 | **已交付 → [PR #26](https://github.com/ertutio/TabulaV3/pull/26) 待人工评审合并**（证据见已交付边界表 O5③ 行） | 实际选择集、过滤、目标 URI、请求与回写都能区分不同卷同数值 ID；只处理用户确认的完整身份，另卷项不受影响。目标断言走真实生产协调路径（`resolveImagesByIdentitySelection`+端到端接线），部分成功/取消/UNKNOWN 隔离沿用 C2/T04 既有门禁未变。 |
| 2：C1，O5④ 恢复回填差额盘点 | **已闭环登记**（不另立单元，见已交付边界表 O5④ 行） | 现场核对原缺陷已由 PR25 消除；跨卷判重/未知卷兼容/重复恢复幂等均有既有证据；进程死亡/重建属既有真实设备 blocked 簇。 |
| 3：C1/T03 后续消费者 | `ready`（逐簇盘点；含 PR26 复审建议①–④与 C1 盘点表遗留） | Album/元数据、使用统计、冷却/专清、哈希/推荐、存储统计、UI 持久选择。先列读写者与缺失断言，再逐簇迁移；按名合并保留为产品语义，旧键冲突待解析，兼容/幂等/中断可恢复。首查：SystemAlbumViewScreen 网格 key、AlbumViewScreen.kt:214 死代码口径、PhotoGridItem 缓存键、onSetCover 封面 ID、StringDragSelectState itemBounds 残留。 |
| 4：C2/T04 其余删除入口 | **已交付（合成可测矩阵以证据关闭）→ [PR #31](https://github.com/ertutio/TabulaV3/pull/31) 待人工评审合并**（证据见已交付边界表 U3 行） | 三入口均复用既有分批协议（同一结果约定、确认集合无扩大，独立审计确认）；2001 项组合级断言已补，部分成功/取消不发后续批/权限/服务错误/日志故障均有既有证据。真实系统授权/恢复路径与真跨卷维持 blocked 簇；回收站入口 2001 项组合断言为可选后续候选。 |
| 独立：C3/T06；C5/T08 | `ready`（先定位缺口） | 两种卡片模式生命周期/取消重试/旧回调，或工具成功与失败 fixture、验收映射。复用已有测试；PR6 的历史真实测量另需授权及有效协议。 |
| C0/T01、T02 | 已有逻辑层覆盖清单可复用；真实设备差额 `blocked` | 既有清单仅作覆盖依据；出现新的具体反例再修。真跨卷/其他应用授权、进程死亡/断电及完整持久故障仍逐条留缺口，不能拿合成测试关闭。 |
| C4/T07；C6/T10、T11b | 受影响身份/结果契约或有效测量前置未闭环 | 权限/外部修改同步、真实移动结果；统计刷新与有界缓存优化。先核对实际依赖，稳定后继续，未取得有效基线不预写性能提升。 |
| C5/T09；C7/T12、T13 | `ready`（兼容/交付映射）；实际变更按原门控 | 工具链、依赖逐组核实必要性，Release/R8、签名、旧数据及覆盖安装验收；真照片、正式发布及扩围另按授权。 |

### C1/T03 后续消费者盘点（2026-11-01，现场基于 49fc1ad）

| 簇 | 现状（读写者） | 缺口与建议 |
| --- | --- | --- |
| A1 系统图集网格 key | SystemAlbumViewScreen.kt:147 `items(images, key={it.id})`；无选择/删除路径 | 跨卷同名桶两行同 ID → 重复 LazyGrid key 崩溃（进桶即触发，先于查看器）。一行身份键修复；Compose key 行为无单测基建，断言落在 mediaIdentityKey 语义+审读，需声明该证据边界 |
| A2 详情死代码回退路径 | AlbumViewScreen.kt:214 `allImages.filter { it.id in imageIds }`（getAlbumImages 恒提供，生产不可达） | 改身份键或标注口径；无行为断言可立（不可达），随 A1 或独立小提交 |
| A3 缩略图缓存键 | PhotoGridItem（AlbumViewScreen ~:1300）`album_grid_${image.id}` | 跨卷同 ID 串图（仅显示）；改身份键需核对 Coil 键稳定性与预加载键（CardStack cardMemoryCacheKey 同族） |
| A4 拖选边界残留 | StringDragSelectState.itemBounds 离场行不清理（共享组件，回收站/图集/分类视图共用） | 极端时序命中离场键；解析端天然兜底（不在候选列表即空）。低优先，单独小提交可清理离场 bounds |
| B 哈希/相似组（真实行为缺口） | **已交付 → [PR #27](https://github.com/ertutio/TabulaV3/pull/27) 已合并；同族组身份/专清持久化 → [PR #28](https://github.com/ertutio/TabulaV3/pull/28)、网格 key/缩略图键 → [PR #29](https://github.com/ertutio/TabulaV3/pull/29) 待人工评审合并**（证据见已交付边界表 B 簇行、PR28 行、PR29 行）。~~AlbumCleanupEngine 组恢复/持久化裸 ID、SimilarGroup.id 裸 ID~~ 已由 PR28 闭环；~~A1 系统图集网格 key、A3 缩略图缓存键~~ 已由 PR29 闭环 | ~~跨卷同 ID 行共享缓存条目~~ 已修；~~组恢复折叠/冷却标记碰撞~~ 已修（PR28）；~~网格 key/缩略图键跨卷串图~~ 已修（PR29）；剩余：封面簇（含 AlbumTagManagementScreen）、deck ViewerOverlay 键错配（deck/T06 簇） |
| C 封面按 ID | onSetCover(image.id)→Album.coverImageId；AlbumPickerDialog/投影封面 `find { it.id == coverId }` | 跨卷同 ID 封面显示错行（ Album 元数据按名合并语义内）；随 Album 身份簇（如有）再议，先登记 |
| D 按名合并簇（显式保留） | albums_metadata.json、AlbumUsageTracker、AppPreferences 冷却/专清、StorageStatsManager、UI 持久选择 | 2026-10-28 C1 盘点表已给保留决定（与 Album 按名合并一致），不迁移；桶拆分时再议 |



### 后续候选：先确认缺口再立单元

`resolveMediaVolumeName` 的可注入测试边界、恢复 LaunchedEffect 的实际并发窗口、journal Atomic 跨进程证据均需核对现有机制与实际故障；不预定新增保护层。MediaStore 返回 0 行不能直接等同已确认 missing：先证明查询身份与授权/可读性，保留 UNKNOWN 的安全语义；无法证明则登记待决策，转独立项。PR26 独立复审新增候选（均显示层或低风险，逐项确认缺口后接入 T03 后续簇）：① SystemAlbumViewScreen.kt:147 网格纯 ID key（bucket 视图跨卷同 ID 重复 key 崩溃残余）；② AlbumViewScreen.kt:214 纯 ID 回退路径为生产不可达死代码，标注口径或改身份键；③ PhotoGridItem 缩略图缓存键跨卷串图；④ StringDragSelectState itemBounds 离场行残留（解析端已天然兜底）。

## 持续执行与交付

每项目同时 1 个生产行为单元、每文件 1 个写者；无每晚 1–2 项总量上限。依次完成有效 FAIL → 最小修复 → 同门禁 PASS/相邻回归 → 未参与实现的新会话独立只读审计及问题闭环 → 冻结 SHA/保存 commit 与 PR → 更新当前状态并继续最早 ready 项。共享契约未验证或审计未清，只暂停依赖后继；缺少独立会话标 review_pending，记录能力缺口。

每个新单元在本节后登记：T/断言 ID、状态、分支/精确 SHA/父 PR、有效 FAIL/PASS 与实际 skip/cache、独立审计范围、累计失败与升级状态、回退、未提交所有权、运行中 CI 和恢复点。同一卡点两次有效修正仍失败按路线图升级，咨询后一次仍失败等待用户针对卡点明确决定。硬中断先落盘，交付区分“已实现待评审 / 未验证 / 阻塞”；不以任务组数量、免费 token 配额或通用 green 替代验收。

## 历史交付登记（更新前快照，仅供追溯）



以下保留更新前的原始记录。旧 head、review_pending、可合并结论、Windows 失败和 next_action 均以本文上方当前状态覆盖；本表不再驱动执行。旧覆盖映射可按精确版本复用，不扩展为当前整组验收。



| T / 稳定断言 ID / 状态 | exact SHA、分支/PR、父依赖 | 有效 FAIL/PASS、run、skip/cache | 独立审计读取范围、遗留 | 累计修正失败、假设/升级 | 回退、未提交文件/写者、运行中 CI、恢复点 |

| --- | --- | --- | --- | --- | --- |

| 夜间首单元 / 未启动 | 待从实际最新 master 建立 | 未执行 | 未执行；无独立会话则 review_pending | 新卡点需先核旧记录，跨代理/夜晚保留累计次数；预期 baseline FAIL 不计 | 当前恢复点为本文件唯一 next_action；生产未改 |

| A：C0-T01/T02 差额盘点 / `review_pending`（清单交付，无代码变更） | 对照 master@5415c0b 实际 tests 与调用链逐项核验（文档交付，无需实现分支），稳定断言映射见独立审计列 | 同左 | 覆盖映射：T01 同源/别名=MediaStoreCopySafetyTest.insertedUriEqualToSourceIsRejected…/…Aliases；同名同尺寸不同内容=…sameNameAndSizeButDifferentContent…+CleanupEvidenceTest.destinationWithSameSize…；null/短读写/EOF/flush/close/publish=29 项系列；取消=…cancellationAfterInsert/DuringSourceCopy/WhenPublishReturns+rollbackFailureIsLogged；账本写失败=CopyEvidenceStoreTest.writeFailure…+ArchiveCommitEvidenceTest.evidenceWriteFailureKeepsSourceUnverifiedButArchiveCommits（源记录仍加、无账本→清理不可达，保守一致）；确认集合=CleanupEvidenceTest+设备 laterCandidateIsNotDeleted；TC57=concurrentCommits…/cancelThenCommit/commitOfUnknownActionId/queueRebuild…；目标失效保留原图=invalidatedCopyKeepsConfirmedOriginal（设备）。T02 快照/软删除/分桶=ProjectSnapshotTest 6 项；PRESENT/MISSING/UNKNOWN=RestoreDecisionTest 3 项；orientation 新旧 JSON+批量单事务=RecycleBinSerializationTest；持久故障/journal/并发=RecycleBinManagerTest（corrupt/failedSave/atomicRead/journal/concurrentUpdates/failedPartialWrite）+RecycleBinDeletionTest 8 项（recovery 不重复删、UNKNOWN 与现存分离、取消留 journal）；恢复 UI=CloudRestoreUiTest。O2 事务=CreateBucketTransactionTest 6 项。 | 结构核对级（无专测，实现已核）：① CopyEvidenceStore.put/removeAll 有 Mutex 串行化，跨 action 并发无丢失更新（同 action 并发已由 TC57 覆盖）；② DebugAlbumLoadGate 为 #23 调试握手设施，由晚到竞争设备用例行使，不另造辅助类测试。真实设备缺口（不可合成，保持 blocked）：真跨卷/热拔插、其他应用媒体授权、进程死亡/断电、持久层真实故障。 | **结论：T01/T02 逻辑层（合成可测）无未覆盖的可控缺口，无 FAIL→FIX 单元可立**；本清单即 A 项交付物，下游直接引用这些 ID，不重复造测试。 |

| B：C1-T03-DECKFEED-VOLUME / `review_pending` | [PR #24](https://github.com/ertutio/TabulaV3/pull/24)，head `a60a3d04`（分支 fix/deck-feed-volume-identity-2026-11-01，基于 master@5415c0b）；仅 LocalImageRepository.kt 投影/映射（+10/-3）与新增 DeckFeedIdentityTest | 有效 FAIL：DeckFeedIdentityTest 喂两卷同名 bucket + Tabula 行的真实 provider cursor，修复前 `expected:<external_primary> but was:<null>`；修复后 PASS（两行卷身份逐行透传、relativePath 携带、Tabula 排除保持）。全量 testReleaseUnitTest 229 项，唯一失败=atomicReadRestoresLegacyBackup（已知 Windows 本地 AtomicFile 环境差异，CI 绿）。CI run [41202877201](https://github.com/ertutio/TabulaV3/actions/runs/41202877201)（head a60a3d04）build+API36+API37 全绿，双设备 Volume 3+Cleanup 8+Restore 3+CreateAlbum 2=16 项零 skip | 独立审计：无独立会话，标 `review_pending`，自查不等价独立通过。遗留：deck 行 volumeName 的下游消费簇（哈希缓存按卷消歧等）是 C1 后续边界，本单元不动任何缓存键语义；真跨卷设备验收仍 blocked。relativePath 唯一消费者（RecycleBinManager 序列化）吃主查询路径行，不受影响（已核）。 | 累计修正失败 0；回退 revert a60a3d0 即可；无未提交文件 |

| B：C1-T03-SNAPSHOT-CROSSVOLUME-DELETE + 升级兼容 / `review_pending` | [PR #25](https://github.com/ertutio/TabulaV3/pull/25)，**最终 head `cf7e15e`**（分支 fix/snapshot-crossvolume-softdelete-2026-11-01，基于 master@b455f45）：①ce03e00 投影层（projectSnapshot 改收回收站记录本体 + 按 (卷,ID) 匹配，任一侧卷未知退化纯 ID）；②3c1a196 回收站去重/移除与 onRemove 即时状态统一 matchesSameMediaIdentity；③b53a171 注释补记；④cc2b6ed 永久删除卷限定 URI（imageItemUri，loadAllData/queryImages/excluding-Tabula/queryImagesWithBundle 四查询路径）+ 恢复内存态 + RecycleBinScreen 列表 key/选择集身份键（StringDragSelectState）；⑤320da5e 独立复审必修（查看器 Pager key/queryImagesWithBundle 补齐/O5 登记）；⑥8e28f3d 升级兼容 partitionByConfirmedIdentity（聚合 URI 按引用记录已知卷解析比对，未确认→delete 停止 Failed 不写日志不发请求、reconcile 归 unknown 保留记录与 journal、restore 候选分区保留；resolveVolume=VOLUME_NAME 现场查询多行判未确认）；⑦f6a4e81 二轮复审落实（restore 确认过滤/多行二义/死行 canSplit=true 隔离/混合代际钉死）；⑧3965cc4 设备测试三处调用适配；⑨eb53caf+43aeac2 旧 pending 隔离（beginDeletion 合并登记、finishDeletion covered 参数保留未覆盖条目、deleteRecycleBinImages 解除整体阻断改逐批分区隔离）；⑩cf7e15e 合并写去重 | [PR #25](https://github.com/ertutio/TabulaV3/pull/25)，**最终 head `320da5e`**（ce03e00 投影层 → 3c1a196 回收站去重/移除/即时 UI → b53a171 注释补记 → cc2b6ed 永久删除卷限定 URI+恢复内存态+回收站列表 key/选择 → 320da5e 独立复审三发现落实：查看器 Pager key 必修、queryImagesWithBundle 补齐、O5 登记；基础投影统一携带身份列）（分支 fix/snapshot-crossvolume-softdelete-2026-11-01，基于 master@b455f45）：①ce03e00 投影层（projectSnapshot 改收回收站记录本体 Collection<ImageFile> + 卷感知兼容匹配，任一侧卷未知退化纯 ID；MainActivity 两调用点迁移；ProjectSnapshotTest 以 null 卷记录适配）；②3c1a196 回收站与即时 UI（用户复审报告：addAllToRecycleBin 纯 ID 去重静默忽略第二卷记录而即时状态双删、恢复会连带移除——addAllToRecycleBin 去重、removeAllFromRecycleBin 移除、onRemove 即时状态改用共享 matchesSameMediaIdentity——同 ID 且同卷，任一侧卷未知退化纯 ID）；③b53a171 注释补记遗留（按独立复审意见） |  **既有缺陷**（ID-only/聚合 URI 消费早于 T03 字段，非 #24 引入）。有效 FAIL×5（全部修复后 PASS）：①投影「另一卷同数值 ID 行必须保留在浏览投影中」；②回收站「两条不同卷记录必须独立持久化 expected:<2> but was:<1>」；③journal「跨卷同数值 ID 行必须携带可区分的卷限定 URI expected:<2> but was:<1>」；④删除链「身份无法唯一确认时必须停止操作而非删除」；⑤reconcile「无法确认身份的 URI 必须归入 unknown」。对照钉死：聚合 URI 双删行为、混合代际 null+known 按 URI 连带清除（T03 前语义取舍）、死行经 deleteInBatches 隔离到 remaining 而已确认项照删。相邻回归 ProjectSnapshotTest 6/RecycleBin 三套 22/FileOperationManagerTest 11/AlbumDetailImagesTest/BucketIdentityTest 通过；全量 testReleaseUnitTest 238 项唯一失败=atomicReadRestoresLegacyBackup（Windows 本地 AtomicFile 双写 rename 静默失败→回读校验抛出，已确诊与 CI 绿同根因）。**最终 head CI** run [41264233572](https://github.com/ertutio/TabulaV3/actions/runs/41264233572)（head 3965cc4）三 job 全绿，双设备 Volume 3+Cleanup 8+Restore 3+CreateAlbum 2=16 项零 skip；历史 run（41211192139/41218601747/41223283067/41226307125）为各段证据 | **独立只读复审五轮已完成**（新上下文 Agent，均未参与实现）：①一轮 PASS+两必要跟进（O5 补记/注释更正）已采纳；②二轮四发现（restore 漏过滤 FAIL——未确认候选落入 else→PROCEED_RESTORE 未核验即恢复、多行二义、死行活锁、覆盖缺口）全部落实；③三轮总评**可合并**，四项二轮遗留闭环无新回归。非阻塞 CONCERN 两条列入后续：resolveMediaVolumeName 私有无测试护栏、僵尸记录 0 行可考虑判 confirmable-missing（方向保守安全）。Coil/分享/归档账本混合代际兼容经复审确认（resolveMediaItemKey 现场归一，失败侧保守不删）。遗留：见 O5 行；真跨卷设备验收仍 blocked | 累计修正失败 0（测试断言笔误/夹具路径/androidTest 调用点适配均在提交前或当轮修正；Windows AtomicFile 双写为环境发现非卡点）；回退按提交逐个 revert（ce03e00…3965cc4 八个提交）；无未提交文件 | |


现有独立性/设备缺口：真跨卷、其他应用媒体、进程死亡和完整持久故障证据尚缺；没有可用独立会话或高阶只读咨询时记录能力缺口并暂停对应依赖，不把自查写成独立 PASS。两次有效针对性修正仍失败先咨询；咨询后一次仍失败须等用户对此卡点明确决定。



## 历史记录的解释边界



后文完整保留旧证据与原始过程。旧 `review_pending`、待合并、O2 待诊断、Toast/握手待办、历史 next_action 和旧计数均已被当前游标覆盖；它们不是今晚队列，也不能覆盖上述已合入与精确 run 事实。历史 PASS 仅覆盖当时实际夹具；其他 T 组整体仍需逐断言验收。



## 历史证据归档（截至 2026-10-30）



- [#11](https://github.com/ertutio/TabulaV3/pull/11) 已合并；#10 已作为被覆盖 PR 关闭，未单独合并。merge 的父提交是原 `d2038b8e0dc8c99fbdf2c754e88dec0a8078a725` 与测试提交 `b2facebf5e6137ff0c15d1a1eedf1fd0bb5bc1cf`。

- [run 40680146410](https://github.com/ertutio/TabulaV3/actions/runs/40680146410) 在该测试提交上成功：build、API36、API37 三个 job 成功；每台 VolumeDeletionDeviceTest 3 项、CloudCleanupUiTest 2 项、CloudRestoreUiTest 2 项，共 14 项实际设备测试通过。

- 每台登记 17 项合成媒体，清理前 15 项存在、2 项已由正文删除，清理后 17 项均确认不存在；集合外哨兵保护断言通过并独立清理。每台三个测试窗口的 `issues=[]`，系统进程身份稳定，三条 volume 观测的 external_primary 为 mounted/recent，actuallyUnmounted 为空。

- API37 使用 cmdline-tools 22.0、AVD target `android-37.0`、image revision 7；实际启动 emulator 37.2.12.0。API36 使用原工具 12.0、target `android-36`、image revision 7。保留环境身份再讨论跨环境复用。

- 本轮 `testReleaseUnitTest` 为 `FROM-CACHE`，没有本轮新执行/计数。此前 run [40650966680](https://github.com/ertutio/TabulaV3/actions/runs/40650966680) 的 XML 汇总为 176 tests、0 failures/errors/skips；这不是未来固定测试数，也不能据此把全部 TC 判为 PASS。

- [run 40717554994](https://github.com/ertutio/TabulaV3/actions/runs/40717554994)（workflow_dispatch，ref docs/continuous-development-2026-10-28，head sha `6a685e3ed4440f0c8a9f40a5290dc2600eb6facc`）三 job 成功：build + API36 + API37。设备逐例：API36 旧探针 OK (2 tests)；API37 VolumeDeletionDeviceTest OK (3)、CloudCleanupUiTest OK (2)、CloudRestoreUiTest OK (2)（singleRestoreUpdatesAlbumWithoutManualRefresh / batchRestoreUpdatesAlbumWithoutManualRefresh）。即本分支 C0.1/C0.3/C1.1 改动在两台云模拟器实测无回归，且 T01 清理确认集、T02 单张/批量恢复接线取得真实 UI 证据。



## 历史已交付范围快照（后续状态见当前游标）



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



## 历史保存点（原始过程与失败记录）



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

| O5 跨卷身份旧口径遗留登记 / 部分闭环（①②已由 PR #25 修复；③④及代际残留待续） | ①物理删除链按聚合 URI 消费身份 → **已修**（320da5e：loadAllData/queryImages/queryImagesExcludingTabulaAlbums/queryImagesWithBundle 对卷已知行构造卷限定 item URI，journal/系统删除/核验/finishDeletion 全链可区分；卷未知行保持聚合 URI）。②恢复路径内存态过滤/回插门槛按纯 ID → **已修**（3c1a196+cc2b6ed：matchesSameMediaIdentity 同口径）。③图集批量永久删除按 id 集合（MainActivity:1373 附近）同 ID 双选 → 待续。④MainActivity:584 imagesForSorting 恢复回填去重按纯 ID → 待续（同模式）。⑤**代际残留**（中间版本构建落盘的 "volumeName 有值 + 聚合 URI" 记录）：8e28f3d 升级兼容 + eb53caf/43aeac2 隔离后——无法唯一确认时保留记录与 journal 并**停止该项操作，不再阻断其他照片删除**（delete Failed canSplit=true 拆分隔离、reconcile 归 unknown 稳定停止、restore 计入保留）；单卷解析一致时照常删除。残留：0 行/解析失败的僵尸记录无 UI 逃生口（方向保守安全，可考虑判 confirmable-missing）；resolveMediaVolumeName 私有无测试护栏。 | ③④按同模式（真实链路 FAIL→最小修复→PASS）接入 matchesSameMediaIdentity；⑤如需闭环须评估 loadUnlocked 读时按 volumeName 重构卷限定 URI 的迁移（行为变更单独立项）。 | 跨卷同 ID 用户的物理删除正确性（新记录已修、代际残留未修）与恢复路径 UI 一致性（已修）；不阻塞单卷用户与其他 T 组。由 PR #25 两轮独立复审发现并补记。 |

| C0.6 旧结果晚到竞争确定性握手 / `review_pending` | 接缝强化（仅 debug）：`debugAlbumLoadDelayMs` 延迟前写 logcat 标记 `albumDetailLoad entering delay window`（握手），延迟改 NonCancellable 模拟"结果在调用方取消后才完成"的最坏情形；新用例 albumDetailLateResultAfterSwitchDoesNotOverwriteNewPage：打开旧图集→轮询握手确认旧查询在窗口内→返回打开新图集→第二次握手→新照片出现且旧窗口到期后，断言旧图集照片不出现在新页、新照片保持；探针声明 6→7 | [test/late-result-race-handshake-2026-10-30](https://github.com/ertutio/TabulaV3/tree/test/late-result-race-handshake-2026-10-30) → [PR #21](https://github.com/ertutio/TabulaV3/pull/21)（基于 master@b2d6b43，head 95d21fe） | 两轮失败均已诊断并记录：41092875221 基线在导航完成后采样（自家标记已计入）→ ccae121 前移基线；41097770580 仍失败 → 定位 UiAutomation 字符串入口按空白分词（acceptance.md 已载明），管道/重定向被当字面参数使计数恒 0 → 95d21fe 在 Kotlin 侧计数；最终 run [41100985640](https://github.com/ertutio/TabulaV3/actions/runs/41100985640)（head 95d21fe）build+API36+API37 全绿，双设备 CloudCleanupUiTest OK(7) | 接缝构造的是守卫必须处理的确定性最坏情形，不是对真实竞态窗口大小的测量；真实 MediaStore 查询取消时序不在断言范围 |



每条验证至少记录：case ID 与断言、源码 SHA/相关输入、命令或 run URL、环境/fixture、实际结果（含失败/跳过/缓存）、报告位置。每条阻塞记录：所缺条件、累计失败与假设、已试结果、需要谁做什么、解除条件、可继续的独立项。收到评审意见后更新原子项，不另起一个无法对账的任务名。



## 2026-10-30 审计、修复与合并验收



本轮按 #14 → #17 → #16 → #15 合入；前三项 merge 为 `83dda1e`、`177fc08`、`e86f741`，最后为 `b2d6b43e`。#15 修复首次组合验证即通过，没有重复派发工作流。四项均为局部增量；C0–C3 和总任务仍未完成。计划 PR #12 保持单独开放，生产代码已通过上述独立 PR 合入。



最终 [41003765715](https://github.com/ertutio/TabulaV3/actions/runs/41003765715) 每设备 12 项通过；六个命名健康窗口均 issues=[]，boot_id 不变，system_server/SurfaceFlinger PID 与 starttime 稳定，未发现 Tabula ANR/fatal 或 DeadSystem 标记。API36 的专用预检条件跳过属设计内行为，其实际用例未跳过。环境仍有 GMS 后台 ANR（API36 两次启动超时、API37 一次 SIM_STATE_CHANGED 广播超时），未影响本次 instrumentation，通过记录不能写成全系统无 ANR。



#17 缺查询进入延迟窗口的握手，且重开旧页照片出现后才切换新图集；通过的是返回/重开/顺序切换，旧慢查询晚到新页的竞争仍待专用断言。#16 缺相同失败输入的未取消对照，不能把两个不同输入的结果当作取消因果的完整证明。O2 的旧运行引用相互矛盾已纠正为待诊断；下一步先确认真实失败阶段，再选择实现。
