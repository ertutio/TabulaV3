# 独立审计报告：E2（PR45 轮6 delta）/ G1（PR55）/ G2（PR56）/ F2（PR53）

审计者：独立只读会话（未参与实现）。日期：2026-11-08。
方法：仓库 https://github.com/ertutio/TabulaV3 本地只读克隆 + 临时 worktree 实跑验证；CI 用 gh 实查；官方文档 WebFetch 复核。所有"未核对/未复跑"项均已显式标注。

## 单元1（E2）：PR45 审计轮6 delta，提交 14bf35b（父 c85c2aa，分支 fix/perf-trace-validation-2026-11-06）

**总评：通过。** delta 恰一提交且仅改测试文件；6 个负例逐一对照 report.py read_session 的 6 个 raise 点核实真执行对应门；41/41 本地独立复跑通过；做了 4 个门放宽变异（要求≥2），每个变异恰好只有其目标负例转红，其余保持绿——变异区分力成立。

- ①delta 范围 ✓：c85c2aa..14bf35b 唯一提交，diff 仅 benchmark/scripts/tests/test_report_fixtures.py（+80/-2），无 report.py 改动；父提交确为 c85c2aa。
- ②门映射 ✓（对照 audit-wt 实读 report.py）：undercountViewerCycles→L106（!=31）；overcountSwipes→L104（!=180 精确计数）；missingDecodedFixtureSmoke→L108-109（preflight 缺失）；shortMemoryRows→L93-94（rows!=20）；duplicateMemoryIteration→L95-98（构造 20 行总量使总数门先过、命中阶段内唯一性门，构造正确）；duplicateBenchmarkCase→L62-63（重复 raise）。
- ③变异区分力 ✓（4 例，均在临时 worktree 打补丁实跑后还原）：swipe 门 !=180→<180 放宽→仅 overcountSwipes 红；viewer 门 !=31→>31→仅 undercountViewerCycles 红；删除 Duplicate benchmark raise→仅 duplicateBenchmarkCase 红；rows 门 !=20→>20 放宽→仅 shortMemoryRows 红（regex 钉具体门消息，即使下游唯一性门兜底仍转红）。
- ④复跑 ✓：`python -I -m unittest discover benchmark/scripts/tests` → Ran 41 tests, OK（41/41，与声明一致）。

**问题分级：**
- 低：test_duplicateMemoryIterationIsRejected 内 `write_text` 重复调用两次（复制粘贴痕迹，幂等无行为影响）。
- 说明：shortMemoryRows 负例依赖 L93 与 L97 双重防御；已用变异 D' 证实放宽 L93 后该负例仍因消息不匹配转红，区分力不受影响。

## 单元2（G1）：PR55，T10 测量入口，提交 bda4ac2（基 master 43a8da1，分支 test/t10-archive-query-count-2026-11-08）

**总评：通过。** 单提交仅新增测试文件 ArchiveQueryCountTest.kt（+178）无生产改动；断言只钉"每提交集合查询≥1 + 累计严格递增"且演化点已在 KDoc 声明；恢复夹具三项与 StorageStatsManager.loadScanResult 的实际 catch 语义吻合；类级 6/6 独立复跑绿，且实测基线与声明完全一致；CI success。

- ①范围 ✓：43a8da1..bda4ac2 单提交单文件，无生产改动；复用既有 EvidenceTestProvider 接缝（该文件先于 master 存在，末次改动 d9b0e79）。
- ②断言核实 ✓：L90-93 钉"每次 commit ≥1 次集合查询"；L110-112 钉"累计计数严格递增（zipWithNext next>prev）+ 每提交增量≥1"，未冻结 [4,8,12,16,20] 具体数字（由 println 登记基线）；KDoc L33-34 明示"断言只钉可观察事实…具体数字登记在交付说明，优化后重测对比"——若未来引入合并刷新窗口，严格递增断言转红，回归信号成立，演化点已声明。生产链核实：commitPendingArchive（AlbumManager L1339）→refreshAlbumsFromSystem（L1377→L787）→getAllBucketsWithInfo→query(EXTERNAL_CONTENT_URI) 整表扫描。
- ③恢复夹具 ✓：对照 StorageStatsManager.loadScanResult（L266-302）：损坏 JSON→JSONObject 抛异常→catch(Exception)→null；空白→isBlank→null；null→initialize 不改 _scanResult，保持 EMPTY（StorageScanResult.EMPTY scanTimestamp=0L）；有效 JSON→解析回载。三项测试期望与生产语义一致。
- ④复跑 ✓：gradle :app:testDebugUnitTest --tests ArchiveQueryCountTest --no-build-cache → tests="6" failures="0" errors="0"；实测输出 T10-SINGLE-COMMIT collection=4 item=4 total=8、cumulative=[4,8,12,16,20] deltas=[4,4,4,4,4]，与声明基线逐字一致。
- ⑤CI ✓：run 42466964934（headSha=bda4ac2）completed success（gh 实查）。

**问题分级：**
- 低：resetSingleton 为对 ArchiveCommitEvidenceTest private 逻辑的独立复制（文件内已注明缘由），后续可提取共享 helper。
- 说明：声明"本地全量 399/0"未由审计者独立复跑（本次按要求仅类级复跑）。

## 单元3（G2）：PR56，T11b 测量入口，提交 8ab907e（基 master 43a8da1，分支 test/t11b-feature-cache-measure-2026-11-08）

**总评：通过。** 单提交仅新增测试文件 ImageFeatureCacheConcurrencyTest.kt（+179）无生产改动；屏障夹具经推演与实跑均无竞态假红/假绿窗口；single-flight 基线演化点在 KDoc 与测试注释双处声明；untrusted-negative 的 HDR 失败前提与既有测试同款且实测成立；类级 4/4 复跑绿；CI success。

- ①范围 ✓：43a8da1..8ab907e 单提交单文件。
- ②屏障确定性 ✓：AtomicInteger openCounter（原子计数）+barrierLimit=8+固定阈值+CountDownLatch；被阻塞线程在 release 前不可能发起第二次打开→前 8 次流打开与 8 个检测者一一对应，arrivals 满额可确定性判定；所有等待有 15s/20s 上界，无挂死窗口。实跑 totalStreamOpens=8。
- ③single-flight 演化点 ✓：KDoc L41-43"若未来引入 single-flight，本测试将按新行为改写…本文件是基线测量，不是终态规格"+测试内注释 L119-121；若引入 single-flight，arrivals 永不达 8→await 超时转红，回归信号成立。
- ④HDR 失败路径确定性 ✓：与 ImageFeatureDefinitiveTest 同款前提（Robolectric sdk=34、setFastMode(false)、ShadowContentResolver 注册流）；既有 PreloadImageFeaturesCancellationTest L54 已声明"重型检测抛异常→正确地产生不可信阴性"同款前提；实测 untrustedNegativeIsRetriedOnNextCall 通过（hdrKey 不入缓存+第二次流打开计数增长），putIfReliable（definitive||isHdr||isMotionPhoto 才入缓存）与生产一致。
- ⑤复跑 ✓：gradle --tests ImageFeatureCacheConcurrencyTest --no-build-cache → tests="4" failures="0" errors="0"；实测 T11B-CONCURRENT-SAME-KEY totalStreamOpens=8、T11B-UNBOUNDED-RETENTION keys=2000 allRetained=true、T11B-SINGLE-PRELOAD streamOpens=1，与声明一致（2000 键全保留、单次=1、8 并发=8 打开）。
- ⑥CI ✓：run 42467580401（headSha=8ab907e）completed success（gh 实查）。

**问题分级：**
- 低：断言强度弱于声明措辞——并发用例断言 openCounter>=callers（非"恰 8"），单次用例断言 opens>=1（非"=1"）；"恰 8/=1"由屏障 latch 满额语义与 println 登记承载。符合文件"只钉事实、数字登记"策略，语义等价，但读断言本身不能直接读出"恰 8"。
- 说明：声明"本地全量 397/0"未由审计者独立复跑（仅类级复跑）。

## 单元4（F2）：PR53，AGP 8.9.0→8.9.1，提交 4a0f2ce（基 master 43a8da1，分支 fix/agp-8.9.1-api36-min-2026-11-08）

**总评：通过。** diff 恰单行单文件；官方依据在引用页原文复核成立；重派 run 42467710400 三 job 全 success（本次审计实查）；首跑失败证据充分指向模拟器测试基建层（boot 期 adb 失败 + swiftshader 图形错误 + device offline），与 AGP 无关。

- ①diff ✓：43a8da1..4a0f2ce 单提交，仅 gradle/libs.versions.toml 一行：`agp = "8.9.0"` → `agp = "8.9.1"`。
- ②官方依据 ✓：developer.android.com/build/releases/gradle-plugin 的 "Minimum versions of tools for Android API level" 表原文行：API level 36.0 → Minimum AGP version **8.9.1**（另：36.1→8.13.0、35→8.6.0）。注：about/versions/16/setup-sdk 页写"至少 8.9.0-rc01"，为较松表述；声明引用的 gradle-plugin 表格更严且成立。
- ③重派 run ✓（gh 实查，本报告最终状态）：run 42467710400（headSha=4a0f2ce）completed **success**，三 job：build=success、device(36,36,2048M)=success、device(37,37.0,4096M)=success。
- ④首跑失败与 AGP 无关 ✓：run 42466195571 失败 job 为 device(36)；日志证据（--log-failed 实取）：emulator 启动期 "ERROR | Unable to connect to adb daemon on port: 5037"（boot 期基建层），运行期多处 "ERROR | Failed to find ColorBuffer: 431/711/786" + "Failed to bind to texture: invalid color buffer."（swiftshader 图形层），末段 "adb: error: failed to get feature set: device offline"；build job 同 run success；同 SHA 重派三 job 全绿。证据充分。

**问题分级：**
- 说明：声明"本地全量 393/0"未由审计者独立复跑；以 CI build job + 两个 device job 同 SHA 全绿为替代依据。

## 汇总

| 单元 | 结论 | 实跑验证 | CI |
|---|---|---|---|
| E2 (14bf35b) | 通过 | 41/41 + 4 变异区分力 | 不适用（python） |
| G1 (bda4ac2) | 通过 | 类级 6/6，基线 4+4、[4,8,12,16,20] 复现 | 42466964934 success |
| G2 (8ab907e) | 通过 | 类级 4/4，8 打开/2000 保留/单次 1 复现 | 42467580401 success |
| F2 (4a0f2ce) | 通过 | 官方表复核 + 重派三 job 全绿 | 42467710400 success |

唯一跨单元共性小问题：G1/G2 的"本地全量 N/0"与 F2 的"393/0"未独立复跑（本次审计按授权范围仅做类级/定向验证）；均标未核对，风险低。
