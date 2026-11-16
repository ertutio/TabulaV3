# PR59 收尾提交审计（753ba21，2026-11-09）

**总评：PASS。** 两修复声明均成立且实现正确；测试无恒真断言；本地独立复跑两遍全绿。无 P1/P2 问题。

**逐项：**
1. **范围 ✓** — gh pr view 59：head=753ba211584…（=753ba21）与 `git ls-remote` 一致、OPEN；diff 151c545..753ba21 恰 2 文件：AlbumManager.kt +11、ArchiveReconcileTest.kt +168（新增）。PR 累计第 3 文件 ArchiveQueryCountTest.kt 属更早提交，符合"收尾恰 2 文件"。
2. **两出口正确性 ✓** — 失败出口：drain 判定在 claim（失败 action 已出队）之后、COPY_FAILED 返回之前；失败无行落地（copyImageUsingMediaStore 先读后插，失败走 owned-target 清理）→ 真值不变，reconcile 恰覆盖同批先前成功提交的脏标记。并发交错安全：所有置脏路径（applyIncremental）各自带 drain 检查，reconcile 按脏标记循环至收敛，任何顺序无丢失。撤销出口逐行推演：`scope.launch` 在队列锁内启动（不阻塞、reconcile 不取队列监视器→无死锁）；launch 后再入队窗口——reconcile 只键于 `albumsReconciliationDirty` 不看队列，再入队项未提交（无增量、无脏），提前/滞后触发至多对上一批脏标记多做一次全库查询（性能而非正确性），新批提交各自置脏并各自 drain 收敛。
3. **断言真实性 ✓** — 夹具纪律属实：无 resetCopyState，行累计（预置 12 + 插入 ≥100）。触发断言为承重断言：`awaitCollectionGrowth` 所指集合级（非数字段）查询仅 reconcile→refreshAlbumsFromSystem→getAllBucketsWithInfo 产生（copy/identity 查询均为数字项 URI 被过滤）；测试 1/2 中 albums 在首次对账前为空（无 initialize）→ 计数断言 2 无法在对账缺席时通过。封面动态推导与实现核对：复制保留源 DATE_MODIFIED（SystemAlbumSyncManager 写入 source dateModified=1_700_000_000 < 预置 1_700_000_001），桶封面=DATE_MODIFIED DESC 首行 → expectedCover=row 12 系真值推导非硬编码；计数 2/2/3 等于可推导真值。
4. **本地复跑 ✓** — 独立 detached worktree @753ba21（未动实现者工作区），ANDROID_HOME=C:/Users/yuxuan/android-sdk、--no-build-cache：3/3 绿（failures=0 errors=0）；--rerun-tasks 复跑再绿（异步 cancel 路径稳定）。worktree 已清理，wg-T10 保持 753ba21 干净。

**问题分级：**
- P1（阻塞）：无。
- P2（应修）：无。
- P3（观察项，不阻塞）：① cancel 出口对账为 fire-and-forget scope.launch——manager scope 取消时该次对账丢失，但脏标记滞留，下次 drain/刷新自愈（可接受）；② cancel 清空→立即再入队的窄窗至多产生一次批次中途全库查询（性能损耗可忽略）；③ 测试 3 第二次对账的排序/封面断言属"质量回归检查"而非触发检查（触发已由 awaitCollectionGrowth(beforeThird) 钉死）；④ 既有边界（非本次引入）：refreshAlbumsFromSystem 吞异常轮次脏已清、计数靠下次刷新兜底——G3G4 注释已登记。

## 增量复审 b9b5e14（2026-11-10）

**总评：PASS。** diff 23b8711..b9b5e14 仅 ArchiveReconcileTest.kt（+39/-12），判据修正成立。

1. **await 期望仅对账可触达 ✓** — 增量计数=每次 COMMITTED 同步 +1（commitPendingArchive 内先增量后队尾对账，同一协程串行）；逐阶段：测试1 增量止于 1、真值 3（预置12+插入+额外行）；测试2 同为 1/3；测试3 阶段1 1/3、阶段2 增量 3+1=4、真值 5。各 await 期望均高于增量上限，唯一触达路径=reconcile 绝对真值。无提前对账窗口：commit A 队列未清空不触发；额外行均在各阶段队尾出口（fail/cancel/commit）之前注入，对账必见全量真值；对账后无后续减量，无瞬态假阳性。
2. **封面跨阶段断言 ✓** — 归档复制写入源 DATE_MODIFIED=1_700_000_000（insert 800–805 + publish 重申 861；touch-now 助手仅批量 MOVE 路径调用，不在归档复制路径）。真值逐阶段：阶段1 max=额外行 1_700_000_010（>预置 1_700_000_001>复制 1_700_000_000）→ 断言 cover1.dateModified=1_700_000_010 与真值一致且稳健（复制时间戳取默认 1_700_000_003 也不影响）；阶段2 max=额外行 1_700_000_012 → 动态推导 expectedCover 一致。封面=DATE_MODIFIED DESC 首行（getAllBucketsWithInfo 组内保序 + provider 按实时行值排序），实现与推导吻合；删除 cover1==expectedCover 正确（真值变化封面应跟随），排序稳定仍由 namesAfterFirst 钉死。
3. **无新恒真/竞态/过度耦合 ✓** — `rows[id]!!.dateModified=` 直改的是测试自有夹具（var 字段、query 实时读取、DATE_MODIFIED DESC 排序生效），非生产内部结构；010/011/012 互异无并列歧义；await 仍可失败（超时→false）。夹具内耦合，可接受。
4. **观察项（不阻塞）**：测试1 `insertedId` 声明未使用——系 23b8711 已有非本次引入；其余无。**结论：PASS，无需修复。**
