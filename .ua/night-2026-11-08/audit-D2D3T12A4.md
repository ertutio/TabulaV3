# 独立审计报告：单元 D2(PR#61) / D3(PR#62) / T12(PR#63) / A4(PR#64)

审计者：独立只读会话（未参与实现），2026-11-08。基线 master `43a8da1`。
方法：gh 实查 4 个 PR diff/提交/CI；本地读源码核验语义（PreloadingManager.kt、ImageFeatureDetector.kt、CardStack.kt、DeckScreen.kt、StringDragSelectState.kt）；PR#63 新测试本地实跑（worktree @5c77fc0，gradle --no-build-cache）。

---

## 单元1：PR #61（D2，N1/N2 活性修复）— **总评：可合并**

分支 fix/deck-liveness-cancellation-2026-11-08，单提交 `af7f406`，恰 2 文件（CardStack.kt +8/-0，DeckScreen.kt +21/-17）。

- ① diff 范围：与声明一致；N1 在两处 onDragCancel（shuffle ~:1506 / draw ~:3203）各加 `preloadingManager.resume()` + `ImageFeatureDetector.setFastMode(false)`，与两处 onDragEnd 既有调用（master :1431-1432 / :3120-3121）**逐参数一致**（resume() 无参、setFastMode(false) 同值同序）。缺陷前提代码级属实：两模式 onDragStart 均 `pause()`（:1154/:2826），原 onDragCancel 无 resume → 取消后 isPaused 卡 true 至下次滑动结束。
- ② N2 try/finally：代码位于 `LaunchedEffect(currentBatch, currentIndex, ...)` 可取消协程内，`getNextBatch` 确为 suspend（AlbumCleanupEngine.kt:316）；finally 内仅布尔赋值、**无挂起调用** → 取消时必执行且不吞 CancellationException；正常路径标志复位时点与原代码相同（try 体完成后），语义零变化。
- ③ 正常路径零影响：onDragCancel 仅取消时触发；`resume() = isPaused.set(false)`（PreloadingManager.kt:58，幂等）；`setFastMode` 为布尔字段写（ImageFeatureDetector.kt:39-41，幂等）——即使误触发也无副作用。
- ④ CI 42469258780（head af7f4067）：build + device(36) + device(37) 三 job 全 success。
- 问题分级：**无必须/建议修复项**。P4 登记：onDragCancel 恢复口径与 onDragEnd 仍靠两处字面量手工同步，未来单侧改动易再漂移（建议后续抽公共恢复函数，不阻断）。

## 单元2：PR #62（D3，preloadNextCards 连发清理）— **总评：可合并**

分支 fix/draw-preload-burst-cleanup-2026-11-08，单提交 `402cae7`，1 文件（CardStack.kt +11/-10）。

- ① 等价性**独立核验成立**：PreloadingManager.preloadAround（PreloadingManager.kt:60-88）为替换式防抖——每次调用 `generation++`、`debounceJob?.cancel()` 后重launch（delay 100ms），job 体以调用时 currentIndex 计算窗口并受 `runGeneration != generation` 守卫（:70）。原循环 4 次调用在 `scope.launch` 内同步连发（无挂起点，间隔微秒级 ≪ 100ms），前 3 个防抖 job 必被 cancel；即使假设取消失效，generation 守卫也令陈旧 job 体直接返回——可观察行为=仅最后一次（最远有效索引）生效，前序从不入队。防抖语义为"替换"而非"排队合并"，等价性前提成立。
- ② 越界回退逐情形对照一致：fromIndex+1 ≤ size-1 时原存活索引 = min(fromIndex+4, size-1) = 新 furthest；fromIndex ≥ size-1 时原零调用、新 `furthest >= fromIndex+1` 不成立即 no-op；空列表（furthest=-1）同 no-op。cardSize 均为 IntSize(1080,1440)。
- ③ 调用点唯一：全仓 grep 仅 CardStack.kt:1917（executeDrawAnimation 内 `preloadNextCards(drawState.currentIndex + 1)`）；既有 PreloadingManagerTest 防抖语义测试在该 head CI 全绿。
- CI：分支首跑 42469522410 失败于 device(36) "Exercise actual MediaStore and old pending records" 步骤——与 preload 改动无关的设备步骤抖动；**同 head** 重跑 42471294282 三 job 全绿。
- 问题分级：**无必须/建议修复项**。P4 登记：等价性依赖"同步连发+防抖替换"隐式前提，若未来把调用改为跨帧分散（语义变更）需重新论证，非本清理引入的风险。

## 单元3：PR #63（T12，EXIF 提示回归 + T09 证据落库）— **总评：可合并**

分支 test/t09-t12-evidence-2026-11-08，单提交 `5c77fc0`，2 文件（新测试 +155、docs +71）。

- ① 对照有效性成立：detectHdr（ImageFeatureDetector.kt:58-134）步骤序 = MediaStore 标志(:67) → XMP(:72) → **EXIF 提示(:77) → definitive 门槛/重型解码(:82-95+)**，提示命中即在重型检测前 return true 且 definitive 保持 true；无提示 JPEG 落入重型路径，Robolectric 下不确 conclusive → onInconclusive → definitive=false，与 ImageFeatureDefinitiveTest:42-50"阴性不可信"前提同型。对照用例断言正确。
- ② EXIF 写入有效性：互证结构成立——若 saveAttributes 静默失败则提示词缺失 → 走重型路径 → definitive=false 或 isHdr=false，阳性断言必红。提示词匹配独立核对：检测端 lowercase 后 contains("hdr"/"ultra hdr"/"pro xdr"/"hdr10")（:368-376），四个阳性词均命中。**本地实跑 5/5 全绿**（tests=5 failures=0 errors=0，含缓存路径用例 preloadImageFeatures→putIfReliable→CacheStore.get 非 null），saveAttributes 静默失败疑虑排除。
- ③ CI 42470313300（head 5c77fc05）：三 job 全 success（build job 含 testReleaseUnitTest）。
- docs/toolchain-compat-2026-11-08.md 仓库内事实抽查一致：AGP 8.9.0 / Kotlin 2.3.0 / Gradle 8.11.1 / coil 2.7.0 / coroutines 1.9.0 / exifinterface 1.3.7 均与 libs.versions.toml、wrapper properties 相符；media3 确为 catalog-only（app/build.gradle.kts 与 main 源码无引用）。**官方文档外链口径（AGP 兼容表、Play 政策日期）未独立核对**——属登记性文档、非代码，不阻断。
- 问题分级：**无修复项**。

## 单元4：PR #64（A4，itemBounds 离场修剪）— **总评：可合并**

分支 fix/dragselect-bounds-prune-2026-11-08，单提交 `2f2ae5f`，2 文件（StringDragSelectState.kt +18/-2、新测试 +63）。

- ① 修剪键集正确：`LaunchedEffect(items)` 内 `pruneBoundsNotIn(items.mapTo(mutableSetOf(), itemKey))` —— 键集来自**完整数据列表**。三个调用点逐一核实：AlbumViewScreen:423 / RecycleBinScreen:186 传 `sortedImages`（全集）、CategorizedAlbumsView:127 传 `selectableIds`（全集），均非 composed 可见子集；可见行键 ⊆ 全集 → 不可能误删。
- ② 线程/重组安全：onGloballyPositioned（帧内布局阶段）与 LaunchedEffect 体（apply 后主线程 effect 调度）均在主线程，itemBounds 单线程访问，retainAll 无并发窗口。prune 至下一布局回调之间保留键使用旧 bounds——与改动前行为相同，无回归。
- ③ 重启频率可接受：LaunchedEffect key 经 equals 结构比较 → 仅 items 内容真变化时重启；每次重启 O(n) retainAll（n=已登记边界数）+ O(n) List equals，与既有 rememberUpdatedState(items) 的比较开销同阶，无性能回归。
- ④ `findItemAt` private→internal：同模块可见性放宽，函数体零改动，无行为影响。
- ⑤ 3 例回归有效：直接构造 state→注册边界→prune→断言（键集收敛 / findItemAt 不再命中离场区 / 幂等+未知键安全）；CI build job（含 testReleaseUnitTest）在 2f2ae5f7 全绿，覆盖通过（未本地复跑，依据 CI）。
- 问题分级：**无必须/建议修复项**。

---

## 交付结论

| 单元 | PR | 总评 | 必须修复 | 建议修复 | P4 登记 |
| --- | --- | --- | --- | --- | --- |
| D2 | #61 | 可合并 | 0 | 0 | 恢复口径字面量重复（抽取公共函数） |
| D3 | #62 | 可合并 | 0 | 0 | 等价性依赖防抖替换语义的前提说明 |
| T12 | #63 | 可合并 | 0 | 0 | docs 外链官方口径未独立核对 |
| A4 | #64 | 可合并 | 0 | 0 | — |

四单元均通过独立核验：PR#61 恢复调用与 onDragEnd 逐参数一致、finally 不吞取消；PR#62 等价性经 PreloadingManager 防抖实现双重确认（cancel + generation 守卫）；PR#63 本地 5/5 实证 saveAttributes 有效且对照前提成立；PR#64 修剪键集为全集、主线程单访问、键比较为结构相等。CI：#61=42469258780 绿、#63=42470313300 绿、#64=42470315863 绿、#62 同 head 42471294282 绿（首跑 42469522410 的 device(36) 失败为无关步骤抖动）。未核对项仅 PR#63 文档中的官方文档外链结论。
