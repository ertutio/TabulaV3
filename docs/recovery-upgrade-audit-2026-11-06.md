# 数据恢复与升级兼容盘点（任务3，2026-11-06）

盘点范围：master `502fce2`（PR41 合并后）。每行给出场景、既有覆盖（真实测试名，可追溯）、结论与剩余缺口。本轮交付**不新增生产改动**；缺口要么已有登记，要么列在文末待办。

## 一、进程重建

| 场景 | 既有覆盖 | 结论 |
| --- | --- | --- |
| 归档 action 排队后进程重建：不补发复制、卡片可恢复 | `ArchiveCommitEvidenceTest.queueRebuildDropsPendingActionWithoutSideEffects`（重建后 commit 旧 id → NOT_CLAIMED、零副作用） | 已覆盖 |
| 同一 action 并发提交/撤销交错 | `ArchiveCommitEvidenceTest.concurrentCommits…`（5 轮）/`cancelThenCommit…`/`commitOfUnknownActionId` | 已覆盖 |
| 回收站持久化：损坏/保存失败/原子读/并发更新/部分写 | `RecycleBinManagerTest`（corrupt/failedSave/atomicRead/journal/concurrentUpdates/failedPartialWrite 系列） | 已覆盖；`atomicReadRestoresLegacyBackup` 在 Windows 本机为已知环境失败（CI Linux 绿） |

剩余缺口：真实断电（掉电时序）无合成可测等价物，维持登记；`RecycleBinManagerTest` Windows 环境失败族见第三节注。

## 二、旧记录升级与迁移

| 场景 | 既有覆盖 | 结论 |
| --- | --- | --- |
| 回收站序列化：orientation 新旧 JSON、批量单事务 | `RecycleBinSerializationTest` | 已覆盖 |
| 清理组旧引用迁移：legacy 组 id（无卷前缀）升级为身份键、保留断点保存时间 | `GroupIdentityCleanupPersistenceTest`（seedLegacyGroup 写入 legacy id 后经真实引擎重建断言）；`fix/group-identity-cleanup-persistence`（PR 已合并） | 已覆盖 |
| 清理成员旧裸 id 升级：确认前必须先完成唯一身份升级 | `AlbumCleanupForegroundRefreshTest.legacyBareMembersRequireUniqueIdentityUpgradeBeforeAcknowledgement` | 已覆盖 |
| 同 ID 改内容旧哈希失效（ver_ 版本键）、旧格式保守重算 | `ImageHashCacheVersionTest` 6 项 + `SimilarGroupDetectorVersionTest` 2 项 | 已覆盖 |

剩余缺口：无新增。C1 消费者簇迁移台账（AppPreferences 冷却等按名合并为显式产品语义）维持既有登记。

## 三、回收站

| 场景 | 既有覆盖 | 结论 |
| --- | --- | --- |
| 删除恢复：recovery 不重复删、UNKNOWN 与现存分离、取消留 journal | `RecycleBinDeletionTest` 8 项 | 已覆盖 |
| 入口统一与 2001 项生产组合 | `MediaDeletionBatchTest` 7 项 + PR31 的 2001 项生产组合断言 | 已覆盖 |

**Windows 本机环境失败（2026-11-06 实证，非本轮引入）：** 干净 master worktree（502fce2）复现 5 失败——4×`AlbumCleanupForegroundRefreshTest`（`RecycleBinPersistenceException: 回收站记录未能读取或保存`，RecycleBinManager.kt:82 事务路径）+ 1×`RecycleBinManagerTest.atomicReadRestoresLegacyBackup`；同一失败族（回收站持久化事务），CI Linux 历史绿。判定为 Windows 本地环境差异（与登记在案的已知环境项同族），**不作为产品回归**；全量门禁以 CI Linux 为权威。

## 四、清理断点恢复与部分权限

| 场景 | 既有覆盖 | 结论 |
| --- | --- | --- |
| 断点行外部编辑后恢复：同对象替换最新行 | `AlbumCleanupForegroundRefreshTest.checkpointRestoreServesUpdatedRowsAfterExternalEdit` | 已覆盖 |
| 组成变化放弃断点、重开干净 | 同上套件（U9 轮 6 项） | 已覆盖 |
| 整组缺席（权限收窄）→ 持有不标记、访问恢复后续接 | `AlbumCleanupForegroundRefreshTest.fullyVanishedGroupIsHeldNotMarkedAndResumesAfterAccessRestore`（C4/T07 任务 A） | 已覆盖 |
| 月快照被空快照清空后按最新池重建续接 | DeckScreen 恢复通道（任务 A 配套；接线层由审计推演覆盖） | 覆盖（接线无单测基建，如实登记） |
| 部分权限期间退出、重开、恢复权限不丢进度 | 启动 onCreate 级别检查（既有路径 + 单测映射）+ 上述持有语义 | 已覆盖（单测级）；真实撤销路径 OS 强停杀 instrumentation，设备用例不可执行（实证登记），手工验收维持 blocked |

剩余缺口：PARTIAL 真实选中集合变化无法经 pm 夹具化（级别映射单测钉住，实机待验）；无新增缺口。

## 五、真实设备项（维持 blocked，不弱化）

- 真实跨卷移动/复制、热插拔卷变化：无设备条件，维持待实机验证。
- 其他应用媒体授权（SHARE 接收端、外部写入）：维持待实机验证。
- OEM 行为（ColorOS 等对 MediaStore update/IS_PENDING 的差异）：维持待实机验证。
- 覆盖安装/旧数据：验证步骤见 [upgrade-verification-checklist.md](upgrade-verification-checklist.md)，待人工执行。

## 六、本轮结论

任务3 的合成可测面在 master `502fce2` 已有完整覆盖，未发现**新的**可立约缺口；本轮产出为（a）上述可追溯盘点表、（b）覆盖安装验证步骤、（c）Windows 环境失败族的干净基线实证。后续代码级工作继续按台账最早 ready 项推进。


---

## 附：刷新验收四场景登记（2026-11-07，审计轮 4 后盘点）

| 场景 | 操作 | 预期 | 实际结果 | 证据 |
| --- | --- | --- | --- | --- |
| ① 外部编辑照片后回前台：实际刷新入口触发、采用最新元数据和内容版本 | 设备：API 34+ 模拟器/真机，运行 ForegroundPermissionUiTest 外部编辑用例（应用回前台前由测试进程经 IS_PENDING 发布链改写照片） | 回前台触发 mediaAccessRevision 整表重载；行对象携带新 DATE_MODIFIED；派生 cardMemoryCacheKey 变化 | **已关闭**——两层证据齐备：设备链（真实系统实跑，见 U14 登记 run 41706417934 系列）+ repository 层（PR49：ExternalEditMetadataAdoptionTest 采纳新元数据/键变化/排序） | 设备用例 CI 实跑记录 + PR49 合并（master 92fd541） |
| ② 卡片与查看器显示更新后的像素（前后可明确区分的图片） | 设备：注入两版可区分图片，编辑后回前台，对比卡片/查看器渲染位图（截图 hash 三角验证） | 显示层重新解码，旧像素不残留 | **blocked**——缺设备 UI 自动化像素对比设施（应用无 Compose 测试基建；in-process 探针无法在编辑后持有两版位图对比）；键链路已由单测钉住（PR39 viewer_hd_ 版本键 + 任务 C ImageCard uri 键：内容版本变化必然派生新请求） | PR39/任务 C 单测 + 本登记；执行条件：API 34+ 设备 + am instrument 扩展设备用例（截图对比步骤已写明） |
| ③ PARTIAL 授权集合变化后可见集合与实际授权一致 | 设备：真机手工操作——设置里撤销/重授部分照片选择，回应用核对可见集合 | 可见集合=实际授权集合（多选/少选都如实） | **blocked（人工验收）**——pm 不可夹具化 PARTIAL 选中集合（OS 无 shell 等价操作）；撤销授权会强停应用进程（in-process instrumentation 随宿主死亡，实证登记） | 逻辑层已覆盖：级别映射单测 + ForegroundRefreshSyncTest 快照竞争；人工验收步骤：真机 revoke→重开应用→逐张核对可见集合→录屏存证 |
| ④ 恢复 FULL 后清理组、月份列表、断点继续处理，暂不可见成员保留正确状态 | 逻辑层单测（已执行）+ 设备（与③同批手工验收） | 缺席组不标记完成（持有）、月快照重建、断点续接、恢复 FULL 后继续 | **逻辑层已关闭**——任务 A 持有语义 7/7（fullyVanishedGroupIsHeldNotMarkedAndResumesAfterAccessRestore：FULL→PARTIAL→重建引擎→FULL）+ U9 套件（断点编辑恢复/空快照完成通道）；设备端与③同批人工验收 | AlbumCleanupForegroundRefreshTest（CI 绿）+ 本登记 |


### 设施盘点与设备验收条件（2026-11-07 第二次盘点）

**现有设施**：CI 双模拟器（API 36/37，KVM，build-volume-confirmation 工作流）可运行 androidTest；`ForegroundPermissionUiTest` 已具备：UiAutomation `shell()`（可执行 pm/am）、MediaStore 种子照片 + IS_PENDING 发布链（外部编辑模拟）、`am start` 回前台驱动。**in-process 探针边界**：撤销授权强停应用进程（instrumentation 随宿主死亡，实证）。

| 场景 | 条件具备度 | 下一步操作（可执行） |
| --- | --- | --- |
| ② 像素级刷新 | **条件具备（待立项）** | 最小可执行验证 = 扩展 ForegroundPermissionUiTest：两版纯色可区分图片（红→蓝）经 IS_PENDING 编辑链 → 回前台 → `UiDevice.takeScreenshot` 截卡片/查看器固定区域 → 前后 hash 必须不同且 after 匹配新颜色。风险：模拟器渲染确定性需两次试跑验证；预计 1 个设备用例 + CI 两轮 |
| ③ PARTIAL 集合变化 | **自动化 blocked** | 选择集合仅能经系统照片选择器 UI 设置（无 shell/程序化入口）；撤销强停杀 instrumentation。下一步 = 真机人工验收（revoke→重开→逐张核对→录屏存证），或立项宿主端 adb 黑盒驱动（新设施，超出当前范围） |
| ④ FULL 恢复续接 | **部分可自动化（待立项）** | 两阶段方案：阶段 1 instrumentation 建立清理断点 → 结束；宿主 shell `pm revoke`（应用被杀）→ `pm grant` FULL → `am start` 重开；阶段 2 新 instrumentation 读取持久化断点/清理组状态断言续接。条件具备（CI 模拟器 + shell 已可用），预计 1 个两阶段用例 |
| ① 外部编辑回前台 | 已关闭 | 设备链实跑记录见 U14；repository 层见 PR49 |


### 像素刷新缺陷登记（2026-11-07，PR51 两次 CI 迭代实证）

**缺陷**：外部编辑（同一媒体身份，IS_PENDING 发布链改写内容）+ 回前台后，deck 卡片**先短暂显示新内容（blue 实证）随后翻回编辑前的旧位图（255,0,0 纯红实证）**，且点按卡片未呈现查看器（失败截图为 deck 界面）。两次 CI 迭代（42367556385 API36 / 42369922277 双 API）复现；冷却清除（相似组+抽取时间戳两 prefs 文件清空）不能消除——排除冷却假设。

**证据**（CI 工件 Tabula-VolumeFix-API36/37，PixelRefreshUiTest）：失败时刻截图 pixel-refresh-viewer-B.png（deck 界面+红卡）；logcat 颜色采样全序列（card-A 红/viewer-A 红/card-B 蓝/viewer-B 红×21）；请求键修复前后两键（card_<uri>_<版本毫秒>）；媒体版本 1794051040000→1794051046000（变化实证）。测试代码保留失败状态（不弱化断言不跳过），分支 CI 红=缺陷持续实证。

**待定位假设**（下一诊断工作包）：①前台重载后 deck 行对象被引擎缓存陈旧行替换（syncRowsToSnapshot 未覆盖该时序）；②查看器状态被空池同步关闭（U9 空池语义 × 本场景）。区分手段：诊断版测试在失败时刻 dump 辅助树与各阶段截图。

**两阶段 FULL 恢复验证（工作包 2）研究结论**：机械可行性成立——instrumentation 各阶段独立运行至完成（不跨进程死亡），宿主侧在两阶段间执行 `pm revoke`（应用被系统强停，实证行为）→ `pm grant` → `am start`；阶段 1 以引擎 API（真实 prefs + 自建 MediaStore 媒体）建立清理组/断点并把预期状态写入 files/（run-as 可读，跨进程死亡持久）；阶段 2 为新 instrumentation，重建引擎断言组/月/断点续接与暂不可见成员持有。实施载体 = run-cloud-ui-probe.sh 增设两阶段块（阶段间不 pm clear）。预计 2 设备用例 + 工作流接线 + CI 两轮；因本轮预算用于像素缺陷，列为下一就绪工作包。


### 像素缺陷的点击机制排查（2026-11-07 第五轮）

**轮 4 时间线实证**：tap 大卡中心未打开查看器（39 轮询 open=false，双 API）。**轮 5**：改无障碍原生 ACTION_CLICK（节点+可点击祖先链，全部失败回退坐标 tap）——logcat 实证 `coordinate tap fallback`（节点与祖先均不可点击）且查看器仍未打开。**两次针对性机制失败，按两次修正规则暂停**。结论倾向：deck 浏览态卡片的 tap→查看器路径对合成注入事件不可达（需真实触摸特征或存在输入消费层），或 onCardClick 接线在该模式下有额外条件。**下一步**：诊断版测试在 onCardClick 入口加日志（生产最小日志）确认回调是否被调；或测试内经 AppPreferences 切换卡片样式（摸牌/切牌）后重试；或经图库模式（AlbumViewScreen 的 SwipeableViewerOverlay）走查看器路径。已登记为独立诊断工作包。