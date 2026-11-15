# 性能前收口盘点（Unit C 交付，2026-11-15）

- 基线：master `60aa6e03a0ad2d862c3bf198ca13d7f8a11849ad`（含 #78；树核对 2026-11-15 01:30 实码）。
- 方法：A1 台账（43a8da1）逐行重核 master 实树（ls-tree/grep 实证）+ 2026-11-08 后合并单元（#61–#78）证据收编 + 本夜 #79 在飞状态标注。
- 状态口径：**已关闭（证据指针）** / **就绪可实施** / **awaiting_human（步骤+解除条件）** / **blocked（解除条件）** / **在飞（单元号）**。
- 用户指令：性能测量/基线/优化/依赖升级暂停；PR77 作为既有证据保留。

## 一、逐项差额表（用户 §四 重点面）

### A. 权限变化、恢复续接及旧结果隔离

| 断言 | 当前证据 | 剩余差额 | 下一步 |
| --- | --- | --- | --- |
| NONE→FULL 接线（会话内 grant→revision→新池） | B2PermissionRefreshDeviceTest 3 例（#79，audit-B2-followup PASS，CI 43514530630 绿） | #79 未合并 | 随 #79 合并闭合 |
| PARTIAL(B) 建立→FULL 升级→池承接（三阶段判据 1–10） | FPF Phase1/2/3Test（#79 分支；R16/判据链 CI 绿历史 + 43604993882 双 API 绿） | **FPF 三阶段不在 master（树核对）**——#72 合并的是 PartialSelection* 原语，FPF 套件仅 #79 | 随 #79 合并闭合；合并后 master CI 复核 |
| FULL 缺席→恢复续接（断点跨权限循环） | FullRecovery Phase1/Absence/Continuation/Readback（master ✓，CI 历史绿） | 无 | 已关闭 |
| 旧结果隔离（旧扫描晚返回不覆盖） | 单测级：MediaSnapshotLoadRaceTest（master ✓ 3 例+变异）；FPF Phase3 判据 10 受控竞争门（#79）；**真实接线级+定向变异：Unit B 在飞**（wg-Stale，握手五步+NonCancellable park+变异分支） | Unit B 交付+审计；变异 run 登记 | 在飞——收口后登记 run id/失败位置/API |
| 撤销权限→进程死亡→门控恢复 | B2PermissionRefresh 步骤2（宿主 revoke 强停→新进程门控+旧集合不重现，#79 CI 绿）；ForegroundPermissionUiTest KDoc 边界 | 无（应用侧路径+宿主编排已覆盖；OS 级真机撤销=blocked 口径维持） | 已关闭（合成可测面） |
| PARTIAL 真实选中集合变化真机人工 | 三态映射单测钉（MediaPermissionPolicyTest 9 例） | 真机人工验收 | **awaiting_human**：真机 revoke→重开→人工核对清理组/月列表/断点续接录屏；解除=真机在手 |

### B. T06 取消/动画退出/预载释放/晚回调

| 断言 | 当前证据 | 剩余差额 | 下一步 |
| --- | --- | --- | --- |
| ACTION_CANCEL 手势注入→恢复预载/操作（两模式） | CardLifecycleDeviceTest 场景1（#74 合并 master ✓，变异+审计 PASS） | 无 | 已关闭 |
| 动画中切模式→返回可操作（真 Settings 驱动） | CardLifecycleDeviceTest 场景2/3（同上） | 无 | 已关闭 |
| 预载生命周期（防抖窗口 pause/reset/dispose） | PreloadingManagerTest 19/19（master ✓） | 无 | 已关闭 |
| N1/N2 活性（取消后预载停摆/预取标志卡死） | #61 修复+回归（master ✓） | 无 | 已关闭 |
| 晚回调身份/旧请求晚到 | PR50+PR62+PR33/34 键簇（master ✓） | 无 | 已关闭 |
| T06-FOCUS4-CALLERPIN（调用方不回收借用位图） | 静态审读 24 处全自有（PR35 审计） | 无测试钉（无反例不造） | 维持登记（静态纪律） |
| T06-COMPOSE-E2E | PR34/35 证据边界+PixelRefresh 像素链 | 无单测基建（口径登记） | 维持登记 |

### C. 持久状态升级恢复

| 断言 | 当前证据 | 剩余差额 | 下一步 |
| --- | --- | --- | --- |
| 非空回收记录跨覆盖安装+真实 UI 恢复 | UpgradeRecycle*（#75 合并 master ✓，run 43375199019 head 绑定绿，audit-RA-pr75 PASS） | 无 | 已关闭 |
| 有效冷却记录跨升级（pickedAt/days 逐位） | UpgradeCooldown*（#76 合并 master ✓，自有绿轮 43375382346，audit-RB-pr76 PASS） | 无 | 已关闭 |
| 升级覆盖安装总链（组/断点/元数据/幂等/哨兵） | UpgradeOverlayDeviceTest（#73 合并 master ✓，audit-T13-pr73 PASS） | 无（自动化子集判据 5–10） | 已关闭 |
| 清理组/断点/已处理跨权限循环 | FullRecoveryContinuationTest + FPF Phase1/3（master+#79） | FPF 面随 #79 | 已关闭（master 面）/#79 待合并 |
| 身份键迁移跨进程死亡 | PR32 迁移幂等单测（master ✓） | 真实 kill 设备模拟 | **blocked**：OS 杀进程不可 in-process 注入；解除=真机专项（maintainer 意愿） |

### D. 包名/签名/Release/R8/覆盖安装

| 断言 | 当前证据 | 剩余差额 | 下一步 |
| --- | --- | --- | --- |
| 包名 com.tabula.v3 保持+fork 签名兼容 | 全部 CI 构建与既有 APK 系（build-volume-confirmation 每轮） | 无回归证据缺口 | 已关闭（持续门禁） |
| Release/R8+身份+体积 | CI 门禁每轮（build job testReleaseUnitTest+R8 警告 0 历史） | 无 | 已关闭（持续门禁） |
| T09 兼容矩阵证据文件 | docs/toolchain-compat-2026-11-08.md **在 master**（实树核对；A1 判"无证据文件"已过期——AGP 8.9.1 经 #53） | 无 | 已关闭（更正 A1） |
| 覆盖安装人工清单 A.4–A.5（更新渠道/鸣谢入口）+ B 手工语义抽查 | 清单文档在 plan 分支 docs/upgrade-verification-checklist.md；自动化子集已由 #73/#75/#76 覆盖 | 人工执行 | **awaiting_human**：按清单步骤真机执行（装 43a8da1→种子→覆盖装→人工核对）；解除=真机+签名材料 |
| fork 签名材料 | 仓库无 Actions secrets | 4 secrets 配置后解锁 fork-signed release 覆盖安装自动化 | **awaiting_human**：TABULA_KEYSTORE_FILE/别名/密码×2；解除=维护者配置 |
| T13 正式发布 | publish-release.yml 存在；release-3.3.4.md 为历史 | 未授权 | **awaiting_human**（用户授权门） |

### E. 条件受限簇（真多卷/他人媒体/进程死亡/断电/真机）

| 断言 | 当前证据 | 剩余差额 | 下一步 |
| --- | --- | --- | --- |
| 真跨卷热拔插 | 双 external_primary 挂载合成验证（PR25 链） | 实际卸载卷 | **blocked**（CI 模拟器无第二物理卷；解除=多卷设备/hosts 特殊环境） |
| 真实他人媒体授权 | OtherAppMediaAuthDeviceTest 4 例（shell 绑 owner 实证，master ✓） | 非 shell 真他人媒体 | **blocked**（AOSP isCallingPackageShell 边界；解除=多设备/多应用环境） |
| 断电（power loss） | 无专项 | 无设施 | **blocked**（CI 无断电注入；解除=真机人工口径，步骤可按覆盖安装清单裁剪） |
| 进程死亡 | 跨进程读回簇（#52/#73/#75/#76 force-stop 编排）已覆盖可合成面 | OS 级 OOM-kill 不可注入 | **blocked**（同上口径） |
| CloudCleanupUiTest API37 间歇 | U18 根因=夹具归属解析竞态（环境层）；迭代间 OK(8)↔失败 | 稳定性观察 | 监控中（随每轮 CI） |
| FPF Phase3 API36 flake | 43597856253 红/43604993882 绿=间歇；框架层 dialog LazyColumn 语义丢失留证 | 复现即诊断 | 监控中（随每轮 CI） |

## 二、组级状态总表（T01–T13，基线 60aa6e03）

| 组 | 状态 | 一句话依据（与 A1 差异标注） |
| --- | --- | --- |
| T01 / TC01–TC07 | 已覆盖（合成面）+ blocked 簇 | 不变；TC57 并发 #54 关闭、TC58 见下行 |
| T02 / TC08–TC12 | 已覆盖（合成面）+ blocked 簇 | 不变 |
| T03 / TC13–TC16 | 已覆盖（簇级） | **T03-DRAGSELECT-ITEMBOUNDS 已关闭**（pruneBoundsNotIn 在 master 实树核对）——A1"待验证"过期 |
| T04 / TC17–TC22 | 已覆盖（矩阵）+ blocked | 不变 |
| T05 / TC23–TC26 | 已覆盖 | 不变 |
| T06 / TC27–TC31 | 已关闭（设备面主体） | **CardLifecycle 3 例+#78 修复已合并**——A1 时尚未交付 |
| T07 / TC32–TC35 | 已关闭（主体）+ awaiting_human(PARTIAL 真机) | **B2 全链=#79 在飞**；ProdAuthCallback 2 例已合并 |
| T08 / TC36–TC42 | **工具链不在 master**（PR45/42/6 已 CLOSED 未合并）；#77 持有 A/A 参考 | **重大更正**：A1"在开放 PR"→现"PR 已 closed"；性能阶段需工具链重建决策 |
| T09 / TC43–TC44 | 已覆盖 | **兼容矩阵文档已在 master**（toolchain-compat）；覆盖安装自动化子集已闭环（#73/75/76） |
| T10 / TC45–TC47 | 已关闭（实现+正确性夹具）；收益数字=性能阶段 | **#59/#60/#55/#56 已合并**——A1"缺实现"过期；StorageStatsManager.performScan 全量重扫为相邻登记观察（非 T10 归档链面，映射问题登记） |
| T11 / TC48–TC51 | T11a/b 已关闭（实现+夹具）；收益=性能阶段 | ImageFeatureCacheStore single-flight+Lru 实树核对 |
| T12 / TC52–TC53 | **已关闭**（EXIF+取消/并发=「或」口径全命中；media3 未使用=F1 已登记 P4 清理候选；平台 MediaPlayer 生命周期=无 Compose 单测基建审读口径） | ~~唯一可实施剩余~~ 已并入上面 R1 解散结论 |
| T13 / TC54–TC55 | 自动化子集已关闭；人工清单/发布/签名=awaiting_human | 见 D 节 |

## 三、就绪可实施清单（性能前，按价值排序）

| # | 工作项 | 断言 | 依据 | 规模 |
| --- | --- | --- | --- | --- |
| R1 | ~~T12-MEDIA3 定点回归~~ **已解散（2026-11-15 02:40 实核）**：media3 1.5.1 仅 catalog 声明、build.gradle.kts 无引用、main 零 import（F1 报告既已登记"未使用 P4 清理候选"）；实际播放依赖=平台 android.media.MediaPlayer（MotionPhotoPlayer.kt:36）；其 DisposableEffect 释放语义无 Compose 单测基建（PR34/35 同款审读边界），无缺陷证据不造接缝 | T12 按"或"口径闭合：EXIF（#63）✓ + 协程取消/并发（PR40/PR50/PR63）✓；动画生命周期=审读口径登记 | 无可实施项（media3 清理=依赖面暂停期不动，登记 P4） | — |
| R2 | TC58 补面：临时读取失败→恢复后旧哈希失效按有界策略重算+无关冷却/进度保留 | 失败注入→恢复→重算正确 | TC58 规格；PR27 版本键+#60 失效已有部分证据，缺"临时读取失败恢复"半边 | 小-中（PR27 测试基建复用） |
| R3 | Unit B 收尾（在飞） | 见 A 节 | wg-Stale | 在飞 |
| R4 | #79 合并后 master CI 复核登记 | 合并态三 job 绿 | 维护者合并动作后 | 小 |

## 四、awaiting_human / blocked 汇总（含解除条件）

- **awaiting_human**：①T07 PARTIAL 真机人工（真机+录屏步骤见 A 节）②覆盖安装清单 A.4–A.5+B（真机+清单文档）③fork 签名 secrets×4（维护者配置）④T13 发布授权 ⑤#79/#77 合并决策（维护者）。
- **blocked**：真跨卷热拔插、真他人媒体（非 shell）、断电注入、OS 级进程死亡（OOM/kill）、身份键迁移真实 kill——均附解除条件（多卷设备/多应用环境/真机专项/维护者意愿）。
- **暂停（用户指令）**：T08 测量/基线/优化/依赖升级（含 A/A 复测、42.3% 归因）——解除=用户确认性能阶段启动。

## 五、证据文件索引

- 本盘点：coord/pre-perf-inventory.md（同步 plan 分支）
- A1 台账（历史基线 43a8da1）：coord/ledger-A-remaining-assertions.md（本文件为对其的 60aa6e03 重核与收编）
- 审计链：coord/audit-*.md ×21；执行笔记 coord/notes-*.md；plan 分支 .ua/DEVELOPMENT_PROGRESS.md（唯一游标）
- 在飞：Unit A（#79 表述+边界，CI 43690226527+复审）、Unit B（wg-Stale）
