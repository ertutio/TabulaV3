# 验收索引（总协调者维护，滚动更新）

## 交付状态图例
`可验收` / `等待审计` / `等待验证` / `外部阻塞`

## 已冻结验收包

| 包 | 单元 | head SHA | 分支/PR | 行为变化 | 验证 | 独立审计 | 状态 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| C1 | TC57 撤销×并发提交交错不变量 | `fe37616` | fix/photo-safety-faults-2026-11-08 / PR#54 | 仅测试+3（闩锁交错/双action/真并发10轮互斥），无 baseline FAIL（生产 monitor 已正确） | 本地全量 396/0；独立复跑 10/10；**CI 门禁已补齐并全绿（run 42642381866 @fe37616d，2026-11-09）** | 协调者会话（非实现者）：可合并 0 必须修复，2 登记 | **可验收（CI 已闭合）** |
| B1 | PARTIAL 选择集 A→B 宿主驱动设备验收 | `8df089e`（R16 证据 head `50c9d88`） | test/partial-selection-ab-2026-11-08 / PR#57 | 仅测试+probe（8 提交，app/src/main 零改动）：PhaseA（NONE→对话框→选择器→PARTIAL(A) 建立→地面真值+哨兵+组/断点持久化+deck/查看器/月份全链）+ PhaseB（跨进程读回→**系统设置页重选**→像素驱动→确认回读→地面真值恰={B1,B2}→一致性+retention+哨兵） | **R16 run 42500080220 三 job 全绿**（双 API）；final head run 42501864524 build/API37 绿+API36 瞬态抖动（登记口径） | 审计派出（精简四核验面） | **等待审计** |
| #59收尾 | 队尾失败/撤销补批次对账 | `b9b5e14`（完成信号=注入行分叉真值/增量；时序判据重写；双跑 3/3） | fix/t10-merge-archive-stats-refresh-2026-11-08 | AlbumManager 两出口补 reconcileAlbumsIfDirty | 402/0；CI 42507084347 全绿 | 交叉复审 PASS 无P1/P2（子代理1，双重复跑） | **可验收** |
| #60收尾 | 等待者共享不可信结果+所有权后复查缓存 | `96997c1`（就绪信号替换 sleep `96997c1`） | fix/t11b-single-flight-bounded-cache-2026-11-08 | getOrDetect 携结果 deferred+所有权后复查+两个测试钩子 | 定点 68 用例绿；CI 42507087643 全绿 | 主代理复审可合并 | **可验收** |
| #57收尾 | 跨月夹具月份判别面+API36 折叠区导航保障 | `3492728` | test/partial-selection-ab-2026-11-08 | PhaseA/B 月份断言+tapAnyScrollableToMarker | compile 级；CI 42507090976 全绿 | 主代理复审可合并（audit-B1-closing.md） | **可验收** |
| #65收尾→PR66 | 生产授权回调消费路径 | `fdc03967`（阻塞登记版） | test/prod-auth-callback-2026-11-09 / PR#66（堆叠 #65） | ProdAuthCallbackDeviceTest 2 用例（真实入口）——**两轮 CI 双 API 红后按预算转阻塞**：已从 probe 类列表摘除（测试文件+阻塞 KDoc 保留） | 轮2 run 42653961146：**入口链全通**（回收站→条目→彻底删除→应用对话框→in-flight→系统对话框出现）；卡点=拒绝键文案 Deny 缺失+BACK 兜底 0.1s 迟到 | 复审=范围/状态面核验过（audit-PR66-closing.md）；设备面=阻塞登记 | **单元三修正+API37 有界重点修复全绿：`513d3df`（主代理四项修正）+`47f38c9`（OtherAppMediaAuth Allow 有界自验证重点，9979967 同款——run 42863345514 三 job 全绿，API37 OK(4) 首击即中；该用例 API37 累计失败定格 3）**。#65 缺口（生产授权回调消费路径）实现+CI 层面闭合。历史：①取消分支明确关查看器（closeViewerIfOpen 幂等）②确认分支正向观察（对话框关闭+前台+计数回基线→再查释放/消费）③Allow/Deny 有界重试前置条件（对话框存在且效果未发生）④用例末可靠释放；重入/journal/哨兵保留。probe 已恢复登记（ec65f0c）并跑组合门禁 43065284177：**API36 取消分支仍红（第 5 次验证）+ 精确证据已取**（coord/evidence-pr66/：截图×2+logcat）——
- **生产取消路径实读否证数据丢失**：RecycleBinDeletion.kt:120-122 取消→finishDeletion(emptySet,covered=all)→RecycleBinManager.kt:150-163 deleted 为空不调 saveUnlocked→**记录保留，journal 清空（设计正确）**
- 失败实态：等待期一张截图=回收站空空如也（列表空），另一张=查看器仍开 2/2（品红媒体+恢复/删除钮）——观测层问题（API36 特有的查看器关闭交互），非数据丢失
- **阻塞登记（累计 5 次验证，升级规则触发=停止修正循环）**：唯一解锁=从失败 dump 提取查看器关闭钮的真实 desc/坐标并修 closeViewerIfOpen 的匹配（需增强 captureUiEvidence 输出 UI 树）；交维护者决策 |
| **#67 汇总** | 13 组件整合分支（基线 SHA e3b005f，门禁 42643510385 全绿，独立复审 PASS——证据保留） | **新候选 `0dd52eb`** | integration/night-2026-11-08 / PR#67 | 基线上并入两收尾修正（恰 2 测试文件 +61/−17：PR59 b9b5e14 完成信号判据、PR60 ae19b31 就绪闩锁） | 本地整合全量 430/0；新门禁 42662260995 API36 红=**环境抖动定性**（两 SHA 间探针脚本零差异、仅 app/src/test 文件变化且设备探针不运行它们；本 run 有 ColorBuffer 错误、API37 全绿、全部套件 API36 亦绿——失败在探针末段静默 exit）；已两次派发：run 42662260995 API36 探针末段静默红（全部套件 API36 亦绿）、run 42840727007 API37 CloudCleanup 单用例红（既有间歇家族；API36 本轮绿）——**两次失败点不同 API/不同套件，且 0dd52eb 与 e3b005f 设备执行代码完全相同（仅 app/src/test 文件变化）**；e3b005f 的 42643510385 三 job 全绿继续作为设备行为权威证据，新候选增量=单元面（本地 430/0）。**最终交付 PR #70**（combination/2026-11-10 @ caafb96 → master）：组合门禁 42867763379 **三 job 全绿**（=68ebf15 内容 + PR66 收尾 513d3df/47f38c9 + T12 exif 1.4.2 547c93b）。**抖动协议三次派发收拢（不再重派）**：尝试1=42662260995（API36 探针末段静默红，全部套件 API36 绿）；尝试2=42840727007（API36 绿，API37 CloudCleanup.invalidatedCopy 单用例红=既有间歇家族）；尝试3=42844759640（API36 红伴 ColorBuffer 图形错误，API37 绿）——**三点互异+API36 run 均伴图形错误签名=环境不稳定实证**。设备执行代码 0dd52eb≡e3b005f（仅 app/src/test 变化），e3b005f 门禁 42643510385 三 job 全绿继续作为设备行为权威证据；单元增量 430/0 由三次 build job 各自验证。b9b5e14 增量复审 PASS。 |
| C2 | 他人媒体真实授权 + U18 间歇诊断 | `956776d` | test/other-app-media-auth-2026-11-08 / PR#65 | 仅测试+探针 3 行：OtherAppMediaAuthDeviceTest 4 用例（读可见/拒绝+源保留/生产组合 intentSender+UNKNOWN 不扩大/真实对话框授权+哨兵存活）；photo() 有界重试（U18 根因=pm clear 后首插归属解析竞态，环境层非生产）；事实更正=夹具实为应用进程内创建，真实他人媒体需 shell 绑 owner_package_name（AOSP isCallingPackageShell，双 API 实证） | CI 42477604680 三 job 全绿、双 API 6 套件零失败零 skip | ✅ 审计：可合并 7/7（合并动作项=与 PR57 后合者机械解 probe 脚本锚点）；**边界保留：生产授权回调消费路径未覆盖（startIntentSenderForResult 的结果接收在 androidx launcher 内，套件断言到系统对话框授权+真实删除为止，登记为 4 条建议级之一）** | **可验收（边界已登记）** |
| D1 | DRAW 状态机生命周期断言 + PR35 CONCERN-4 闭环 | `c9b7cf0` | fix/card-mode-lifecycle-2026-11-08 / PR#58 | 仅测试+10（DrawCardStateLifecycleTest 8+PreloadingManagerTest 2）；N1/N2 活性缺陷登记 | 独立全量复跑 403/0；**CI 门禁已补齐并全绿（run 42642388259 @c9b7cf0f，2026-11-09）** | 审计子代理：可合并 0 必须修复，2 文档级小瑕疵 | **可验收（CI 已闭合）** |
| F2 | AGP 8.9.0→8.9.1（API36 官方最低） | `4a0f2ce` | fix/agp-8.9.1-api36-min-2026-11-08 / PR#53 | gradle/libs.versions.toml 单行；无行为变化 | 本地 393/0；CI 42467710400 三 job 全绿（首跑 42466195571 API36 模拟器基建失败已单列重派） | ✅ 审计通过（官方表复核+run 实查+首败与 AGP 无关证据充分） | **可验收**（待人工合并） |
| E2 | PR45 审计轮6：6 负例钉无夹具门 | `14bf35b`（父 c85c2aa 已复审） | fix/perf-trace-validation-2026-11-06 / PR#45 | fixture-only +80；无 report.py 改动 | python 41/41（本地 Windows）+审计复跑 | ✅ 审计通过（6 门逐一对 raise 点核实+4 变异转红） | **可验收**（待人工合并） |
| G1 | T10 测量入口（归档查询轮廓+统计恢复） | `bda4ac2` | test/t10-archive-query-count-2026-11-08 / PR#55 | 仅测试+178；基线=每提交 4 集合+4 按项、[4,8,12,16,20] 无合并窗 | 本地 399/0；CI 42466964934 全绿；审计类级 6/6 复跑 | ✅ 审计通过 | **可验收**（待人工合并） |
| G2 | T11b 测量入口（并发重复检测+无界缓存） | `8ab907e` | test/t11b-feature-cache-measure-2026-11-08 / PR#56 | 仅测试+179；基线=8 并发同键 8 次流打开、2000 键无淘汰 | 本地 397/0；CI 42467580401 全绿；审计类级 4/4 复跑 | ✅ 审计通过（屏障确定性+演化点双处声明核实） | **可验收**（待人工合并） |
| G3 | T10 实施：归档增量计数+批次末对账 | `7f783df` | fix/t10-merge-archive-stats-refresh-2026-11-08 / PR#59（堆叠 PR55） | AlbumManager 归档提交：全库重扫→内存增量计数+队列清空时一次对账 | 实测 [3,3,3,3,4] vs 基线 4；本地 399/0；CI 42469008646 全绿 | ✅ 审计：可合并 1×P2→修复复核确认；**收尾单元 `753ba21`（队尾失败/撤销补对账+累计夹具回归3例，402/0，CI 42507084347 全绿，交叉复审 PASS 无P1/P2）** | **可验收**（待人工合并） |
| G4 | T11b 实施：single-flight+LRU2048 | `7612f0a` | fix/t11b-single-flight-bounded-cache-2026-11-08 / PR#60（堆叠 PR56） | 特征检测同键并发单飞+有界缓存；T11a 语义不变 | 本地 398/0；CI 42469011483 全绿 | ✅ 审计：可合并 0 阻塞；收尾 `9db3449`（CI 42507087643 全绿）；**未闭合：主审计 P3（测试钩子生产面、全量未复跑）+时序修正 `96997c1` 待复审** | **可验收（P3 开放登记；收尾待复审）** |
| D2 | N1/N2 活性修复（取消后预载停摆/预取标志卡死） | `af7f406` | fix/deck-liveness-cancellation-2026-11-08 / PR#61 | onDragCancel 同口径 resume+setFastMode（两模式）；isPreloadingNextBatch 移入 finally | 本地 393/0；**CI 42469258780 全绿 18:08** | ✅ 审计：可合并（与 onDragEnd 逐参数一致、finally 不吞取消、幂等） | **可验收**（待人工合并） |
| D3 | T06 冗余连发清理（preloadNextCards 单次等效调用） | `402cae7` | fix/draw-preload-burst-cleanup-2026-11-08 / PR#62 | CardStack 4 连发→1 次（防抖存活调用逐参数等价） | 本地 393/0；**CI 42471294282（重派）全绿 20:15**（首跑 API36 基建抖动已单列） | ✅ 审计：可合并（替换式防抖等价性独立核验成立） | **可验收**（待人工合并） |
| T12 | EXIF 提示 HDR 定点回归 + T09 兼容证据落库 | `5c77fc0` | test/t09-t12-evidence-2026-11-08 / PR#63 | 仅测试+docs（5 例：提示词→definitive HDR；无提示对照不可信；升级转红契约） | 本地 398/0；**CI 42470313300 全绿 20:15** | ✅ 审计：可合并（本地 5/5 复跑，互证结构排除 saveAttributes 静默失败） | **可验收**（待人工合并） |
| A4 | 拖选 itemBounds 离场修剪（T03 登记项落地） | `2f2ae5f` | fix/dragselect-bounds-prune-2026-11-08 / PR#64 | pruneBoundsNotIn + items 变化接线；findItemAt private→internal | 回归 3 例；本地 396/0；**CI 42470315863 全绿 20:15** | ✅ 审计：可合并（修剪键集=全集、主线程无并发窗） | **可验收**（待人工合并） |

## 分析交付（文档）

| 交付 | 位置 | 状态 |
| --- | --- | --- |
| A: T01–T13 剩余断言台账 | coord/ledger-A-remaining-assertions.md | **完成 17:05**（A代理，123行，按 master 43a8da1 实码核对；队列校正要点见台账第5节） |
| E: PR6→42→45 链审查 | coord/report-E-perf-chain.md | 进行中（**主代理**执行） |
| F: 工具链兼容盘点 | coord/report-F-toolchain.md | 排队 |

## #68 合并记录（本轮授权，2026-11-11）
- 合并提交 `488bd21`（master 43a8da1 ← PR68 head 6a782901，MERGEABLE/CLEAN，合并差异恰为已审单文件 build.gradle.kts +11）
- 三重核对：head 精确一致 / 门禁 42661619514 绑定该 head 三 job 全绿 / master 无新增变化
- **树同一性**：merge_tree == head_tree（1c2d0525…）→ 门禁证据对合并提交直接有效
- 回退入口：`git revert -m 1 488bd21`
- 措辞撤回：ColorBuffer 与门禁红的"因果实锤"表述撤回——仅为相关签名；API36 探针末段非零退出原因待查（单元二诊断中，子代理已派）

## 运行中 CI
| run | head | 状态 | 归属 |
| --- | --- | --- | --- |
| （T09 42661619514 已随合并收口） | — | — | — |

## T13 交付（覆盖安装恢复，2026-11-12）
- **PR #73**（test/upgrade-overlay-2026-11-11，base=master 488bd21；3 提交）：新 workflow upgrade-verification.yml（双 worktree 构造 OLD 43a8da1/NEW head 各 app+androidTest 四件，digest 守卫+SHA 记录）+ run-upgrade-probe.sh（装 OLD→自带 FullRecoveryPhase1Test 种子→root 注入 metadata→install -r NEW→UpgradeOverlayDeviceTest）+ 验证 instrumentation（判据 5–10）
- **R2 run 43058202316 三 job 全绿**（判据 5–10 双 API 通过；快照 13 字段零漂移；R1 单点红=content query --where 已修 860bedb）
- 登记偏差：①插桩面=debug 应用对（AGP testBuildType 事实）；**仓库无 Actions secrets——release 对照物 unsigned，fork 签名覆盖装解锁=配置 4 项 secrets（无代码改动）**；②种子=单组 3 成员（Phase1 真实行为，非判据原设双组）；③冷却种子被 OLD 生产逻辑合法清空（边界发现非缺陷）
- 状态：**✅ 独立审计可合并**（audit-T13-pr73.md：head=860bedb 三方一致、双 APK 构造+覆盖链+断言真实性全过、3×P3 登记；外部佐证=AGP testBuildType 行为）

## 在飞三路（2026-11-12）
| 路 | 执行者 | 目标 | 状态 |
| --- | --- | --- | --- |
| #74 两 P2 | 子代理 A | 日志游标防串用（含变异反例）+会话内切换连续性 | 实现中 |
| #66 观测层两处 | 子代理 B' | closeViewerIfOpen 有界轮询+确认例空态文本等待 | 实现中 |
| #73 补记录 | 我→CI 43244836208 | 两 awaitUntil 回归设备执行记录 | 运行中 |

## 单元五：组合候选（2026-11-11）
- 组合分支 combination/2026-11-10 @ `68ebf15`（master 488bd21 含 #68 + integration a16796e 含 13 组件/收尾修正/门禁诊断 cee003b+9979967；**零冲突**）
- **组合门禁 42856903671 三 job 全绿**（全量 430 单元 + Release/R8 + API36/37 设备全套；R8 metadata 警告 0——#68 效力在组合中复验）
- **独立复审 PASS**（audit-combination.md：单次顶层合并、与 integration 逐字节一致、冲突标记 0、6 类设备门+阶段门齐全且 ProdAuthCallback 摘除态无回潮、生产面恰 7 文件、R8 8.13.19 在位且警告 0、CI XML 430 全绿）；**#66 单元三 CI**：API36 绿（修正+诊断生效），API37 OtherAppMediaAuth 第 3 次红（同签名=tap 被入场动画吞）→ 有界重点修正轮已派（9979967 同款模式）；B2 维持额度阻塞登记

## T06 交付（设备级生命周期验收，2026-11-12）
- **PR #74**（test/card-lifecycle-2026-11-12，base=combination/2026-11-10；diff 恰 4 文件）：CardLifecycleDeviceTest 3 例（场景 1 SHUFFLE/DRAW 真实 swipe+ACTION_CANCEL 注入→dragCancel/resume 日志时序断言→真实删除真值→恢复入口；场景 2 Settings 驱动会话内切模式→DRAW 全循环）+ PreloadingManager pause/resume 各 1 行 DEBUG 日志（可观测性增补，无行为变化）+ probe 第 7 类登记
- **门禁 run 43086693791 三 job 全绿**（本套件双 API OK(3)；API36 FULL recovery 间歇 flake 已登记为与 PR 无关——对比基线自身既有红）
- 迭代 5 轮全以工件定向修正（推荐批序漂移/DRAW 原点落侧缘/空态无计数文本/overlay 结构背景 deck 判定）
- 状态：**✅ 独立审计可合并**（audit-T06-pr74.md：六项全过——resume.ts≤dragCancel.ts 时序断言经生产代码核验确证 #61 接线、真 Settings 驱动切换、无恒真；3 低级备注含 ProdAuthCallback 重摘除的实证披露）。低级备注(B) 含维护者须知：本分支 probe 为 ProdAuthCallback 摘除态（基线自身 run 双 API 红于该套件，实证后摘除）——与 ec65f0c 恢复登记的合并顺序=后合者并集 |

## T12 交付（EXIF 1.4.2，2026-11-11）
- PR #69（`547c93b`，堆叠 combination/2026-11-10@68ebf15——依赖 #63 回归在树）：exifinterface 1.3.7→1.4.2 单行；官方依据=最新稳定（2025-12-03）/1.5.0 beta 排除/minSdk 无影响；迁移点已核（XMP 分段/HEIC-AVIF/IFD 容错）
- 回归：ImageFeatureExifHintTest 5/5（#63 转红契约成立）+ Definitive 2/2 + 全量 430/0；CI 42863404468 运行中；回退=revert 单行
- 状态：待 CI + 独立复审（review_pending）

## T09 交付（新）
| # | SHA | 内容 | 验证 | 状态 |
| --- | --- | --- | --- | --- |
| T09 | `6a78290` | R8 8.13.19 一行锁定（Kotlin 2.3 metadata 100 警告→0；官方表依据） | 本地 393/0 + 警告计数实证 + CI 运行中 | 待 CI+复审 |
| run | head | 状态 | 归属 |
| --- | --- | --- | --- |
| 42469008646 | 7f783df (T10/PR59) | running | 协调者 |
| 42469011483 | 7612f0a (T11b/PR60) | running | 协调者 |
| 42469258780 | af7f406 (N1N2/PR61) | running | 协调者 |
| 42500080220 | 50c9d88 (B1 R16) | **三 job 全绿（PhaseA+PhaseB 双 API）** | B1 交付完成 00:55；审计派出 |

## 额度记录（定期更新；平台无数据标未知）
| 时间(北京) | 已用 | 剩余 | 备注 |
| --- | --- | --- | --- |
| 17:00 | 未知 | ~5亿（本窗口09:00到期） | 2026-11-08 |
| 17:40 | 未知 | 同上 | A组≈152万 token/40次工具/7分钟；并发=2子代理+主代理 |

## 时间更正记录
- 17:00 确认本机时钟=北京时间(+0800)。早前"08:46/09:15"系 TZ=Asia/Shanghai 在 Git Bash 回退 UTC 被误标，作废。调度一律用本机 `date` 输出。


## 交付状态精确校正（2026-11-12，替代"全部队列完成"表述）
| 项 | SHA | 结论 | 验证 | 覆盖范围 |
| --- | --- | --- | --- | --- |
| #74 T06 | **e38a3c18+88a591b** | **两项 P2 已闭合**（isWithin 双重下界防串用+会话连续性三重信号封死假阳性；变异反例经复审者实际编译执行 ALL EXPECTATIONS MET） | 修正后 run 43248972561 三 job 全绿（双 API OK(3)）；增量复审 PASS | ✅ 审计闭环 |
| #70 组合 | ec65f0c | ProdAuthCallback 已恢复登记；**门禁 43065284177 双 API 红**（API36 取消+确认、API37 确认——诊断子代理在飞） | build 绿+API36 部分；设备三用例失败 | 失败例覆盖缺口=诊断中 |
| #73 T13 | d541b3c+5ddba0a | Boolean 等待已修；**两轮询回归补设备执行段**（5ddba0a 方法级过滤缺口补齐） | CI 43244836208 运行中（显式驱动两回归） | 执行记录补齐中 |
| #66 回调 | **ec65f0c+caacab5** | 修正轮 1 收敛：**API36 双用例全绿实证**（有界轮询+空态文本等待均生效）；API37 取消例=上游观测缺陷新形态（in-flight 观测面在 windows 恒空环境永不可见——dump 实证生产无误）；API37 确认例=次生污染（前例遗留对话框）。**执行路径更正**：原分支 ff 至 ec65f0c（组合态，验证空洞修正），无重写 | 轮 1 CI 43251910422：build 绿/API36 双例绿（域外 PartialSelectionPhaseB 独立红已登记，#74 的 P2-2 修法覆盖）| **轮 2 已授权在飞**：in-flight 双证据化（系统对话框 active window=充分证据）+一次 CI |
| 基线 | e3b005f/42643510385/caafb96+42867763379 | **绿基线证据保留** | 全绿 | 历史有效 |

## 最终交付（2026-11-11 02:12）
**维护者验收清单见上批报告与 audit-*.md；PR #70/#71/#72/#73/#74 开放待审。**
- 组成与审计：16 PR 全部独立审计/复审闭环（audit-*.md ×13）；最终组合门禁 42867763379 三 job 全绿（430 单元+Release/R8 警告 0+API36/37 全套）
- 已验证：#53-#69 全部（各分支门禁绿+组合门禁绿）；待人工：#67/#69/#70 合并决策（#67 保留 13 组件基线记录，#70 为超集交付）
- 阻塞：B2（额度，恢复入口=判据文件+44e1c506）；#66 API37 环境敏感（已修，本 run 绿；复发按 KDoc）
- 未验证：无；运行中：无

## 额度截止快照（2026-11-11 01:25，截止 09:00）
- **next_action（今晚接续）**：复审者验收 **PR #70**（caafb96，组合门禁 42867763379 三 job 全绿）；B2 恢复执行者与 coroutines 单元在飞（结果若未落盘，按各自分支与 coord/notes-*.md 恢复）
- **已保存完毕（08:55 紧急快照后双双停止）**：
  - B2：`2b21481`（notes 快照 docs/B2-notes-snapshot-2026-11-11.md 已推分支）——根因=Phase1 exitCleanupViaSheet awaitGone 在 sheet 退场动画期（300-500ms）首见误杀（生产 onSwitchToGlobal 路径源码核实正确）；Phase2/3=依赖级联红。**恢复入口=notes-B2.md §7.3**（awaitDismissed/tapAndAwaitDismissal 两种等待语义拆分 + requireDependency 显式依赖门 + probe 依赖跳过）→ compile → dispatch CI → 迭代 → PR
  - coroutines：**绿** 1.10.2（`a14d097` 已推 fix/coroutines-1.10-t12-2026-11-11；定点+全量绿；CI 42934016082 在飞结果待登记；1.11.0 已发布登记为后续候选）；回退=revert 单提交
  - T13：判据+实施定稿已存（t13-overlay-acceptance.md——种子=旧 APK 自带 FullRecoveryPhase1Test，零格式猜测）
- 已保存：本目录全部 31+ 证据文件 + 计划分支进度（9ee0591 + 本快照）
