# 验收索引（总协调者维护，滚动更新）

## 交付状态图例
`可验收` / `等待审计` / `等待验证` / `外部阻塞`

## 已冻结验收包

| 包 | 单元 | head SHA | 分支/PR | 行为变化 | 验证 | 独立审计 | 状态 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| C1 | TC57 撤销×并发提交交错不变量 | `fe37616` | fix/photo-safety-faults-2026-11-08 / PR#54 | 仅测试+3（闩锁交错/双action/真并发10轮互斥），无 baseline FAIL（生产 monitor 已正确） | 本地全量 396/0；独立复跑 10/10；**CI 门禁已补齐并全绿（run 42642381866 @fe37616d，2026-11-09）** | 协调者会话（非实现者）：可合并 0 必须修复，2 登记 | **可验收（CI 已闭合）** |
| B1 | PARTIAL 选择集 A→B 宿主驱动设备验收 | `8df089e`（R16 证据 head `50c9d88`） | test/partial-selection-ab-2026-11-08 / PR#57 | 仅测试+probe（8 提交，app/src/main 零改动）：PhaseA（NONE→对话框→选择器→PARTIAL(A) 建立→地面真值+哨兵+组/断点持久化+deck/查看器/月份全链）+ PhaseB（跨进程读回→**系统设置页重选**→像素驱动→确认回读→地面真值恰={B1,B2}→一致性+retention+哨兵） | **R16 run 42500080220 三 job 全绿**（双 API）；final head run 42501864524 build/API37 绿+API36 瞬态抖动（登记口径） | 审计派出（精简四核验面） | **等待审计** |
| #59收尾 | 队尾失败/撤销补批次对账 | `23b8711`（时序修正 `awaitTargetCount` 先于查询计数） | fix/t10-merge-archive-stats-refresh-2026-11-08 | AlbumManager 两出口补 reconcileAlbumsIfDirty | 402/0；CI 42507084347 全绿 | 交叉复审 PASS 无P1/P2（子代理1，双重复跑） | **可验收** |
| #60收尾 | 等待者共享不可信结果+所有权后复查缓存 | `96997c1`（就绪信号替换 sleep `96997c1`） | fix/t11b-single-flight-bounded-cache-2026-11-08 | getOrDetect 携结果 deferred+所有权后复查+两个测试钩子 | 定点 68 用例绿；CI 42507087643 全绿 | 主代理复审可合并 | **可验收** |
| #57收尾 | 跨月夹具月份判别面+API36 折叠区导航保障 | `3492728` | test/partial-selection-ab-2026-11-08 | PhaseA/B 月份断言+tapAnyScrollableToMarker | compile 级；CI 42507090976 全绿 | 主代理复审可合并（audit-B1-closing.md） | **可验收** |
| #65收尾→PR66 | 生产授权回调消费路径 | `fdc03967`（阻塞登记版） | test/prod-auth-callback-2026-11-09 / PR#66（堆叠 #65） | ProdAuthCallbackDeviceTest 2 用例（真实入口）——**两轮 CI 双 API 红后按预算转阻塞**：已从 probe 类列表摘除（测试文件+阻塞 KDoc 保留） | 轮2 run 42653961146：**入口链全通**（回收站→条目→彻底删除→应用对话框→in-flight→系统对话框出现）；卡点=拒绝键文案 Deny 缺失+BACK 兜底 0.1s 迟到 | 复审=范围/状态面核验过（audit-PR66-closing.md）；设备面=阻塞登记 | **阻塞（本轮定向修正中：补实际观察 Deny 文案+等待窗口关闭/应用恢复前台+就绪核验；API37 他人媒体连续两轮红=根因未知，如实登记）** |
| **#67 汇总** | 13 组件整合分支（集成 SHA e3b005f） | `e3b005f` | integration/night-2026-11-08 / PR#67 | 按依赖序合并 13 组件；probe 冲突并集解 | **集成门禁 42643510385 三 job 全绿**（全量 430/0+Release/R8+API36/37）；本地整合全量 430/0 | 整合差异独立复审：整合有效无需修复（audit-integration.md） | **可验收（待人工合并）** |
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

## 运行中 CI（收尾时点：无运行中）
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
