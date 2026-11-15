# 性能前收口报告（Unit D 交付，2026-11-15）

交付对象：用户。用途：确认正确性/恢复/设备验收收口结果，**性能阶段启动由用户确认**。
基线：master `60aa6e03`（CI run 43692861712 三 job 全绿实证）。执行依据：2026-11-15 收口指令（§一状态核对 → §二 PR79 收口 → §三旧扫描晚返回 → §四盘点 → §五纪律）。

## 一、已关闭断言（本轮收口新增）

| 单元 | 断言 | 证据 |
| --- | --- | --- |
| §二 Unit A：B2MR 表述收口 | 测试名/注释/日志/PR 正文全部收敛到实际已验证行为（终态口径：真实 PARTIAL→FULL 刷新链同进程最终向推荐批次提供完整 47 项集合）；超证据表述零残留（复审 grep 实证） | d03dc08+52c9de6；review-B2MR-closure.md **PASS**；CI 43690226527 双 API 绿+工件时序链实核 |
| §二 Unit A：批次日志动作边界 | 消费池断言只认动作边界后新事件；"仅有动作前正确日志"红灯；fail-closed；47 集合相等保留 | REFETCH_BOUNDARY_MARKER（边界前写入+append 序单调+墙钟/pid 过滤否决理由登记） |
| §三 Unit B：旧扫描晚返回真实接线级 | 五步显式握手：旧扫描停在可控位（parked rows=2）→真实权限变化触发新扫描→新集合提交+消费池承接（边界后 47 池集）→释放旧扫描确认返回（resumed rows=2）→旧集合不覆盖+取消不误报失败+清理状态面读回原样；全程 pid 恒等 | PR #81 61a26f0；CI 43694969019 三 job 绿双 API OK(1)；audit-stale-scan-pr81.md **PASS**（含审计者反编译 coroutines 1.10.2 独立验证） |
| §三 Unit B：接缝隔离 | 生产接缝恰 2 处（MediaLoadTestHook DEBUG 门 + MainActivity 单行位于 withContext(IO) 块内 initialize 后）；release 恒 no-op；app/src/test 零 diff | 同上审计逐行核实 |
| §三 Unit B：变异验证 | 变异-1（apply 包 NonCancellable）=no-op（协程 1.10.2 取消检查在 withContext(IO) 完成边界——变异后双 API 绿=no-op 预期，字节码三层证据）；**变异-2（有效形态）双 API 红=检出**，红灯位于早期 a11y 等待（非步骤5 目标断言，因果性如实登记"未证实"）；单测级检出实证（PR50 用例 FAILED，stale apply 确实执行） | run 43696079300（no-op 绿）/43695988574（检出红）；变异分支 mut/mut1 远端保留 |
| §四 R2：TC58 补面 | 临时读取失败→版本推进不服务→重算成功→版本键单向；失效/重算/清理循环不触碰冷却/组键/断点/永久完成面 | PR #80；本地 2/2 绿+相邻 8/8；审计 audit-TC58-pr80.md 两项最小修复已落实（单例隔离+错引更正） |
| §四 盘点更正 | T08 工具链 PR45/42/6 已 CLOSED 未合并（master 无测量实现）；T09 兼容矩阵文档在 master（A1 过期）；T03-DRAGSELECT/T06 设备面/T10/T11b 实现已关闭；T12 按"或"口径闭合（media3 声明未使用=R1 解散） | pre-perf-inventory.md（实树核对） |

## 二、待复核项（开放，含阻塞详情）

### TC58 相邻用例隔离缺口（PR #80）——**已按咨询定向修正闭合，轮 3 CI 绿**
- 轮 1/2 红（仅相邻既有用例 :177，Tc58 自身绿）→ 两次修正无效 → 独立咨询定案：Robolectric 4.12.2 filesDir 每方法新建+sandbox classloader 复用=static 跨类存活；Tc58 必要行为=被测生产入口 AlbumCleanupEngine 构造器内部 getInstance（测试侧不可绕过）；失败链路探针完整复刻；"最小三元组"系本地枚举序巧合。
- **定向修正 5d6331b（采纳咨询推荐 C，基建层）**：ResetAppSingletonsPlugin（TestEnvironmentLifecyclePlugin）+ META-INF/services 注册；本地双变体全量 864/864 绿（含原 :177 类 16/16）；**轮 3 CI run 43703642885 = success**（工件实核 Tc58 2/2 + :177 类 16/16）；shadow @Resetter 路线实验否定（登记勿回头）。
- **差分复审 PASS**（coord/review-TC58-plugin-delta.md：恰 2 新文件纯插入；KDoc 两点声明经 javap/反编译实证——AppPreferences 外层类含 private static volatile instance、onSetupApplicationState(offset 59) 先于 clearEnvironment(offset 71)；范围纪律仅 test 面；CI 43703642885 headSha 绑定 success）。
- 非阻塞跟进项（登记）：7 单例清单完整性守卫测试（fail-fast 防字段改名静默跳过）。

### 监控中（既有间歇，非本轮新增）
- FPF Phase3 API36 flake（43597856253 红/43609384927 绿同代码=间歇；框架层 dialog LazyColumn 语义丢失，留证 notes-B2.md:211）
- CloudCleanupUiTest API37 间歇（U18 夹具归属竞态，环境层）
- Robolectric 构件 CI 下载偶发即红（无缓存护城河，Unit B 登记）

## 三、awaiting_human（步骤+解除条件）

| 项 | 步骤 | 解除条件 |
| --- | --- | --- |
| T07 PARTIAL 真机人工 | 真机 revoke→重开→人工核对清理组/月列表/断点续接+录屏 | 真机在手 |
| 覆盖安装清单 A.4–A.5+B | 按 docs/upgrade-verification-checklist.md（plan 分支）人工执行 | 真机+签名材料 |
| fork 签名 secrets ×4 | 配置 TABULA_KEYSTORE_FILE/别名/密码 | 维护者配置 |
| T13 发布 | publish-release.yml | 用户授权 |
| 合并决策 | #79（52c9de6 审计闭环+CI 绿）、#81（audit PASS+CI 绿）、#80（咨询后定）、#77（A/A 参考） | 用户逐 PR 决定 |

## 四、blocked（解除条件）

真跨卷热拔插（多卷设备）｜真他人媒体-非 shell（多应用环境）｜断电注入（真机人工口径）｜OS 级进程死亡/OOM（真机专项）｜身份键迁移真实 kill（同上）——全部为 CI 合成设施不可注入的 OS/硬件条件，维持既有 blocked 簇登记。

## 五、性能阶段前置（启动需用户确认）

- **暂停项（按指令）**：测量/基线采集/优化实现/依赖升级（含 A/A 42.3% 归因）。
- **T08 工具链决策**：PR45/42/6 已 CLOSED（未合并）；#77 分支持有 A/A 参考与工具。性能阶段启动时需决定：重开工具链 PR 或以 #77 分支为测量基线。
- **本轮新增的可测量面**（性能阶段用）：B2MR 边界断言与 Unit B 握手为刷新链提供了确定性时序锚点；probe 预算实测余量登记。

## 六、证据索引

- 审计：audit-stale-scan-pr81.md（PASS）/ review-B2MR-closure.md（PASS）/ audit-TC58-pr80.md（需修复→两项落实）/ +20 份历史 audit-*.md
- 笔记：wg-Stale/docs/notes-stale-scan-2026-11-15.md（五步+run 总表+变异判定+遗留 5 条）；wg-B2/docs/notes-B2MR-2026-11-14.md §7–§11
- 盘点：coord/pre-perf-inventory.md；进度：plan 分支 .ua/DEVELOPMENT_PROGRESS.md（唯一游标+三轮执行登记）
- CI：43690226527（A）/43694969019（B 主）/43696079300+43695988574（B 变异）/43691596178+43693504295（TC58 轮1/2）/43703642885（TC58 轮3 绿=判据）/43692861712（master 全绿）
- 复审：review-B2MR-closure.md（A）/audit-stale-scan-pr81.md（B）/audit-TC58-pr80.md+review-TC58-plugin-delta.md（R2）——**本轮全部交付均经未参与实现的独立只读审计/复审**

---

# 收口第二轮增量（2026-11-15，用户指令执行记录）

## 合并（§一 授权，预期 head 校验）
- **#77 → master b787aca**（4e00669b 六项门：零生产面/触发面安全/绿 run 绑定/结果边界逐字保留）
- **#79 → master merge commit 52c9de60c3**（52c9de60 精确匹配；增量全在已审范围）
- **master 组合 CI 43713220665 三 job 全绿**；依赖分支更新（#81 变基 master MERGEABLE；#80 并 master aff9568）

## §二（PR #80）真实重算验收+插件加固
- 交付：4046245b（HashRecomputeDeviceTest+插件加固+守卫 4 例+probe 段）→ 轮 4 InvalidTestClassError（runBlocking 非 void）→ cf930d5 修复 → 轮 5 NPE（4 dHash 静默 Failed）→ 审计**需修复**两项 → aa2ee8f 插桩原因捕获 → **d64633a 位型夹具重设计**（A=0xAA/B=0x55/C=0xFF/D=0x00 按字节重复，两两距离∈{32,64}；契约测试 HashRecomputeFixtureContractTest 3/3 钉住；compress check+回读解码验证）→ 轮 7 run 43729643127 运行中
- 审计：coord/audit-recompute-pr80.md（缺陷 A/B 全部由修复落实）；runner 完整性 PASS（两分支 9 类共享 runner+依赖门+失败传播）

## §三（PR #81）目标变异证据——**完整交付**
- 差分复审 **PASS**（coord/review-stalescan-mutation-delta.md）
- 变异-3 run 43717346095 @ c3c813f：**双 API 红灯准确落在步骤 5b 目标断言 :939**（"旧结果覆盖新状态"），五步握手全程走完（armed→parked rows=2→47 池提交→released→size=2 覆盖→重取 2 行→:939 红），时间线逐锚核实
- **轮 D 43724140820 @ 69a9611 三 job 全绿——PR #81 合并判据达成**；StaleScan 段 4 轮 8/8 绿
- 三连红诊断：上游既有间歇段下午爆发（FPF Phase3 API36+API37 + CardLifecycle），非本轮引入；diag-closure2-burst-2026-11-15.md（门禁政策裁决项呈用户）

## 待用户裁决/确认
- 合并：#80（轮 7 绿后）、#81（判据已达成）、#77 已合并、#79 已合并
- 门禁政策：既有登记间歇段（FPF Phase3/CardLifecycle）probe 级有界重试——呈裁决
- 性能阶段启动确认
