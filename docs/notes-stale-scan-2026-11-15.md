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
| 43714053881 | test/stale-scan-mut-2026-11-15 @ d5f5fd5 | 变异-2 重跑（带 a11y 恢复） | **双 API 提前红（主线程冻结根因）**：`marker-step1b` awaitDeckMarkerWithRecovery（StaleScanLateReturnDeviceTest.kt:672）a11y recoveries=4 仍失败；THREAD_STATE 双 API 实证 main 阻塞于 MediaLoadTestHook park latch.await——定位过程见下文轮 2 小节 |
| 43717346095 | test/stale-scan-mut-2026-11-15 @ c3c813f | **变异-3（目标形态）设备执行** | **双 API 步骤5 目标断言红灯（§三 核心证据达成）**：StaleScanLateReturnDeviceTest.kt:939 释放后批次池断言（API37 13:38:46 / API36 13:39:51），双 API 均先走完全部五步；build job 绿（PR50 路由后单测全绿） |
| 43714036923 | test/stale-scan-2026-11-15 @ 69a9611 | 轮 A（正常分支 dispatch 1） | API37 job **全绿**；API36 job 红 = **无关遗留 flake**（FPFPhase3Test "timed out waiting for 全局"）；本段双 API OK(1)（API36 complete 锚 12:57:39） |
| 43717360920 | test/stale-scan-2026-11-15 @ 69a9611 | 轮 B（正常分支 dispatch 2） | API37 job **全绿**；API36 job 红 = **无关遗留 flake**（CardLifecycleDeviceTest scenario1DrawCancel…）；本段双 API OK(1)（API36 complete 锚 13:37:16） |
| 43720733892 | test/stale-scan-2026-11-15 @ 69a9611 | 轮 C（正常分支 dispatch 3，合并判据门） | API36 job **全绿**；API37 job 红 = **无关遗留 flake**（FPFPhase3Test "timed out waiting for 全局"，同轮 A 签名）；**本段 API37 OK(1) 148.13s + API36 OK(1)**（工件实测，详见补记三与时间线小节） |
| 43724140820 | test/stale-scan-2026-11-15 @ 69a9611 | 轮 D（正常分支 dispatch 4，前任被终止时在跑） | **三 job 全绿（合并判据达成）**：build ✅ + device API36 ✅ + device API37 ✅ @ 69a9611；本段双 API OK(1)（API36 144.47s complete 锚 14:52:21 / API37 147.70s complete 锚 14:52:56，均 residual=[]，见补记四） |

### 补记（run 完成后回填）

- **主交付（43694969019）**：五步握手全程通过——步骤1b parkedRows==2、步骤3c 池集 47、
  步骤4 resumed rows=2、步骤5 重取批 47 池 + 无 error 行 + 状态面读回原样（wiring-grep
  与 complete 锚双证）；PR50 基线（build job 内）绿。
- **变异-1（43696079300）**：本段双 API 绿 = 指定变异形态（包下游）**未使设备测试失败**
  ——触发指令预留的"若变异后设备测试意外通过，如实记录并诊断"分支，诊断见下节。
- **变异-2（43695988574）**：双 API 红 = 有效变异形态被 CI 检出（job 级）；红灯未落在
  步骤5 目标断言（早期 a11y 树等待），zombie apply 的状态churn 与 a11y 失联的因果链
  未证实——如实在上表登记，不粉饰为"目标断言红灯"。
- **补记三（轮 C 43720733892，2026-11-15 收口第二轮补齐）**：API37 job 红 =
  FPFPhase3Test "timed out waiting for 全局"（14:08:04，T07 段门）；**目标段
  StaleScanLateReturn 在其后独立执行且双 API OK(1)**——API37 test.txt 实测
  `Time: 148.13 / OK (1 test)`、complete 锚 14:14:52.050、residual=[]；API36
  complete 锚 14:15:46.756、residual=[]。69a9611 的 a11y 恢复在本绿样本中两次触发并
  成功（marker-step1b / marker-step2-upgrade 各 recovery #1，见时间线小节）。
  ui-failures 采集 rc=1（COLLECTION FAILED，非门控）沿用轮 C 诊断口径。
- **补记四（轮 D 43724140820，接管者跟进至完成）**：**三 job 全绿 @ 69a9611**（14:23:58Z
  派发，14:53:10Z completed/success）——build ✅、device API36 ✅、device API37 ✅。
  双 API 全部段组 OK（矩阵 D 列 17 行全绿）：目标段 StaleScanLateReturn 双 API OK(1)
  （API36 Time 144.468s、complete 锚 14:52:21.208、residual=[]；API37 Time 147.701s、
  complete 锚 14:52:56.219、residual=[]）。**合并判据 = "至少一次全绿 run 绑定最终
  head"达成**：43724140820 绑定 69a9611（代码 SHA；随后的 docs 提交不影响该口径，
  #74 docs 尾随惯例）。上游 flake 爆发窗（12:36-14:08 三轮各一处）在 14:23 派发
  自行平息，无需重派与段级重试。

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

## 收口第二轮（2026-11-15 §三）：目标变异证据补齐

### 轮 2 修正（正常分支 1ad07ba + 69a9611）

- **1ad07ba**：轮 1 变异-2 提前失败专项存档（见上节）。
- **69a9611**：定向修正——`recoverA11yWindowIfLost`（rootInActiveWindow 空/非应用包/
  全树无可见节点 → 有界 am start 重附窗，每标签 ≤4 次，逐轮留证，文件名无 `|`），
  应用于全部 5 处 deck 标记等待（`awaitDeckMarkerWithRecovery`）+ `awaitAnyNode` +
  步骤5a 观察窗；等待目标与截止时间不变（不弱化断言）；三事件登记（接缝恢复/
  读取返回/提交处理缺席面 + 不可注入约束）载入钩子与测试 KDoc。
- 本地 `:app:compileDebugAndroidTestKotlin --no-build-cache` ✅ + PR50 单测 ✅。
- probe 完整性自查（轮 2 编辑后复核）：class 循环 + b2pr + b2mr + FPF Phase1-3 +
  FullRecovery 四件 + PartialSelection A/B + 本段全部在位；依赖门（FPF Phase2/3、
  b2pr step1/2、partial 阶段）在位；失败传播（76 处 status=1 + exit "$status"）在位；
  `bash -n` 通过。

### 轮 2 变异-2 重跑（d5f5fd5 = f3c3a35 形态 + normal 69a9611 合并）→ 提前失败根因定位

run **43714053881**：双 API 仍在 `marker-step1b` 红（`StaleScanLateReturnDeviceTest.kt
:672` → `awaitDeckMarkerWithRecovery`，`a11y recoveries=4` = 恢复 4 次仍未恢复）。
**THREAD_STATE 双 API 实证根因（主线程冻结）**：`Thread[main,5,main]` 阻塞于
`CountDownLatch.await ← MediaLoadTestHook$parkBeforeCommit$2.invokeSuspend
(MediaLoadTestHook.kt:85)`（round-1 同形态 :76，run 43695988574）——

**机制**：变异-2 的同拦截器 NonCancellable 块经 UndispatchedCoroutine **在主线程
undispatched 起跑**（caller = Compose 主调度器），块内挂起段经主调度器回归——整个
load-commit 块有效运行在主线程；接缝 park 的 `gate.await()` 在 **NonCancellable 上下文
退化为阻塞式 latch.await**（kotlinx.coroutines 实现：不可取消即不挂起）→ **主线程在
parked 门处永久冻结**：a11y 树死（root=launcher→null，截屏界面为冻结末帧）、窗口焦点
停留在 launcher（onResume 无法完成）、恢复性 am start 无效（app 无法处理 resume）。
对照：正常实现中该块在 `withContext(Dispatchers.IO)` 内运行——阻塞 await 只占用一个
IO worker（主交付 2/2 绿的机理）。**变异-2 的红灯 = 变异缺陷的真实表现（生产等价 =
扫描冻结主线程），但证据无法落到步骤5 目标断言**（app 冻结，无法驱动到步骤5）——
按"提前导航超时"形态登记。

**失败形态链（按指令分类）**：轮 1 = 早期 a11y 树等待（误判为伴生症状）→ 轮 2 恢复
修正后 = 早期导航超时 + 主线程冻结根因（THREAD_STATE 铁证）→ **继续定位收敛到变异
形态本身**：目标断言判别的是"旧结果提交覆盖"，需要一个**主线程存活**的变异形态。

### 变异-3（c3c813f，supersedes 变异-2；audit-only）

内容：load-commit 块恢复 `withContext(Dispatchers.IO)`（生产形状，主线程不阻塞），
仅把 `apply` **移入 IO 块内、紧随接缝之后**：被取消扫描在释放后于 IO worker 上恢复，
到达 apply 前**无任何取消检查点**（提交保护边界在块完成处、位于 apply 之后）→ 旧
投影提交落地；主线程存活 → 五步握手完整走通 → 红灯落到步骤5 目标断言。PR50 晚返回
用例在此形态下单测级先红（本分支两竞态用例 @Ignore 路由维持；合作取消用例此形态下
绿，Ignore 冗余但保留登记）。

**run 43717346095 结果 = 目标断言红灯，双 API**：

| API | 红灯 | 位置 |
| --- | --- | --- |
| API37 | `IllegalStateException: 释放后批次池断言失败（旧扫描释放后提交 2 行覆盖 / 或重取批未承接 47 池 / 或释放后无新批次事件）` | `StaleScanLateReturnDeviceTest.kt:939`（13:38:46） |
| API36 | 同上 | `StaleScanLateReturnDeviceTest.kt:939`（13:39:51） |

**直接指纹（API37 错误消息原文）**：
```
postReleaseBatchLines(1)=11-15 13:38:10.978 20197 20197 V DeckScreen:
currentBatch<-[batch-site-4]: content://media/external_primary/images/media/234:...,
content://media/external_primary/images/media/235:...
expectedIds(47)
```
API36 同指纹（13:39:15.408，batch-site-4 重取批 = ids 234/233 两行 vs expectedIds(47)）。
释放（13:38:03.966 released/resumed rows=2）之后 7 秒，步骤5b 确定性重取批
（batch-site-4，13:38:10.978）从**被旧扫描覆盖后的 2 行池**取批（ids 234/235 = b1,b2）
——"旧扫描释放后提交 2 行覆盖"被目标断言直接捕获。

**变异-3 证据登记（§三 口径，2026-11-15 接管者复核）**：
- 正常 SHA：**69a9611**（合并基 d5f5fd5 = 69a9611 并入变异分支）；变异 SHA：
  **c3c813f**（69a9611→c3c813f diff 仅 MainActivity.kt load 块 +17/-12 与
  MediaSnapshotLoadRaceTest.kt 路由 +7，隔离性核实）；
- 变异内容：`apply(projected, loadResult.second)` 移入 `withContext(Dispatchers.IO)`
  块内、紧随 `MediaLoadTestHook.parkBeforeCommit(...)` 之后（外层 apply 删除）；
- 目标断言：`parkedStaleScanReleasedAfterNewCommitNeverOverwritesAndNeverReportsFailure`
  @ StaleScanLateReturnDeviceTest.kt:**939**（步骤5b 释放后批次池断言，行号复核于
  69a9611 与 c3c813f 两版测试文件——两版测试文件一致）；
- 双 API 失败行：API37 `INSTRUMENTATION_STATUS: stack=...释放后批次池断言失败...`
  13:38:46.137Z + API36 13:39:51.001Z（均含 `expectedIds(47)` 与 `at ...kt:939`）；
- 变异分支 push 状态：origin/test/stale-scan-mut-2026-11-15 = c3c813f（已推送，
  本地无领先提交）；run 43717346095（build 绿 + 双 device 红）即绑该 SHA。

### 轮 2 具名断言登记（69a9611 版行号，StaleScanLateReturnDeviceTest.kt）

| 断言 | 行 | 语义 |
| --- | --- | --- |
| 旧扫描必须停在可控位 | :678 | 步骤1b parkedRows()==2（暂停者=旧选择集合扫描、读取已返回） |
| 升级后门控屏不得出现 | :748 | 步骤3b 组合观察窗（无门控回退） |
| 升级后 deck 标记必须持续在窗 | :753 | 步骤3b 存活行同步不白屏 |
| 释放后必须确认旧读取确实返回 | :827 | 步骤4 isReleased + "resumed rows=2"（接缝恢复+读取返回双事件） |
| 释放后门控屏不得出现（窗内） | :849 | 步骤5a hasPermission 保持 |
| 释放后 deck 标记必须持续在窗（窗内） | :854 | 步骤5a 取消不得重置消费面 |
| 释放后不得出现回收站记录未能读取对话框 | :859 | 步骤5a 取消不误报加载失败（界面面） |
| 释放后 MediaStore 可见面变化 | :868 | 步骤5a 可见性不变量（覆盖检测由下条承担） |
| **释放后批次池断言失败（目标断言）** | **:939** | **步骤5b 释放标记后全部批次行 id 集 == 47 池集且至少一条存在——变异-3 双 API 在此红灯** |
| 取消不得落 error 态 | :946 | 步骤5b TabulaApp error 行缺席（PR50 语义设备面） |

### 变异-3 握手时间线（API37，run 43717346095，artifact logcat 提取；pid=20197）

| 时刻 | 锚 |
| --- | --- |
| 13:36:09.732 | step1a PARTIAL(B) established + armed（同毫秒） |
| 13:36:12.218 | MediaLoadTestHook: parked rows=2（**tid 20691 = IO worker，主线程不阻塞**——与变异-2 的主线程冻结形态成对照） |
| 13:36:12.768 | step1b parkedRows==2 确认 |
| 13:37:51.130 | step2 填充注入（store=47）+ grant FULL |
| 13:37:54.124 | step3a 集合面 47 |
| 13:38:00.187 | step3b deck 观察窗通过 |
| 13:38:03.509 | step3c 边界后 batch-site-4 池集 == 47 |
| 13:38:03.966 | released + resumed rows=2（IO worker 20691） |
| 13:38:04.256 | step4 旧读取确实返回确认 |
| 13:38:09.364 | step5a 观察窗通过（MediaStore 47、无门控、无对话框） |
| 13:38:10.978 | **覆盖指纹**：batch-site-4 重取批 = 2 行（234/235） |
| 13:38:46 | **步骤5b 目标断言红灯（:939）** |

API36 同构（13:39:51 同断言红灯；双 API 均先走完全部五步再红）。

### 轮 2 正常分支 CI 记录

- run **43714036923**（69a9611）：API37 全绿 + 本段 API36 绿；API36 job 红 =
  **无关遗留 flake**（FullPartialFullPhase3 "timed out waiting for 全局"）——本段
  complete 锚 12:57:39 在后、时序隔离。
- run **43717360920**（69a9611）：API37 全绿 + 本段 API36 绿（complete 锚 13:37:16）；
  API36 job 红 = **无关遗留 flake**（CardLifecycleDeviceTest scenario1DrawCancel…）。
  两次失败落在不同遗留套件 = 共享 runner 资源压力型 flake（与本分支改动无关）。
- run **43720733892**（69a9611，第三次 dispatch，合并判据门）：API36 job **全绿**；
  API37 job 红 = **无关遗留 flake**（FPFPhase3Test 同轮 A 签名）；**本段双 API OK(1)
  （API37 148.13s 工件实测）**——目标段在三轮 6 个 job 执行中全部绿（详见矩阵）。
- run **43724140820**（69a9611，第四次 dispatch，前任被终止时在跑，接管者跟进）：
  **三 job 全绿**（补记四）——合并判据达成，无需进一步重派。

### 分段结果矩阵（轮 A-D @ 69a9611，从 --log 提取的段门 + OK 锚）

段序 = probe 顺序（`tools/run-cloud-ui-probe.sh`）：类循环 → PartialSelection A/B →
FPF Phase1/2/3 → FullRecovery 四件 → b2pr step1/2/3 → b2mr → **StaleScan（本段，末位）**。
判读口径：每 job 每段按 `OK (n tests)` 锚（类/阶段名配对）判绿、按段门行
（"expected OK(n)" / "Failure or skip" / "…failed"）判红；失败不早退（status 累积），
故红轮中后续段仍被执行与判读。**本段（StaleScan）在 4 轮 × 2 API 全部 OK(1)**。

| 段 | A36 | A37 | B36 | B37 | C36 | C37 | D36 | D37 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| VolumeDeletion（probe 前置 3 例） | OK | OK | OK | OK | OK | OK | OK | OK |
| CloudCleanupUiTest(8) | OK | OK | OK | OK | OK | OK | OK | OK |
| CloudRestoreUiTest(3) | OK | OK | OK | OK | OK | OK | OK | OK |
| CreateAlbumDiagnostic(2) | OK | OK | OK | OK | OK | OK | OK | OK |
| ForegroundPermissionUiTest(2) | OK | OK | OK | OK | OK | OK | OK | OK |
| PixelRefreshUiTest(2) | OK | OK | OK | OK | OK | OK | OK | OK |
| OtherAppMediaAuth(4) | OK | OK | OK | OK | OK | OK | OK | OK |
| ProdAuthCallback(2) | OK | OK | OK | OK | OK | OK | OK | OK |
| **CardLifecycleDeviceTest(3)** | OK | OK | **红** | OK | OK | OK | OK | OK |
| T06RemainingDeviceTest(3) | OK | OK | OK | OK | OK | OK | OK | OK |
| PartialSelection PhaseA/B | OK | OK | OK | OK | OK | OK | OK | OK |
| FPF Phase1 / Phase2 | OK | OK | OK | OK | OK | OK | OK | OK |
| **FPF Phase3** | **红** | OK | OK | OK | OK | **红** | OK | OK |
| FullRecovery 四件（phase1/absence/cont/readback） | OK | OK | OK | OK | OK | OK | OK | OK |
| B2PermissionRefresh step1/2/3 | OK | OK | OK | OK | OK | OK | OK | OK |
| B2MediaAccessRevision | OK | OK | OK | OK | OK | OK | OK | OK |
| **StaleScanLateReturn（目标段）** | **OK** | **OK** | **OK** | **OK** | **OK** | **OK** | **OK** | **OK** |

红格定位：A36 FPF Phase3 = `FPFPhase3Test onSwitchToGlobal` 全局批次等待超时
（12:50:38，"timed out waiting for 全局"）；B36 CardLifecycle = 13:22:43
"Failure or skip observed in CardLifecycleDeviceTest"（scenario1DrawCancel…，上游
登记 flake）；C37 FPF Phase3 = 14:08:04 同 A36 签名。三处红均在本段（末位）之前、
段门独立、本段时序隔离——与 coord/diag-closure2-burst-2026-11-15.md 三连红逐因一致。

### 正常分支五步握手时间线（轮 C API37，run 43720733892 artifact 提取，pid=19887）

来源：`device-results/StaleScanLateReturn/logcat.txt`（工件 Tabula-VolumeFix-API37）
+ `test.txt`（Time: 148.13, OK (1 test)）。时间 = 设备 logcat threadtime。

| 时刻 | 锚 |
| --- | --- |
| 14:12:41.467 | 夹具注入 7 行（shell 身份 bulk 通道） |
| 14:12:44.329 | 状态面播种（组 g1/g2 + 断点([G],0) + acknowledged={g1}） |
| 14:12:49.217 | PARTIAL(B) 集合面确认：2 行无重复 |
| 14:12:50.770 | **step1a** PARTIAL(B) established（visible={b1,b2}）+ **armed**（同毫秒） |
| 14:12:52.825 | marker-step1b a11y 窗失联（root=launcher）→ **恢复 #1** am start 重附（69a9611 生效证据） |
| 14:12:53.048 | **parked rows=2**（tid 20247 = IO worker，主线程不阻塞） |
| 14:12:53.685 | step1b parkedRows==2 确认（读取已返回、投影在手上） |
| 14:14:16.844 | 40 填充注入（store=47）；after-fill-still-B 2 行（14:14:17.794） |
| 14:14:27.951 | **step2** 填充注入完成 + 会话内 grant FULL（level=FULL） |
| 14:14:30.048 | marker-step2-upgrade a11y 恢复 #1（升级后观察窗前） |
| 14:14:30.869 | **step3a** 集合面 = 47 全名集（哨兵 s1 在内）——新扫描全池提交生效 |
| 14:14:36.903 | step3b deck 存活行同步（无门控/无白屏） |
| 14:14:38.657 | **边界后 batch-site-4 池集**：currentBatch 47 ids == 全池 |
| 14:14:40.133 | step3c 池集相等断言通过 |
| 14:14:40.574 | **released**（stale-release-boundary → MediaLoadTestHook: released） |
| 14:14:40.575 | **resumed rows=2**（tid 20247 = 同一 IO worker） |
| 14:14:40.655 | **step4** 旧读取确实返回确认（isReleased=true） |
| 14:14:45.729 | step5a 观察窗通过（25 轮询 lastFace=47、无门控、无错误对话框） |
| 14:14:47.286 | 释放后二次重取 batch-site-4 仍 47 ids（无 2 行事件） |
| 14:14:48.978 | **step5b** 释放后重取 = 全 47 池、无 2 行批次事件（旧投影未落地） |
| 14:14:49.036 | step5b 缓冲无 TabulaApp 错误行 + step5c 状态面读回原样 |
| 14:14:52.050 | **complete** 锚（parked stale scan released） |
| 14:14:53.953 | after cleanup residual=[] |

要点：五步全程 14:12:50→14:14:52（≈2m02s，套件 Time 148.13s 含夹具与清理）；旧扫描
park→release 由同一 IO worker（tid 20247）承载；a11y 恢复两次触发均单次成功、未触及
每标签 ≤4 上限；释放后无任何 2 行批次事件 = "旧结果覆盖新状态"在正常实现下不发生。

### 变异验证结论（轮 2 收口更新）

1. **指令指定的变异形态（变异-1，包下游）是 no-op**：设备段双 API 绿（43696079300）+
   PR50 单测绿（build job）——"变异后设备测试意外通过"，按指令如实记录并诊断：
   保护点（恢复期取消检查）位于 `withContext(Dispatchers.IO)` **完成边界**
   （DispatchedTask.run CancellableMode 检查，1.10.2 字节码实证），其下游的 apply 在
   取消路径不可达；PR50 KDoc 自己记载的有效变异形态是"将提交链的 withContext 换成
   NonCancellable"。
2. **PR50 KDoc 形态（变异-2）在设备上 = 主线程冻结缺陷**：双 API device job 红
   （43695988574/43714053881，4/4 变异运行 vs 0/2 正常运行——因果成立）；根因
   THREAD_STATE 铁证（main = park 的阻塞 latch.await）。该形态单测级红/挂起（本地
   实证），设备级表现为扫描冻结主线程——**红灯无法落在步骤5 目标断言**（app 冻结），
   按"提前导航超时 + 主线程冻结"登记（轮 2 §三-1 存档 + 根因定位）。
3. **目标断言红灯达成（变异-3，c3c813f）**：apply 移入 IO 块紧随接缝——主线程存活、
   五步握手完整走通、**双 API 红灯准确落在步骤5 目标断言**（:939，错误消息携带
   batch-site-4 重取批 2 行指纹 API37 ids 234/235、API36 ids 234/233 vs expectedIds(47)）——"旧扫描释放后提交
   2 行覆盖"被验收直接捕获。检出示意链：变异 → 覆盖发生 → DeckScreen 重取批从被
   覆盖池取数 → 池集相等断言红灯。
4. **总体判定**：设备验收（真实接线级五步握手）主交付绿（43694969019 全绿；轮 2
   A/B/C 三轮目标段 6 job 执行全部 OK(1)，三轮各自恰有一个上游遗留段红——A36/C37
   FPF Phase3、B36 CardLifecycle——时序隔离，见矩阵）；指定变异 no-op 诊断闭环；
   **目标变异证据达成**（变异-3 双 API :939 红灯 + 直接指纹）；变异-2 形态的额外发现
   （主线程冻结）作为该缺陷族的另一表现如实入档。**三 job 全绿判据达成**：轮 D
   （43724140820）双 device job 全绿绑定 head 69a9611（合并判据 = "至少一次全绿
   run 绑定最终 head"满足）。

## 遗留登记（轮 2 收口更新）

1. **变异-2 形态的主线程冻结缺陷族发现**（PR50 KDoc 的"有效变异形态"在设备上的真实
   表现 = 扫描冻结主线程）：NonCancellable 上下文的挂起式 latch.await 退化为阻塞
   await + 同拦截器块在主线程 undispatched 起跑——该交互对生产接缝设计是一个**反向
   印证**（park 必须在 IO 块内：阻塞 await 只准落在 IO worker），已载入钩子 KDoc。
2. **变异-1 API36 job 的无关 flake**：PartialSelectionPhaseA 红 → PhaseB 级联（本段
   绿、时序隔离）——遗留既有套件自身问题。
3. **轮 2 正常分支三轮各一处上游遗留段红**：FullPartialFullPhase3 "timed out
   waiting for 全局"（A=43714036923 API36 / C=43720733892 API37，同签名）与
   CardLifecycle scenario1DrawCancel…（B=43717360920 API36）——三处落在两个不同
   遗留套件、**目标段 6 job 全绿**、时序隔离 = 共享 runner 资源压力型 flake（下午窗
   爆发，diag-closure2-burst-2026-11-15.md）；与 master 基线 43713220665 当晨三 job
   全绿对照。**轮 D（43724140820）三 job 全绿**（14:53 完成）——上游爆发窗自行平息，
   未重派、未动段级重试。
4. **Robolectric 构件下载瞬时故障**（run 43692855424 build 红）：CI runner 对 Maven
   Central 的下载无缓存护城河，偶发即红——既有基础设施口径，未改动。
5. **probe 预算**：变异-3 的 test.txt 实测耗时为 API36 185.205s、API37 198.314s。
   正常轮 C API37 为148.13s。1800s/50min 预算保持。
6. **审计分支保留（均不合并）**：test/stale-scan-mut-2026-11-15（**c3c813f** = 变异-1
   历史 + normal 合并 + 变异-2（主线程冻结形态）+ **变异-3（目标断言红灯形态）**）；
   test/stale-scan-mut1-2026-11-15（b19f404）；scratch worktree wg-Stale-mut 保留。
7. 变异-3 的 PR50 合作取消用例 @Ignore 为冗余路由（该形态下其绿），保留为分支级统一
   路由并登记；变异分支的测试文件与 normal 完全一致（无测试弱化）。

## 2026-11-16 释放后恢复覆盖检测收口

- 复核发现旧版步骤5b只过滤并解析 batch-site-4。恢复动作可能通过 onResume
  重扫恢复47池，此前 batch-site-7/8 的瞬时两行覆盖事件没有进入断言。
  上文历史正常 run 43724140820 三 job 绿、变异 run 43717346095 双 API
  在原 :939 红灯的记录保留，恢复治愈路径在本轮补验。
- 现在释放标记后的 batch-site-4/7/8 全部接受池集核对，任一非47事件失败。
  第二次推荐点击之后必须出现 site-4 全47池事件，保留独立的动作成功门。
  两个边界标记丢失均失败，错误同时列出历史事件和最新 site-4 集合。
- 变异-3与新测试组合时传 instrumentation 参数
  `staleScanForceRecoveryAfterOverwrite=true`。先等 site-7/8 精确等于旧 B 集合，
  再 Home 后经现有 a11y 恢复入口重启前台检查。必须先出现
  `mutation recovery healed site-4 refetch to full 47 pool`，之后目标批次断言
  仍应因历史两行事件失败。默认流程不启用此参数。
- MediaLoadTestHook 新增 DEBUG reset。setup和@After先复位再进行后续操作，
  释放已暂停的latch并清除未消费武装态。park在锁内捕获本次latch，release/reset
  只释放捕获的latch，旧操作与新arm交错时不会解除新门。reset不取消生产扫描，
  后续probe仍沿用原有进程及应用状态隔离。
- 本轮文件范围为测试、MediaLoadTestHook和本notes。编译、正常双API设备验证、
  恢复治愈变异双API验证及独立审计待集中执行，尚无新设备通过证据。

