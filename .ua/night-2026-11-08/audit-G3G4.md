# 独立审计报告：PR #59（G3/T10）与 PR #60（G4/T11b）

审计者：独立只读审计（新会话，未参与实现）。日期：2026-11-08。方法：gh 元数据核对 + diff 逐行审读 + 临时 worktree 本地复跑（--no-build-cache，ANDROID_HOME=~/android-sdk）+ 回退反转实验（临时 worktree 内改代码验证测试转红，未触碰任何分支/远端）。CI：两 PR 均无 checks 上报（仓库未跑 CI），以本地复跑为准。

---

## 单元1：PR #59 — fix/t10-merge-archive-stats-refresh-2026-11-08 @ 7f783df

**总评：可合并**（1 项 P2 建议修复，非阻塞；无必须修复项）

- ① 范围与 head：**通过**。gh pr view 59 headRefOid=7f783df22c9...与声明一致（OPEN/MERGEABLE，base=master）；diff=bda4ac2..7f783df 恰 2 文件（AlbumManager.kt +46/-13 行内含测试；ArchiveQueryCountTest.kt），单提交，无夹带；其他 refreshAlbumsFromSystem 调用点（建册210/改名242/删除282/隐藏306/封面268 等）未动。
- ② 并发正确性：**通过（附 P2）**。`_albums.update{}` 是 StateFlow CAS 原子读改写，增量之间无丢失；但 refreshAlbumsFromSystem 末尾是 `_albums.value = albums` 全量赋值（非 update）——另一线程（建册/改名/删除等）的重建若快照早于本次复制入 MediaStore，会在赋值时覆盖内存增量。后果评估：**短暂计数陈旧，非持久错误**——批次末对账从 MediaStore 重数必然包含已复制文件（复制先于增量），且建册/改名/删除/隐藏/下一批次 drain/重启均触发重建自愈；复制/账本/证据等数据面不经 _albums，无丢失。**P2（唯一实质发现）**：reconcileAlbumsIfDirty 的 `finally { dirty=false }` 在刷新**之后**清脏，存在丢失唤醒窄窗——对账 R 刷新期间提交 C 落盘并置 dirty=true 后阻塞于互斥，R 完成刷新清脏放锁，C 双检见 false 直接返回，C 的对账被吞（其内存 +1 若已被 R 的赋值覆盖则计数少 1，直到下一次任意全库刷新）。复现场景：批次 drain 对账进行中，另一 snackbar 超时提交最后一张并 drain（MainActivity 三处调用点均在 lifecycleScope，Dispatchers.IO 并发真实可达）。静态分析结论，未运行时复现（时序窗为刷新耗时量级）。修复一行级：进锁后先 `dirty=false` 再刷新，或 while(dirty) 循环。附带同因小点：refreshAlbumsFromSystem 内部 catch 全部异常，刷新失败仍清脏不重试——同属短暂陈旧类，收敛性同上。
- ③ 批次边界：**通过**。drain 判定后新 action 入队 → 该 action 自己提交时 drain 再判定触发对账，无遗漏；两次 drain 重叠 → 第二次双检失败空返回，重复对账幂等（至多多一次查询）；COPY_FAILED 在增量/置脏之前 return，失败不产生文件不需对账，正确；cancelLastPendingArchive 只出队不清脏，最坏多一次对账；对账中途协程取消：增量已生效（计数本就正确），脏标记留给下一 drain，良性。
- ④ 单飞互斥：**通过**。互斥 + 双检保证并发两次 drain 至多一次全库刷新（②中 P2 是第三线程置脏被吞的窄窗，非两次 drain 重复刷新）。
- ⑤ 排序一致性：**通过**。三种排序模式均以 imageCount 为尾键（870/877/880），但增量用 `list.map` 保序不重排——中段仅徽标数字变化、位次随批次末对账收敛，属声明内"细节随对账收敛"；`copy(imageCount+1)` 按 name 匹配，且空图集合并有 `!in systemBucketNames` 去重（826 行），无同名双增。
- ⑥ 测试演化：**通过（反转实验证实）**。worktree 内将成功路径还原为每提交 refreshAlbumsFromSystem 后重跑：perCommit=[4,4,4,4,4]、singleBaseline=4，`sum 20 < 20` 为假 → fiveQueuedThenCommitted...FAILED（断言 100 行）；同时 `last>=1` 钉住 drain 对账必须发生、删对账也会转红。基线首提交无预热膨胀（=4，与后续同价），无假阴性回退。相邻正确性测试（恰好一次复制）在回退下仍绿，符合预期。
- ⑦ 本地复跑：**通过**。ArchiveQueryCountTest 6/6、AlbumIdentityMoveCopyTest 9/9、ArchiveCommitEvidenceTest 7/7；全量 399/0（复现声明）。基线标注：ArchiveQueryCountTest 不在 master（由堆叠底座 PR55 分支 bda4ac2 引入），其余两类在 master 基线。

## 单元2：PR #60 — fix/t11b-single-flight-bounded-cache-2026-11-08 @ 7612f0a

**总评：可合并**（无 P1/P2 阻塞项）

- ① 范围与 head：**通过**。headRefOid=7612f0af51...与声明一致（OPEN/MERGEABLE）；diff=8ab907e..7612f0a 恰 3 文件（ImageFeatureCacheStore.kt / ImageFeatureState.kt / ImageFeatureCacheConcurrencyTest.kt），单提交，无夹带。
- ② single-flight 并发正确性：**通过**。逐点核对：(a) 发起者 finally 次序 `inFlight.remove(key,mine)` 先于 `mine.complete(Unit)`——remove 与 complete 之间到达的新调用方 putIfAbsent 成功自跑检测（不等待），已持有 `existing` 引用的等待者 await 已完成/即将完成的 deferred，两者都无永久等待；putIfReliable 在 complete 之前执行，等待者醒来循环头 get 命中共享结果。(b) mine 只会 complete(Unit)（从不 completeExceptionally），发起者失败（detect 抛异常）时 finally 仍走 remove+complete，等待者正常醒来→缓存 miss→自跑重试，语义正确；不可信阴性不入缓存 → 等待者重跑，与 T11a 重试语义一致。(c) 等待者自身取消：await 抛 CancellationException → `ensureActive()` 重抛向上，不吞；若收到非自身取消的 CancellationException（本实现中 mine 不会取消，防御分支）ensureActive 通过后 continue 重试，安全。(d) 活锁：每轮在途条目必被移除（finally 无条件），等待者下轮 putIfAbsent 必成功轮转为发起者，每波恰一次检测有进展；反复波次仅在检测失败/不可信阴性时发生，实际有界。无 cache 永不命中且 inFlight 永不空的活锁路径。
- ③ LRU：**通过**。LinkedHashMap accessOrder=true，get/put 均在 synchronized(lock) 内（访问即重排，含 get）；淘汰循环用 entries 迭代器 next()+remove() 删队头最久未用，稳态 size==MAX 时 put 进 1 淘 1；get 不增长只重排，不变式 size≤2048 每次 put 后成立。图库 >2048 时保最近访问、老键 miss 后重检测（检测幂等只读），无正确性损失。
- ④ T11a 回归：**通过**。取消不落缓存：detect 抛 CancellationException 时 putIfReliable 不执行、finally 仍清在途表（被取消的发起者无待跑结果，等待者接管重跑——waiterReruns 测试钉住）；definitive 入缓存与失败对照语义未改（putIfReliable 可靠性规则原样）；rememberImageFeatures 仍经 remember(cacheKey) 同步回缓存值防闪烁；两调用点均接线 getOrDetect。复跑 PreloadImageFeaturesCancellationTest 3/3、ImageFeatureDefinitiveTest 2/2、相邻 ImageFeatureCacheStoreTest 7/7 全绿。
- ⑤ 本地复跑 + 反转实验：**通过**。ImageFeatureCacheConcurrencyTest 5/5（并发同键恰 1 次流打开+入缓存、发起者取消→等待者重跑≥2 次打开且结果入缓存、LRU 淘汰最老保留最新、单次基线、不可信阴性重试保持）；全量 398/0（复现声明）。反转实验：worktree 内还原 putIfReliable 为无界 + preload 为旧 get/detect/put 内联 → 恰 2 项新测试转红（concurrentSameKey...opens≠1、cacheEvicts...eldest 未淘汰），钉住生效；其余 3 项在回退下仍绿（合理，语义未变）。

## 分级汇总

- P1（必须修复）：无。
- P2（建议修复，非阻塞）：PR#59 脏标记丢失唤醒窄窗（详见 ②，含复现场景与一行级修复建议）；同因附带"刷新失败仍清脏不重试"。
- P3（备忘）：PR#59 测试文件类头 KDoc 仍写"T10 测量入口…钉住两项当前行为事实（线性增长）"，与改写后断言（合并语义）已不符，属文档陈旧；两 PR 无 CI checks，全量结论依赖本次本地复跑。

## 未核对项（诚实声明）

- PR#59 P2 竞态为静态分析结论，未在运行时构造确定性复现（时序依赖）。
- 未运行 androidTest/instrumented（两 PR 声明与范围均仅涉 JVM 单测）。
- PR#60 未做逐路径延迟/内存基准（MAX_ENTRIES=2048 的容量收益仅由 LRU 测试间接钉住）。
