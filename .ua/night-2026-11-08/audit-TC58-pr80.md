# 独立审计：PR #80（test/tc58-hash-recovery-2026-11-15，head 618112d，base=master 60aa6e0，2026-11-15）

审计角色：独立审计员（新上下文，未参与实现）。全程只读；证据=gh PR/diff/run API + CI artifact XML 实核 + wg-TC58 生产码逐条比对。

## 结论：**需修复（2 条最小项，均为测试/登记级；无生产违规、无断言缺陷）**

CI 门禁当前为红：run 43691596178（headSha=618112db…核实一致）build job 的 testReleaseUnitTest 432 项中 1 失败——失败项是**本 PR 未触碰的相邻既有用例**（AlbumCleanupForegroundRefreshTest.directRecycleClearAcknowledgesExistingRecordWithoutEngineBackfill），且生产码与 18 分钟前的绿 run（d03dc08）**逐字节相同、唯一差异即本 PR 新增的测试文件**。归因分析（见 §3）指向一个先于本 PR 存在的测试隔离缺口被新类的插入位置暴露，而非新断言错误；但门禁为红即不可判 PASS，须按 §5 最小项处置后重跑。

## 逐项

1. **范围 ✓** — `gh pr diff 80 --name-only`：恰 1 文件 `app/src/test/java/com/tabula/v3/data/cache/Tc58HashRecoveryTest.kt`（新增 142 行，`new file mode`）；`git diff --stat 60aa6e0 618112d`（真实 merge-base）同结果；app/src/main、app/src/androidTest、probe/workflow 零 diff。分支=master(60aa6e0, PR#78 合并点)+1 提交。

2. **用例 1 temporaryReadFailureRecoversAtNewContentVersion ✓** — 对照生产 `ImageHashCache.kt` 逐条推演，断言真实可失败：
   - FAILED 落盘（saveHash Failed→hash=NO_HASH_MARKER+status=FAILED+ver=1000）后同版本可查询（L74，对 isFailed 判定，若失败被冒充成功则此断言红）；
   - 版本推进后不服务（L77 assertNull，走 isVersionCurrent 等式判定——若实现把失败条目当有效或版本键多版本并存，此断言红；与 ImageHashCacheVersionTest.failedEntryStaysQueryableAtSameVersion 的 v2000-null 断言同构但键为卷限定，属场景前置）；
   - 重算成功落盘后新版本必须服务（L80-83：getHash(2000) 恢复 Success(77)——**既有 8 例无此"失败→恢复"半边，属真实新增覆盖**）；
   - 版本键单向（L86：恢复后 getHash(1000) 仍 null——ver_ 键每身份单值，v2000 写入覆盖 v1000；若实现版本集累积/双向，此断言红）。

3. **用例 2 hashInvalidationAndRecomputeCyclePreservesUnrelatedCooldownAndProgress ✓（断言面）/归因见 §5-1** —
   - 种子同源性核实：`recordImagesPickedByIdentity`（AppPreferences L431，写 tabula_pick_timestamps）、组写四件套 saveAlbumAnalysisResult/saveGroupImageCounts/saveAlbumTotalImages/saveGroupsImageIds（L661/710/675/1091）+ `AlbumCleanupEngine.getNextBatch+saveCheckpoint`（L316/705）——与 AlbumCleanupForegroundRefreshTest L49-50/77-80/96-97/106/285 逐一同源（KDoc"analyzeAlbum 同款组写入口"属实）；`SimilarGroup.id`=成员身份键摘要（SimilarGroupDetector L21-40）。
   - 基线自检（L113-118：冷却命中+新引擎实例 getCheckpointBatch 恢复断点）先于目标操作，排除"种子未落地即通过"的假绿。
   - 目标循环=失效(v2000 不服务 L122)+重算落盘(L123)+cleanupStaleHashes(validKeys={key9})(L124)+有效键保留(L125)；读回面=冷却命中(L128-131)/组键逐成员(L132-136)/断点索引+组身份(L137-139)/永久完成面为空(L140)。
   - 失败模式推演：cleanupStaleHashes 结构上只持 `tabula_image_hash_cache` 单一 prefs 引用、只移除 hash_/status_/ver_ 前缀且后缀不在有效集的键（L187-213）；saveHash 同理单文件。若任一被改清他文件，第一个红的是 L130（冷却）——prefs 文件物理隔离（pick_timestamps/album_cleanup/hash_cache 三文件），断言网络覆盖三文件+引擎断点，钉有效。
   - 逐项读回断言全部带失败消息；两处 assertEquals 键序依赖成员表顺序（members 声明序=写入序）在 Robolectric StringSet 读回路径下与 AlbumCleanupForegroundRefreshTest 同款前提（组尺寸互异防歧义此处 2 成员同尺寸——靠 getGroupImageIds 的 List 存储而非 Set，AppPreferences L1107 返回 List，无序性风险不适用）。

4. **与既有簇分工 + 纪律** — KDoc 声明"既有覆盖（复用不重做）：版本语义/跨卷隔离/失败条目同版本可查询/清理按身份判定；本类补恢复半边+无关面保留半边"与两文件实际断言吻合：ImageHashCacheVersionTest 8 例（roundTrip/legacy-stale/failed-same-version/batch/cleanup-removes-ver/overwrite/cross-volume/cleanup-by-identity）无一覆盖"恢复"与"无关面"；用例 1 前半（L71-77）与 failedEntryStaysQueryableAtSameVersion 有意重述作场景前置、键型不同（卷限定 vs 裸 ID），不算重复造断言。纪律：@Config(sdk=[34], manifest=NONE) 与既有单测同款；无固定 sleep；runBlocking 仅用于 suspend 生产入口；14 条断言中 10 条带消息，4 条基线/中继断言（L83/117/118/122）无消息——与既有测试消息密度一致，非阻塞。

5. **CI run 43691596178 实核（含失败归因）** — status=completed / conclusion=**failure** / headSha=618112db…=PR head ✓。artifact Tabula-VolumeFix-build（id 14152448742）已下载实核 XML：
   - **Tc58HashRecoveryTest tests=2 failures=0 errors=0**；ImageHashCacheVersionTest 8/8、SimilarGroupDetectorVersionTest 2/2、RecommendationEngineContentInvalidationTest 3/3 全绿（"相邻零回归"声明对哈希簇成立）。
   - 唯一失败：AlbumCleanupForegroundRefreshTest 15/16，`java.lang.AssertionError: expected:<[external_primary|1]> but was:<[]>`（directRecycleClear… L178 ack 断言）。
   - 归因（证据链）：a) 生产码+该测试文件与绿 run d03dc08（同日 07:30，artifact 14152411521 同法实核全绿）逐字节相同，唯一 delta=新测试类；b) 类执行序（XML timestamp）：绿 run 中 ImageHashCacheVersionTest→AlbumCleanupForegroundRefreshTest 直接相邻，红 run 中新类恰好插队其间（Tc58 07:51:12 → AlbumCleanup 07:51:13，Gradle 单 JVM 顺序执行、类名字母序）；c) 该失败断言是全类 16 例中唯一依赖 `RecycleBinManager` 生产静态单例跨环境回填可见性的用例：单例 `getInstance` 双检锁**首次绑定后忽略后续 context**（RecycleBinManager L64-70），ack 回填经构造时捕获的 context（beforeDiscard→AppPreferences(捕获 context)→acknowledgeCleanupMembers 仅对该实例 prefs 中已有 groups_ 键的相册生效，AppPreferences L1039-1063）；d) 红 run 中本类 setUp 是**整个 JVM 首个 getInstance 调用方**（此前类核实：MediaSnapshotLoadRaceTest 直构 MemoryRecycleBinStorage 不触单例、RestoreDecisionTest/ImageHashCacheVersionTest 不触）——单例被绑到新类环境，其真实文件 I/O（AtomicRecycleBinStorage）指向已回收的 temp 目录，回填断言落空；绿 run 中首绑者是 AlbumCleanup 类自身首个执行方法（恰为本失败用例，JUnit 方法序首位）→ 自洽通过。
   - 定性：**先于本 PR 的测试隔离缺口**（该单例从未纳入仓库既有 resetSingleton 反射复位纪律——该纪律已用于 AlbumManager/SystemAlbumSyncManager/AlbumUsageTracker），被新类插入位置确定性暴露；非生产回归、非新断言缺陷。但本 PR run 无重跑记录，门禁红以最终结论为准。

6. **TC58 规格映射如实性** — PR 正文登记"重启/旧缓存/专清/推荐消费者面由既有簇证据承担"**主体成立**：旧缓存/版本键=ImageHashCacheVersionTest 8/8（本 run 实核绿）；专清消费入口=U9 引擎消费入口校验（PROGRESS.md U9 条目 + AlbumCleanupForegroundRefreshTest，与 ledger C1.1"TC58 相关注册到哈希簇"一致）；推荐消费者=RecommendationEngineContentInvalidationTest 3/3（staleGroupIsNotServedAfterContentEditWithinTtl 等三例）；SimilarGroupDetectorVersionTest 2/2。**一处不实引用**："专清对账 #59"——仓库 #59=PR/issue T10 归档统计对账（audit-G3G4/INDEX.md 证实），与专清/哈希无关，属登记腿错引（专清面的真实既有证据=U9+GroupIdentityCleanupPersistenceTest，不削弱结论但登记文本须改）。

## 需修复（最小项）

1. **测试隔离复位（仅测试文件）**：Tc58HashRecoveryTest 增加按仓库既有 `resetSingleton` 反射惯例复位 `RecycleBinManager.instance`（建议连同 `AppPreferences.instance`）于 @After/setUp 头，消除跨类绑定足迹；或对称地在 AlbumCleanupForegroundRefreshTest setUp 加同款复位（任一即可解除顺序耦合，二者都做更稳）。完成后同 head 或后继提交重 dispatch CI，以 build job testReleaseUnitTest 全绿为合并判据；重跑后若 directRecycleClear… 仍失败则升级为独立缺陷单（与本 PR 解耦处理）。
2. **登记文本更正（仅 PR 正文）**："专清对账 #59"改为实际承载面（U9 引擎消费入口校验 AlbumCleanupForegroundRefreshTest + GroupIdentityCleanupPersistenceTest）；#59 系 T10 归档统计对账，与 TC58 专清面无关。

## 登记（非阻塞）

- 失败归因为静态+CI 证据链推定（红/绿各 1 样本、机制逐码核对一致），未在本机复现（审计全程只读，不运行 Gradle）；重跑 CI 即同时验证归因。
- cleanupStaleHashes 当前无生产调用点（ImageHashCache KDoc 自述），测试将其作为"有界策略"钉住与 TC58 文本一致；生产接线属后续单元。
- 4 条无消息断言（L83/117/118/122）与既有测试密度一致，不要求本轮补齐。
