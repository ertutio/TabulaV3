# 持续开发进度与续接记录

本文件记录事实和下一动作；执行规则见[总计划](DEVELOPMENT_ROADMAP.md)，验收规格见[验收约定](../docs/acceptance.md)。每次提交保存点、开 PR、测试结束或被迫中断时更新已有行，不另建一套每轮报告。

## 当前游标

| 字段 | 当前值 |
| --- | --- |
| 总任务状态 | `ready`：计划已准备；后续实现由使用者启动 |
| 已接受代码基线 | `master@8be46e424f6c39ba837f969d876b4d5d53003f97`（#11 merge commit，2026-10-28） |
| 当前检查点 | C0：安全与一致性的当前差额 |
| 当前代码变更 | 本计划 PR 仅有文档，未启动新的产品实现 |
| next_action | 核对实际 master/工作分支与本表；定位 AlbumViewScreen 首次查询失败、列表为空时的错误/重试显示路径，取得可区分当前行为的证据，再局部修复和验收。若当前源码已经满足，记录对应证据，继续 C0 其他差额或 C1，保留原有实现。 |
| 本动作通过条件 | 初次失败可见错误与重试；重试成功显示真实结果；切图集/取消不被旧结果覆盖。证据关联 T05 / TC23–TC26 范围，逐条登记实际断言。 |
| 全局阻塞 | 当前无已确认阻断全部实现的条件；CI/测量/真机权限以执行环境及当次用户授权为准 |

以上 master 是代码基线记录，不要求恢复到旧提交。计划 PR 合并或其他工作使 master 前进后，先比较受影响输入并更新游标，避免把已合并工作回退。

## 已接受的证据

- [#11](https://github.com/ertutio/TabulaV3/pull/11) 已合并；#10 已作为被覆盖 PR 关闭，未单独合并。merge 的父提交是原 `d2038b8e0dc8c99fbdf2c754e88dec0a8078a725` 与测试提交 `b2facebf5e6137ff0c15d1a1eedf1fd0bb5bc1cf`。
- [run 40680146410](https://github.com/ertutio/TabulaV3/actions/runs/40680146410) 在该测试提交上成功：build、API36、API37 三个 job 成功；每台 VolumeDeletionDeviceTest 3 项、CloudCleanupUiTest 2 项、CloudRestoreUiTest 2 项，共 14 项实际设备测试通过。
- 每台登记 17 项合成媒体，清理前 15 项存在、2 项已由正文删除，清理后 17 项均确认不存在；集合外哨兵保护断言通过并独立清理。每台三个测试窗口的 `issues=[]`，系统进程身份稳定，三条 volume 观测的 external_primary 为 mounted/recent，actuallyUnmounted 为空。
- API37 使用 cmdline-tools 22.0、AVD target `android-37.0`、image revision 7；实际启动 emulator 37.2.12.0。API36 使用原工具 12.0、target `android-36`、image revision 7。保留环境身份再讨论跨环境复用。
- 本轮 `testReleaseUnitTest` 为 `FROM-CACHE`，没有本轮新执行/计数。此前 run [40650966680](https://github.com/ertutio/TabulaV3/actions/runs/40650966680) 的 XML 汇总为 176 tests、0 failures/errors/skips；这不是未来固定测试数，也不能据此把全部 TC 判为 PASS。

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

## 就绪与待办队列

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

## 观察与证据缺口

| 编号 | 类型与当前事实 | 最小下一步 | 影响 |
| --- | --- | --- | --- |
| O1 | 相册首次加载异常时，空列表分支可能不显示错误/重试；已静态观察，未完成该入口设备验证。 | 先复现 UI 状态，覆盖失败→重试成功及切换取消，再修有证据的缺口。 | C0/T05；不重新实现详情完整模型。 |
| O2 | 先前 API37 createBucket 占位删除出现权限错误，直接方法/调用和权限声明在旧基线与当时头相同；未在基线模拟器复现。 | 合成媒体最小复现、记录 URI/权限与身份，必要时对照基线后决定局部修复。 | 旧路径兼容观察，不冒称新增回归或已解决。 |
| O3 | 真实多卷、其他应用媒体、进程死亡/取消/持久失败的所有组级规格尚未逐例证实。 | 明确 fixture/设备/授权，先做可控测试，真实设备缺口单独列明。 | 自有媒体探针通过不能替代这些要求。 |
| O4 | 性能 PR6 仍 draft，旧 paused run 不能证明有效基线；没有冻结性能数字。 | 验证工具成功/失败 fixture，获实际授权后按版本协议测量。 | C5/C6 的性能结论门槛。 |

## 保存点格式

继续在此表追加或更新当前子行；详细日志放现有 CI artifact/测试报告，避免把全部日志塞进上下文。

| 子项 / 状态 | 行为与改动提交 | 分支 / PR / 父 PR | 验收断言与实际证据 | 未完成 / 阻塞 / next_action |
| --- | --- | --- | --- | --- |
| 计划初始化 / ready | 仅重构执行文档，代码基线见上 | 由本计划 PR 的真实链接及 SHA 补录，不预写不存在的编号 | 文档链接、原 T/TC 范围、连续执行循环与依赖静态核对 | 启动实现后更新游标，执行 C0 的具体动作。 |

每条验证至少记录：case ID 与断言、源码 SHA/相关输入、命令或 run URL、环境/fixture、实际结果（含失败/跳过/缓存）、报告位置。每条阻塞记录：所缺条件、累计失败与假设、已试结果、需要谁做什么、解除条件、可继续的独立项。收到评审意见后更新原子项，不另起一个无法对账的任务名。
