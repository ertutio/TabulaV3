# StaleScanLateReturn 旧扫描晚返回设备验收 notes（2026-11-15，wg-Stale 单文件单写者，Unit B）

任务（用户收口指令 §三）：设备级验收"真实 MainActivity 权限变化 → mediaAccessRevision →
加载 effect → 生产快照提交 → 界面消费"链条中，**旧扫描晚返回不得覆盖新集合、取消不得被
误呈现为加载失败**——经显式握手在生产链内建立五步顺序。覆盖层级如实登记：本单元 =
**真实接线级**（竞态构造点在生产链内 debug 接缝）；PR50 MediaSnapshotLoadRaceTest =
**单测级**（参数注入），两级口径各自保留，不互相冒充。

## 基线与交付

- 工作树 `wg-Stale`，分支 **test/stale-scan-2026-11-15**（基于 PR #79 head **d03dc08**——
  FPF/PartialSelection 原语在该分支，master 尚无）。
- PR：**#81**（base=test/b2-to-master-2026-11-14，标题含 Unit B）。
- 交付提交：
  - **e186a78**（test: stale-scan late-return device acceptance on real wiring）：
    - 新 main 源集文件 `app/src/main/java/com/tabula/v3/MediaLoadTestHook.kt`
      （生产接缝 1/2：debug 门一次性暂停钩子）；
    - `MainActivity.kt` 唯一一行（生产接缝 2/2）：`loadAndApplyMediaSnapshot` 的
      `withContext(Dispatchers.IO)` 块内、`albumManager.initialize(...)` 之后、
      `Pair(projected, ...)` 之前插入 `MediaLoadTestHook.parkBeforeCommit(projected.allImages.size)`；
    - 新 androidTest 套件 `app/src/androidTest/java/com/tabula/v3/StaleScanLateReturnDeviceTest.kt`
      （单方法五步握手，预期 1 例）；
    - probe 新段 `tools/run-cloud-ui-probe.sh` "StaleScanLateReturn old-scan late-return
      device verification"（b2mr 段后追加；instrumentation timeout 600s；OK(1) 精确门 +
      失败门 + 依赖门不适用（单 instrumentation）+ 按目录兜底清理，b2mr 同款机械解）；
    - 预算同步：`run-volume-device-probe.py` wrapper **1500s→1800s** +
      `build-volume-confirmation.yml` device job **40→50 分钟**（登记数字见下节）。
  - **61a26f0**（test: fix stale-scan step5 observation plane + deterministic overwrite
    detector）：设备执行前自查修正（失败→修正链见下节）。
- 编译验证：`:app:compileDebugAndroidTestKotlin --no-build-cache` ✅（e186a78 与 61a26f0
  各跑一次）；`:app:assembleRelease -x lint` ✅（release 变体无 androidTest compile task，
  接缝 release 编译由 assembleRelease 承担；本地无 release androidTest 编译任务可跑）；
  本地 `:app:testReleaseUnitTest --tests MediaSnapshotLoadRaceTest` ✅（接缝就位后 PR50
  基线绿，release 变体 = BuildConfig.DEBUG=false，接缝零行为）。
- 不动 app/src/test（主交付分支）、不动 PR79 分支与 wg-B2/wg-Docs。

## 接缝设计要点（MediaLoadTestHook）

- 隔离口径 = `BuildConfig.DEBUG` 门（GenieDeleteAnimTestHook 先例 GenieEffect.kt T06 场景 c
  同款：release 全入口直接返回，生产行为零变化）。
- 一次性门：`armOnce()` 武装 → 首个到达 park 的扫描**消费**武装态并暂停（后续扫描直通）
  → `release()` 释放。同一时刻至多一个扫描被暂停。
- **关键语义**：park 以 `withContext(NonCancellable)` 承载等待——模拟"读取不可中断"晚返回
  形态：键控重启的取消被推迟；释放后暂停点返回，由**外层 withContext(Dispatchers.IO) 的
  恢复期取消检查**生效。机制（kotlinx-coroutines 1.10.2 字节码实证，DispatchedTask.run
  CancellableMode 分支，偏移 124-252）：IO 块完成后对已取消调用方按 CancellableMode
  resumption **丢弃成功结果改抛 CancellationException** → apply/onError 均不为被取消扫描
  执行（catch(CancellationException) 原样重抛）。park 若放 IO 块外（设计已否决）：
  恢复后与 apply 之间无取消检查点，守卫被接缝自身绕过。

## probe 预算登记（数字与理由）

- run 43609384927（2026-11-14，双 API 全绿）实测：probe 子进程墙钟 API36=1150.4s /
  API37=1174.2s（预算 1500s，余量 326-350s）；b2mr 段墙钟 ≈150s（instrumentation 143s）。
- 本段预估 ≈200-290s（PARTIAL 选择器建立 ≈ B2MR 量级 + park/release/观察窗轮询 +
  两次 Settings 重选）→ API37 投影 ≈1380-1470s，对 1500s 预算余量 <120s，逼近预算 →
  **wrapper 1500s→1800s（余量 ≈330s+）**；device job 墙钟 ≈21.6 分钟 + 本段 ≈5 分钟 ≈
  27 分钟 < 50 → **job timeout 40→50 分钟同步**。

## 五步握手记录（StaleScanLateReturnDeviceTest，单方法，全程同进程 pidof 恒等）

1. **旧扫描进入读取并停在可控位**：真实系统机制建立 PARTIAL(B)（7 格像素驱动只选
   {1,2}=b1,b2 + 哨兵 s1；FPF Phase2/B2MR 步骤1 同款）→ deck 就绪（初始加载已过接缝，
   门未武装）→ `armOnce()`（同进程直调）→ Home→am start（级别不变+foregroundRefresh →
   revision++）→ 轮询断言 `parkedRows()==2`（=PARTIAL 选中集合规模：暂停者 = 旧选择集合
   时代扫描且其读取已返回）。
2. **真实权限变化+前台检查触发新 revision/扫描**：会话内 shell 注入 40 填充
   （MediaStore=47、应用可见仍={b1,b2}，无 ContentObserver）→ `pm grant READ_MEDIA_IMAGES`
   （grant 不杀进程 #52 口径）→ Home→am start（级别变化分支 revision++；被暂停旧扫描被
   取消，取消被 NonCancellable 推迟至释放后）。
3. **新扫描提交，界面+消费池呈现新集合**：集合面截止轮询=47 名全集（哨兵 s1 存在性、
   无重复）+ deck 存活行同步观察窗（无门控/白屏）+ 真实 Settings 推荐模式现值重选 →
   `STALE_BOUNDARY_MARKER`（"stale-refetch-boundary:"）在 tap 前写入 → 边界后
   batch-site-4 批次日志行 id 集 == 47 池集（fail-closed）→ BACK 回 deck。
4. **释放旧扫描，确认旧读取确实返回**（deck 前景执行）：`release()` → 轮询 `isReleased()`
   且 logcat（MediaLoadTestHook tag）出现 "resumed rows=2"（旧读取结果确实浮出——rows=2
   即旧选择集合投影；提交归属归生产链恢复期取消检查）。
5. **旧集合不覆盖+取消不误报失败**：≥5s 观察窗（deck 前景）：deck 标记在窗；无门控；
   无"回收站记录未能读取"对话框；MediaStore 可见面 47（**可见性不变量**——旧投影 apply
   只改应用内存 allImages，不动 MediaStore）。**覆盖检测器（确定性重取批）**：第二次真实
   Settings 推荐模式现值重选 → 释放标记（"stale-release-boundary:"）后全部 DeckScreen
   批次行 id 集 == 47 池集**且至少一条存在**（若旧扫描释放后曾提交 2 行覆盖，重取批/
   同步批次出现 2 行事件即红灯；池 47 ≤ batchSize 50 → 整池重取确定性成立）；logcat 无
   TabulaApp "Could not load/reconcile recycle bin"（CancellationException 原样重抛不落
   onError——PR50 语义的设备面）。**清理状态面**：暂停前经真实生产写入口建立
   组{g1,g2}+断点([G],0)+已处理{g1} → 步骤5 以新 AlbumManager 实例 + AppPreferences 读回
   原样（FPF Phase3 Continuation 读回模式）——旧扫描暂停前已执行过
   albumManager.initialize(旧投影)，此断言验证 stale initialize 无持久污染。

## 建组模式登记（指令"FPF Phase1 建组模式复制"的取舍，测试类 KDoc 同载）

- 组状态经**真实生产写入口**建立（与 Phase1 建组入口同源）：`seedCleanupGroup`（分析+组键，
  持久键序 [g1,g2]；行身份由 shell 面查询重建——PARTIAL(B) 下 g1/g2 对应用不可见，
  rebuildRow 的应用身份查询不可用）、`acknowledgeCleanupMember`（已处理 g1）、
  `saveAlbumCleanupCheckpoint`（断点 ([G],0)——AlbumCleanupEngine:707 生产 saveCheckpoint
  同款入口）、`flushCleanupPrefs`。
- Phase1 的"真实月份清理 UI 上滑处理"腿不可行：该腿要求 g1/g2 对应用可见，而会话内权限
  为 PARTIAL(B)；PARTIAL→NONE 需 pm revoke（强停进程**含 instrumentation**，B2PR/FPF #52
  口径），与单 instrumentation 单例（probe 依赖门不适用）冲突——已处理/断点经生产写入口
  落盘，如实登记。
- Phase1 提交腿的**回收站记录不种子**：回收站条目会使生产投影减去软删除行（projectSnapshot
  契约，FPF Phase3 判据 10 实证 7−1=6），步骤3 的 47 池算术将变 46；污染断言面（组键/
  断点/已处理三元组）不依赖回收站腿。"已处理记录而无回收站条目"非生产可达组合，属状态面
  种子工件，其用途（initialize 写路径无持久污染检测）不受影响，如实登记。

## 失败→修正链（如实登记）

1. **run 43692855424（首 dispatch，e186a78）**：build job 红——全部 Robolectric 单测
   `MavenArtifactFetcher.java:129 AssertionError ← IOException (HttpURLConnection)` =
   CI runner 下载 Robolectric android-all 构件的**基础设施失败**（同窗口 master run
   43692861712 build 绿、变异 run 43692958900 build 绿——瞬时网络故障）；device job
   skipped，目标断言未执行。→ rerun（build 转绿）后**主动取消**（见 2）。
2. **设备执行前自查修正（61a26f0）**：审查发现两处会污染 device 结果的测试缺陷
   （取消的 device job = 43692855424/43692958900）：
   - 步骤4 释放原排在 Settings 前景（步骤3c 之后未回 deck）——Settings 前景下 deck 为
     backgroundContent，标记不保证在活动窗树内，观察窗的 deck 标记断言会**误红**；
     修正 = BACK 回 deck 后再释放（B2MR 步骤5 同款回退）；
   - 旧投影覆盖检测器错放：旧投影 apply 只改应用内存 allImages（MediaStore 面不变），
     集合面轮询**检测不到覆盖**；修正 = 观察窗后经第二次真实 Settings 重选做**确定性
     重取批**，释放标记后全部批次行 id 集 == 47 池集且至少一条存在（fail-closed），
     集合面降级为可见性不变量。
3. **run 43694997490（变异-1 重 dispatch，b19f404）被并发取消**：同 ref 并发组
   （volume-confirmation-test/stale-scan-mut-2026-11-15，cancel-in-progress）被
   变异-2 的 dispatch 取消（build 已绿、device 未执行）→ 变异-1 经审计分支
   **test/stale-scan-mut1-2026-11-15**（= b19f404，保留）以 **run 43696079300** 重跑。
4. CI 运行总表见下节。

## CI 运行总表

| run | ref / commit | 目的 | 结论 |
| --- | --- | --- | --- |
| 43692855424 | test/stale-scan-2026-11-15 @ e186a78 | 首 dispatch | build 红（Robolectric 构件下载基础设施失败）→ rerun 后主动取消（测试缺陷自查，见修正链 2） |
| 43692958900 | test/stale-scan-mut-2026-11-15 @ 824cbad | 变异-1 首 dispatch | build 绿（变异-1 不改单测结果）；device 未执行，主动取消（同 2） |
| 43692861712 | master | 窗口对照 | 全绿（证实 43692855424 的红为瞬时基础设施故障） |
| 43694969019 | test/stale-scan-2026-11-15 @ 61a26f0 | **主交付 CI** | **全绿**：build ✅ + device API36 ✅ + device API37 ✅；StaleScanLateReturn 段双 API OK(1)（complete 锚 + residual=[]）；段墙钟 API36≈161s / API37≈148s（段预算 600s）；device job 墙钟 API36≈23.5min / API37≈21.7min（预算 50min） |
| 43694997490 | test/stale-scan-mut-2026-11-15 @ b19f404 | 变异-1 重 dispatch | 被并发组取消（修正链 3；build 绿） |
| 43696079300 | test/stale-scan-mut1-2026-11-15 @ b19f404 | **变异-1 设备执行** | **本段双 API OK(1) 绿（no-op 预言成立）**；API36 job 整体红 = **无关遗留 flake**（PartialSelectionPhaseA 红 → PhaseB 级联"阶段B前置：PARTIAL 必须仍授权（宿主不得 pm clear） expected:<0> but was:<-1>"——本段在其后运行、时序隔离，与本分支/变异无关，如实登记）；API37 job 全绿 |
| 43695988574 | test/stale-scan-mut-2026-11-15 @ f3c3a35 | **变异-2（诊断）设备执行** | **双 API device job 红（变异检出）**：StaleScanLateReturnDeviceTest 红，但**红灯位置 = 早期 a11y 树等待**（API37 @ StaleScanLateReturnDeviceTest.kt:589 步骤1b waitFor(张待整理) / API36 @ :622 步骤2 waitFor），**未达步骤5 目标断言**——截屏留证显示界面完全正常（deck 渲染、"1 张待整理"稳态文本）而 rootInActiveWindow 树空（>10-30s 全空，instrumentation a11y 服务失联形态；FPF Phase3 startMonth 已登记过同族 a11y 语义异常先例）；与变异-2 的 zombie 扫描状态churn 的因果性未证实、相关性如实登记 |

### 补记（run 完成后回填）

- **主交付（43694969019）**：五步握手全程通过——步骤1b parkedRows==2、步骤3c 池集 47、
  步骤4 resumed rows=2、步骤5 重取批 47 池 + 无 error 行 + 状态面读回原样（wiring-grep
  与 complete 锚双证）；PR50 基线（build job 内）绿。
- **变异-1（43696079300）**：本段双 API 绿 = 指定变异形态（包下游）**未使设备测试失败**
  ——触发指令预留的"若变异后设备测试意外通过，如实记录并诊断"分支，诊断见下节。
- **变异-2（43695988574）**：双 API 红 = 有效变异形态被 CI 检出（job 级）；红灯未落在
  步骤5 目标断言（早期 a11y 树等待），zombie apply 的状态churn 与 a11y 失联的因果链
  未证实——如实在上表登记，不粉饰为"目标断言红灯"。

## 轮 1 变异-2 提前失败专项存档（2026-11-15 收口第二轮 §三-1，run 43695988574）

**失败位置（61a26f0 版行号）**：
- API37：`StaleScanLateReturnDeviceTest.kt:589` = 步骤1b `ui.waitFor(ui.DECK_MARKER,
  timeoutMs = 30000)`（armOnce + Home→am start 之后）——
  `IllegalStateException: timed out waiting for 张待整理`；
- API36：`StaleScanLateReturnDeviceTest.kt:622` = 步骤2 `ui.waitFor(ui.DECK_MARKER,
  timeoutMs = 10000)`（40 填充注入 + awaitGone×2 之后）——同款超时。

**形态**：两 API 失败点留证截屏均显示**界面完全正常**（deck 渲染、月份头、卡片栈、
"1 张待整理"稳态文本 = `currentBatch.size - currentIndex - 1` 的正常读数、卡片/图库
切换器在位）而 `rootInActiveWindow` 树**全空**（dumpTree 0 字节 = 无可见节点；
waitFor 全程 10-30s 轮询无一帧恢复）——instrumentation a11y 服务失联形态（同族先例：
FPF Phase3 startMonth 的"渲染在屏而 a11y 语义不可达"登记）。与变异-2 的 zombie 扫描
（NonCancellable 块脱离取消传播）状态churn 的**因果性未证实、相关性如实登记**。

**当时握手阶段（从 artifact logcat 的 MediaLoadTestHook/StaleScanLateReturn 行提取）**：
- API37：`armed` → `parked rows=2` 已发生（旧扫描已停在生产接缝、读取已返回）→
  a11y 失联打断在步骤1b 的 deck 标记等待——**release 未发生、旧扫描未释放、新扫描
  未提交**（grant 未执行）；
- API36：同阶段更晚（步骤1b 整体已过——deck 标记等待/park 轮询/40 填充注入/
  after-fill-still-B 集合面核对均通过）→ 失联打断在步骤2 的 deck 标记等待——
  **旧扫描仍 parked 未释放、grant 未执行、新扫描未提交**。
- 两 API 均未进入步骤3/4/5：**未达"旧结果覆盖新状态"目标断言（步骤5 集合面/池集）**，
  定性 = 变异 job 级红成立但**证据未落在目标断言**（与"旧结果覆盖"无判别关系）。

**轮 2 对策**：早期 a11y 失联恢复（等待辅助在树空/非应用包时有界 am start 重附窗，
恢复后继续原断言，不弱化等待目标/不放宽判别；步骤5 判别对恢复副作用免疫的论证：
zombie apply 的 2 行指纹经 DeckScreen 存活行同步 batch-site-7/8 落入释放标记后缓冲，
与恢复重扫的 47 行提交可区分）→ 见轮 2 修正提交与重跑记录。

## 变异验证记录（用户 §三 硬要求）

scratch worktree **wg-Stale-mut**（基于 test/stale-scan-2026-11-15），审计分支
**test/stale-scan-mut-2026-11-15**（保留不删；另建 test/stale-scan-mut1-2026-11-15 =
b19f404 供并发独立的变异-1 设备执行），**不合并任何 PR**。

### 变异-1（指令指定的最小变异，824cbad）

`apply(loadedData.first, loadedData.second)` 包进
`withContext(NonCancellable) { apply(...) }`（=移除"恢复期取消检查"保护，按指令）。

**结果：设备测试通过（变异未检出）——如实记录并诊断如下。**

诊断（三层证据，非掩盖）：
1. **字节码层**：kotlinx-coroutines 1.10.2 `DispatchedTask.run`（CancellableMode 分支）
   在被恢复的调用方 job 非 active 时**丢弃成功结果、改以 CancellationException 恢复**
   （反编译实证）。检查点位于 `withContext(Dispatchers.IO)` 的完成边界——包在其**下游**
   的 apply 在取消路径上**不可达**，变异为 no-op。
2. **单测层交叉证据**：变异-1 的 build job 全绿（run 43692855424 build 红为基础设施、
   43694997490 build 绿）——PR50 晚返回单测在变异-1 下同样通过，单测级同样未检出。
3. **本地单测对照**（wg-Stale-mut 本地 `:app:testReleaseUnitTest`）：
   - 基线（无变异）：PR50 三例绿（run 本地 + CI build job 双证）；
   - 变异-1：晚返回用例绿（no-op 实证）；
   - **变异-2（有效形态）**：`lateReturningOldReadReturnsButNeverCommits` **FAILED**
     （AssertionError，stale apply 确实执行）——单元级检出实证。

**根因**：指令指定的变异形态（包下游）与设计文档所述保护（"恢复期取消检查"= IO 块完成
边界的可取消恢复检查）不在同一位置——PR50 KDoc 自己记载的有效变异形态是"**将提交链的
withContext 换成 NonCancellable**"（同拦截器路径 → UndispatchedCoroutine，无取消检查，
且块脱离被取消调用方的 job）。

### 变异-2（诊断性复合变异，f3c3a35——按 PR50 KDoc 的有效形态 + 单测路由，audit-only）

内容：load-commit 块 `withContext(Dispatchers.IO)` → `withContext(NonCancellable)`
（=PR50 KDoc 记载的有效变异形态），并把 PR50 两个竞态用例 `@Ignore`（路由说明：有效
形态下两用例在 build job 的单测级先红/挂起——合作取消用例在 NonCancellable 块内
`await()` **永久挂起**（本地实证），晚返回用例红（本地实证 AssertionError）——不 Ignore
则 device job 被 build 门拦截，无法演示设备级红灯）。

预期：StaleScanLateReturnDeviceTest 在步骤5b 红灯（释放后重取批/同步批次出现 2 行事件
或重取批 ≠ 47 池——旧扫描释放后提交 2 行覆盖）。结果见上方补记。

### 变异验证结论

1. **指令指定的变异形态（变异-1，包下游）是 no-op**：设备段双 API 绿（43696079300）+
   PR50 单测绿（build job）——"变异后设备测试意外通过"，按指令如实记录并诊断：
   保护点（恢复期取消检查）位于 `withContext(Dispatchers.IO)` **完成边界**
   （DispatchedTask.run CancellableMode 检查，1.10.2 字节码实证），其下游的 apply 在
   取消路径不可达；PR50 KDoc 自己记载的有效变异形态是"将提交链的 withContext 换成
   NonCancellable"。
2. **有效变异形态（变异-2）被 CI 检出**：双 API device job 红（43695988574）——设备级
   检出成立；红灯落在早期 a11y 树等待（zombie 扫描churn 的伴生症状，界面本身正常），
   步骤5 目标断言未到达，如实登记。**单元级检出实证补全**：本地
   `lateReturningOldReadReturnsButNeverCommits` 在变异-2 下 FAILED（stale apply 确实
   执行）——"旧扫描释放后提交覆盖"缺陷类在单测级与 job 级均被检出。
3. **总体判定**：设备验收（真实接线级五步握手）在主交付全绿；指定变异 no-op 的诊断
   完成且证据链闭环（字节码 → 单测对照 → PR50 KDoc 交叉印证）；有效变异形态被 job 级
   检出（双 API）+ 单测级检出（本地）。未达成"步骤5 目标断言红灯"的完成态，差距与
   原因如上登记，不掩盖。

## 遗留登记

1. **变异红灯位置偏差**：变异-2 双 API 红灯在早期 a11y 树等待（步骤1b/2 的
   waitFor(张待整理)，截屏留证界面正常）而非步骤5 目标断言——与 zombie 扫描
   状态churn 的因果性未证实；如需"目标断言红灯"完成态，需在变异分支给测试加
   a11y 失联恢复（am start 重新附着活动窗）——属测试改动，超出本单元授权，登记不动。
2. **变异-1 API36 job 的无关 flake**：PartialSelectionPhaseA 红 → PhaseB 级联（本段
   绿、时序隔离、与本分支无关）——遗留既有套件自身问题，不在本单元处理范围。
3. **Robolectric 构件下载瞬时故障**（run 43692855424 build 红）：CI runner 对 Maven
   Central 的下载无缓存护城河（setup-java gradle cache 不含 ~/.m2），偶发即红——
   既有基础设施口径，本单元未改动（"顺手改进"纪律）。
4. **probe 预算余量**：主交付实测段墙钟 ≈150-160s（预估 200-290s 偏保守）；1800s/50min
   预算保持（后续段落增长空间按既有口径预留）。
5. 审计分支保留：**test/stale-scan-mut-2026-11-15**（f3c3a35：变异-1 + 修正 cherry-pick
   + 变异-2 复合）与 **test/stale-scan-mut1-2026-11-15**（b19f404：变异-1 隔离点）——
   均不合并；scratch worktree wg-Stale-mut 保留。
