# 独立复审：PR60 收尾单元（7612f0a→9db3449，主代理执行，2026-11-09 02:00）

复审角色：总协调者会话（未参与该单元实现；实现者=收尾子代理1）。

## 结论：**可合并**（定点 68 用例绿；全量/CI 由协调者统一派发验收）

## 逐项
1. **范围**：恰 3 文件——ImageFeatureCacheStore.kt（生产）、ImageFeatureState.kt（仅注释）、ImageFeatureCacheConcurrencyTest.kt（+2 确定性测试+测试注入钩子）✓。
2. **修复一（等待者共享不可信结果）**：在途条目携带 `CompletableDeferred<ImageFeatures>`——发起者正常完成（含不可信阴性）`complete(features)`、等待者 `return existing.await()` 直接共享；`putIfReliable` 拒绝语义不变（不可信阴性不入可靠缓存）、条目移除后新调用按重试语义开始 ✓。失败=completeExceptionally、取消=mine.cancel(error)：等待者 catch 后 `ensureActive` 区分自身取消（传播）与发起者取消（循环重试）——T11a 取消语义保持（`waiterRerunsDetectionWhenInitiatorIsCancelled` 实测 opens=2 仍绿，无需改动）✓。
3. **修复二（所有权后复查）**：赢得 putIfAbsent 后 detect 前复查可靠缓存，命中即 `complete(cached)+return`——消除初始 get 与领取之间的入库竞态窗；complete 先于条目移除，后来者共享 ✓。
4. **并发序核对**：complete(features) 先于 finally 移除——complete 与移除之间醒来的等待者 await 立即得结果；移除后的新调用走 putIfAbsent 新轮——无丢失结果/无重复检测窗 ✓。
5. **测试钩子**：`preOwnershipGateForTest`（@Volatile internal，生产恒 null，tearDown 复位）拦在初始 get 与 putIfAbsent 之间——T2 用闩锁确定性复现入库竞态（opens=1 零新增流打开）；T1 标定单人轮 K=2 后断言 3 并发恰 K 次打开+三方同一结果实例+缓存拒绝+新轮重跑（perFlightOpens=2 totalOpens=6）✓。
6. **语义保留清单**：取消传播/失败重试/LRU2048/单人不可信重试——既有 5 测试零改动全绿；ui-util 定点 68 用例 0 失败 ✓。

## 登记（非阻塞）
- `preOwnershipGateForTest` 为生产文件中的测试钩子（有 @Volatile+命名划界+KDoc 声明）——T11b 后续若移除需同步测试。
- 全量套件未在本单元跑（协调者统一派 CI 后以 CI 为准）。
