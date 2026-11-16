# PR #58 独立审计报告（包D card-mode-lifecycle，仅测试）

- 审计者：独立只读审计（新会话，未参与实现）；日期 2026-11-08。
- 对象：head `c9b7cf0f…99e6`（gh 实查 state=OPEN、commits=1、head 未移动），基 master `43a8da1`。
- **总评：可合并。** 范围/声明/登记/门禁全部独立核实通过，无必须修复项；仅 2 条文档级小瑕疵。

## 逐项结论（对应审计清单 1-7）

1. **冻结与范围** ✅ — `git diff master c9b7cf0 --name-only` 仅 2 个测试文件（新增 DrawCardStateLifecycleTest.kt +179、PreloadingManagerTest.kt +39），`-- app/src/main` diff 为空，+218 行与声明一致，无夹带；gh 确认单提交。
2. **DrawCardStateLifecycleTest 8 项** ✅ 真执行目标断言 — 逐项对照 DrawCardState.kt reducer（:117-176）：Draw 边界拒绝（canDraw false 返回原 state，data class 全字段相等）、发牌记录 (originalIndex,cardId,cardVolumeName) 完整身份并清残留过渡态（:127-140）、Recall 收栈顶+捕获收牌前右卡 deck[i+2].id（:147-162）、右卡越界→null（:151-155）、空栈/顶 no-op（canRecall :78-80）、AnimationComplete 只清过渡态不回写位置/栈（:165-170）、Reset→fresh（:172-174）、往返多轮 cardId/originalIndex 序列不漂移。无恒真/同义反复：`assertEquals(state, after)` 类断言的失败方向=守卫被移后状态推进，可区分。
3. **PreloadingManagerTest +2** ✅ — `inFlightRequestWithSameIdentityIsNotReenqueued`：TestScope 确定性，两轮同窗口 preloadAround 且不派发任何回调（active 3 条、completed 空），断言 enqueued 恒 3；master 原有 20 项逐一核过，此前仅 `successfulPreloadIsNotRepeatedForSameContent` 钉"成功"腿（有回调），在途腿确属缺口，真区分新旧实现。`disposeCancelsProvidedScopeDirectly`：providedScope=TestScope 即管理器 scope（PreloadingManager.kt:39），advanceTimeBy(50) 证防抖在飞→dispose→`assertFalse(runner.isActive)` 直接断言 scope.cancel()（:165），CONCERN-4 闭环方式属实。
4. **无 baseline FAIL 声明** ✅ 成立 — `git diff master c9b7cf0 -- app/src/main` 逐字节为空，故 c9b7cf0 全绿 ⟺ master 全绿；新断言逐条对照生产语义（DrawCardState.kt 全 reducer、PreloadingManager.kt:81/:165）均与现行为一致，master 上不可能红。声明诚实，非人工 FAIL。
5. **N1 登记** ✅ 准确 — SHUFFLE pause:1154/fast:1155、resume:1431-1432、onDragCancel:1506-1514 两者皆无；DRAW 同构 pause:2826/fast:2827、resume:3120-3121、onDragCancel:3203-3212 皆无；resume 全库仅此两处且都在 onDragEnd 内无条件调用，自愈途径（下一次完整滑动/退出 dispose）评估恰当；影响面=预载停摆+fastMode 残留（活性，非正确性）评估正确。
6. **N2 登记** ✅ 准确 — DeckScreen.kt :425 flag=true → :431 getNextBatch（AlbumCleanupEngine.kt:316 实为 suspend）→ :444 flag=false，无 try/finally；flag 复位点 grep 仅 :340/:401/:425/:444，取消后 :401 门永久拦截本 composition 预取；实时获取兜底在 :696-699 存在；"活性、重进页面自愈"评估正确。
7. **其余登记** ✅ — ①连发（CardStack.kt:1769-1783 for 1..4，各次 debounceJob?.cancel 仅末次防抖存活，唯一调用点 :1917，键控 LaunchedEffect :1760 再覆盖）：纯冗余推演成立；③exitingRightCard（:2632）：AnimationComplete dispatch(:2045) 先于 finally isRecallTransitioning=false(:2049)，裸 ID 比较正常时序下确为冗余层（备注：动画协程被取消的极端路径下该层仍兜住残留值，"冗余"指正常路径，登记口径无碍）；④同 size 同 index 不重校验（:1629 键=currentIndex,images.size）：理论洞描述准确。
8. **变异区分力抽查** ✅（2+2，代码推演，未实际改生产——只读约束）— ①移除 PreloadingManager.kt:81 在途守卫：第二轮 3 条重入队→enqueued=6≠3 转红，且现有 21 项无替代（成功腿有回调、失败腿走重试，均钉不到在途）；②移除 :165 scope.cancel()：TestScope.isActive 恒 true→assertFalse 转红；加测 ③移除 AnimationComplete 清理（DrawCardState.kt:165-170）：assertNull(returningCard) 转红（若误回写位置，assertEquals(1,currentIndex) 亦红）；④移除 Recall 右卡捕获（:151-155）：exitingRightCard null≠5L 转红。四个断言均有效区分对应守卫。
9. **独立复跑** ✅ 全对上 — 本地 detach worktree 于 c9b7cf0：三类 gate PreloadingManagerTest **22/22**、DrawCardStateLifecycleTest **8/8**、DrawCardRecallIdentityTest **8/8**（XML：failures=errors=skipped=0）；全量 `:app:testReleaseUnitTest --no-build-cache --rerun-tasks`（--rerun-tasks 真重跑，非 up-to-date 复用）→ **60 类 XML 实计 403 tests / 0 failures / 0 errors / 0 skipped**，与声明完全一致；403−10 新=393 与 master 基线自洽（master PreloadingManagerTest 实数 20、DrawCardRecallIdentityTest 实数 8）。

## 问题分级

- **必须修复：无。**
- 登记（不影响合并，如实性已核）：N1 两模式 onDragCancel 漏 resume()+setFastMode(false)（CardStack.kt:1506/:3203，活性/自愈）；N2 isPreloadingNextBatch 取消卡死无 try/finally（DeckScreen.kt:425→431→444，活性/重进自愈）；①连发纯冗余、③exitingRightCard 冗余层、④理论洞——均与代码一致，维持登记合理。
- 文档级小瑕疵（不修亦可，不阻塞）：notes-D.md 焦点6 行测试名误写 `recallWithEmptyStackOrAtTopIsNoOp`，实际为 `recallWithEmptyStackOrTopOfDeckIsNoOp`；notes-D.md 章节编号"六"排在"五"之前（乱序）。

## 方法说明与边界

- 只读执行：未改动仓库文件；测试在临时 detach worktree（c9b7cf0）独立复跑，已清理（主仓仍在 master 43a8da1 干净态）。
- 未核对/边界：Compose 动画接线层（executeRecallAnimation dispatch 时序、DeckScreen LaunchedEffect 消费）无单测基建，按源码审读核验，与 PR 自身口径一致；变异检验为代码推演非实际执行；无其他未核对项。
