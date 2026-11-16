# 独立审计：Unit B 旧扫描晚返回设备验收（PR #81，test/stale-scan-2026-11-15 @ 69eb962，CI 核查提交 61a26f0，2026-11-15）

审计角色：独立审计员（新上下文，未参与实现）。全程只读：gh pr diff / gh api / 工件下载 / 反编译核对，未修改、未 push、未 dispatch。

## 结论：**PASS**（变异-2 权重判定：**"检出但断言位置不符预期"，非"未检出"**——实现者已如实登记，不构成修复项）

CI 判据：run 43694969019（headSha=61a26f0，success：build + device API36 + device API37 全绿）。69eb962 与 61a26f0 diff = docs/notes 仅 226 行插入（`git diff --stat` 核对），CI 证据覆盖 PR 全部代码。

## 逐项

1. **范围**：PR #81 恰 7 文件——生产恰 2：`MediaLoadTestHook.kt` 新增 +86、`MainActivity.kt` 单行 +1；androidTest 新类 `StaleScanLateReturnDeviceTest.kt` +905；`build-volume-confirmation.yml` +3/-3（预算注释+timeout 40→50）；`run-volume-device-probe.py` +8/-1（1500→1800+登记注释）；`run-cloud-ui-probe.sh` +76（新段+class 循环注释）；`docs/notes-stale-scan-2026-11-15.md` 新增 +226。**app/src/test 零 diff** ✓。

2. **接缝正确性（逐行）**：
   - **DEBUG 门全入口**：`armOnce/release/parkBeforeCommit` 首行 `if (!BuildConfig.DEBUG) return`；`parkedRows/isReleased` 三元门（非 DEBUG 返回 -1/false）。5 入口无遗漏。隔离口径与 `GenieEffect.kt` GenieDeleteAnimTestHook 先例同款（先例三入口 DEBUG 门 + 消费即一次性，注释风格对齐）✓。
   - **一次性门**：`gateArmed` @Volatile 消费式（首个 park 置 false 后 await）；后续扫描直通；release 仅 countDown。观测面（parkRows/released）独立于门控，仅 DEBUG 可读 ✓。
   - **parkBeforeCommit 的 NonCancellable 承载**：`withContext(NonCancellable) { gate?.await(); Log.i("resumed rows=$rows") }`——"resumed" 日志在 NonCancellable 块内，取消推迟期仍可产生（步骤4 证据行）✓。
   - **MainActivity.kt:213 接缝位置**：`loadAndApplyMediaSnapshot` 的 `withContext(Dispatchers.IO)`（:208）块**内部**、`albumManager.initialize`（:212）之后、`Pair(projected, ...)` 返回（:214）之前；`apply`（:216）在块外。块外 park 的设计否决项成立：若在块外，恢复后到 apply 无取消检查点。catch(CancellationException) 原样重抛（:218-219）✓。
   - **正式构建隔离（独立判定）**：CI build job（43694969019）含 `:app:assembleRelease` 且绿 = release 编译核查实证；release 变体 DEBUG=false → 5 入口全部提前返回/恒值，park 不挂起，release APK 无行为面 ✓。

3. **取消语义独立判定（审计员自查，不采信实现者口径）**：本地反编译 gradle cache 的 kotlinx-coroutines-core-jvm-**1.10.2**（与项目依赖同版本）：
   - `DispatchedCoroutine.afterResume` 字节码 → `resumeCancellableWith(uCont.intercepted(), recoverResult(...))`——`withContext(Dispatchers.IO)` **完成边界**对调用方按 CancellableMode 恢复；
   - `DispatchedTask.run` 偏移 **124-252**：exceptional 为 null 且 `isCancellableMode(resumeMode)` → 取调用方 Job → `!isActive` → `cancelCompletedResult`（丢弃成功结果）→ 以 `Result.failure(CancellationException)` resume——与 notes"偏移 124-252"记载逐字吻合；
   - 内层 `withContext(NonCancellable)` 同拦截器 → UndispatchedCoroutine（非可取消恢复），块内 "resumed" 日志必然出现，随后 IO 块 lambda 正常求值返回，**由外层 IO 块完成边界的可取消恢复改抛 CancellationException** → `apply` 在取消路径上不可达。
   - **判定：withContext 块内 NonCancellable + 块外 apply 的形态下，被取消扫描的 apply 确实不可达**；notes 三层证据第 1 层（字节码）与已知 coroutines 语义一致，独立复核成立。

4. **五步握手真实性（全文读 + 工件核对）**：
   - 步骤1：真实系统机制 PARTIAL(B)（系统对话框→照片选择器 7 格像素驱动，FPF Phase2/B2MR 原语）+ `parkedRows()==2` 断言真实——武装发生在 PARTIAL、接缝位于 load/projectSnapshot/initialize 之后，rows=2=旧选择集合投影，暂停者身份可证明 ✓（工件 API36 `parked rows=2`，IO 线程 tid 与测试主线程分离=真实生产扫描）。
   - 步骤2：真实权限变化载体 = `pm grant READ_MEDIA_IMAGES`（会话内、grant 不杀进程 + pid 断言）+ Home→am start 真实前台转换，非注入捷径 ✓；store 47 行 shell 双面核对。
   - 步骤3：STALE_BOUNDARY_MARKER 在 tap 前写入、边界后 batch-site-4 批次 id 集==47 池集（截止轮询 30s）；标记被环淘汰→`?: ""`→空集≠池→红灯 **fail-closed**；动作后无新批次事件同样红灯（错误信息明载）——与 B2MR REFETCH_BOUNDARY_MARKER 同构 ✓（工件：`step3c post-boundary batch-site-4 pool equality: 47 ids == full 47 pool` 双 API）。
   - 步骤4：release→`isReleased()`+logcat `resumed rows=2` 双条件轮询 ✓（工件：`released`+`resumed rows=2` 同刻，pid 恒等）。
   - 步骤5：≥5s 观察窗（deck 标记/无门控/无"回收站记录未能读取"对话框/MediaStore 可见面 47 可见性不变量——对话框标题与 TabulaApp error 行均为真实生产呈现面，MainActivity.kt:619/:220 独立证实）+ 确定性重取批（第二次真实 Settings 现值重选，释放标记后**全部**批次行==47 池且至少一条，2 行批次事件=红灯；池 47≤batchSize 50→整池重取，getRandomWalkBatch 可用池+冷却补充逻辑核实确定性成立）+ 清理状态面读回（生产写入口 seedCleanupGroup/acknowledgeCleanupMember/saveAlbumCleanupCheckpoint 均为真实 API，读回断言原样）✓。
   - **覆盖层级登记**：KDoc 明载本类=真实接线级（竞态构造点在生产链内 debug 接缝）、PR50=单测级（参数注入、自成体系）、两级不互相冒充——与执行指令 §三 一致。"取消不误报失败"为 logcat 缺席断言+对话框缺席断言，KDoc 以"PR50 语义的设备面"表述、未声称超证据形态；notes 明载变异红灯未达步骤5 的证明力边界（见 5b）✓。

5. **变异验证审计（重点）**：
   - **变异-1 no-op 诊断成立**：run 43696079300（test/stale-scan-mut1-2026-11-15 @ b19f404）StaleScanLateReturn 段双 API **OK(1)**（工件核实）——指令指定形态（apply 包 `withContext(NonCancellable)` 下游）no-op。三层证据：①字节码层（本报告 §3 独立复核成立）；②单测层交叉（build job 全绿含 PR50）；③本地对照——wg-Stale-mut `app/build/test-results/testReleaseUnitTest/TEST-...MediaSnapshotLoadRaceTest.xml`（2026-11-15 16:45 实文件）解析：tests=1 failures=1，`lateReturningOldReadReturnsButNeverCommits` FAILED（AssertionError"新扫描必须已提交"）——**但该 FAILED 属变异-2 本地实证**（该 XML 在 f3c3a35 状态产生，PR50 用例已 @Ignore 路由其一），notes 第 3 层"变异-1 晚返回用例绿（no-op）"为文字记录、无可复验工件，仅此一项**登记为证据强度弱项**（不影响 no-op 结论：设备段双 API 绿+字节码机理已闭环）。PR50 KDoc 变异形态引用属实：`MediaSnapshotLoadRaceTest.kt:45` 明载"将提交链的 withContext 换成 NonCancellable（移除旧结果提交保护）"✓。
   - **变异-2 检出诚实性成立**：run 43695988574（test/stale-scan-mut-2026-11-15 @ f3c3a35）双 API device job 红——API37 红灯 @ `StaleScanLateReturnDeviceTest.kt:589`（步骤1b `waitFor(张待整理)`）、API36 @ **:622**（步骤2 `waitFor`），与 notes 逐字一致（行号经主分支文件与堆栈双核）；截屏证据（mut2-api37/ui-failures）实际查看：deck 完整渲染、"1 张待整理"稳态文本在屏，而 tree dump **0 字节**（rootInActiveWindow 空）=instrumentation a11y 失联形态。**审计员判定：检出成立但属"检出而红灯位置不符预期"**——红灯在早期 a11y 等待而非步骤5b 目标断言，zombie apply churn 与 a11y 失联的因果链未证实；实现者"因果性未证实、如实登记、不粉饰为目标断言红灯"的口径准确，且单测级检出（本地 XML FAILED）补全了"stale apply 确实执行"的直接证据。**该事实在结论中的权重：不降级本单元交付（主交付五步全绿），不升格为"目标断言红灯完成态"——与 notes 总体判定一致，无虚报。**
   - **变异分支保留**：`gh api` 核实 test/stale-scan-mut-2026-11-15 @ **f3c3a35**、test/stale-scan-mut1-2026-11-15 @ **b19f404** 均在远端；mut 分支 diff 内容与登记一致（824cbad=变异-1、b19f404=修正 cherry-pick、f3c3a35=IO→NonCancellable+PR50 双用例 @Ignore 路由，注释明载 "Never merge"）✓。
   - **失败→修正链**：43692855424（首 dispatch，build 红→rerun 绿→主动取消，现态 build✓/device X 与叙事吻合）、43692861712（master 全绿对照）、43694997490（并发组取消）——gh run list 核对一致 ✓。

6. **CI 与工件抽查**：run 43694969019 success、headSha=61a26f0、workflow_dispatch；`Tabula-VolumeFix-API36/37` 工件下载抽查：StaleScanLateReturn 段 `test.txt` 双 API **OK (1 test)**（单方法 parkedStaleScanReleasedAfterNewCommitNeverOverwritesAndNeverReportsFailure，API36 153.1s/API37 141.4s）；段 logcat 关键行双 API 齐全：`armed`/`parked rows=2`/`step3c ... 47 ids == full 47 pool`/`released`/`resumed rows=2`/`step5b ... no 2-row batch event`/`no TabulaApp load error line`/`step5c ... read back unchanged`/`complete:` 锚/`after cleanup residual=[]`；全程 pid 恒等（20629/20446）✓。wiring-grep.txt 含 complete 锚（API36 核实）✓。

7. **probe/workflow 机械改动**：probe.sh 新段=b2mr 段后追加、`timeout 600s` instrumentation + OK(1) 精确门 + 失败门 + 依赖门不适用（单 instrumentation）+ 按目录兜底清理 + 段末 revoke 卫生；`run-volume-device-probe.py` 1500→1800 + 登记数字（run 43609384927 实测 1150.4s/1174.2s，该 run 经 gh api 核实存在且 success）；workflow job timeout 40→50 同步。无其他行为面 ✓。

## 登记（非阻塞观察项）
1. gateArmed 的 check-then-set 非原子：理论上两扫描同刻到达可同挂一 latch。实际到达协议（键控重启=先取消后启动）使该形态不可达，且 DEBUG-only、误挂场景下释放后恢复期取消检查仍兜底——登记不动。
2. 变异-1 本地"晚返回用例绿"仅为文字记录（无可复验工件）；no-op 结论由设备段双 API 绿+字节码机理独立闭环支撑，不受影响。
3. 变异-2 未达"步骤5 目标断言红灯"完成态：notes 已登记（如需完成态需在变异分支加 a11y 失联恢复，属测试改动、超出授权）——与本审计判定一致，留作后续可选项。
4. mut-1 run 43696079300 API36 job 红为 PartialSelectionPhaseA→PhaseB 级联既有 flake（`阶段B前置... expected:<0> but was:<-1>` 工件核实），与本单元无关，登记属实。
