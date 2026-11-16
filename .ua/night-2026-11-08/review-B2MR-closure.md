# 独立复审：PR79 B2MR 收口单元（d03dc08，验收表述修正 + 批次日志动作边界，2026-11-15）

复审角色：独立只读复审员（新上下文，未参与实现；wg-B2 worktree @ d03dc089a603db9c2c49f6e59236b33506d30adb）。全程未修改任何文件、未 push、未 dispatch CI；唯一写操作 = 本报告 + 工件下载至 scratch（ci-r5/）。

## 结论：**PASS**（表述修正与动作边界均达标，CI 双 API 实证；附 3 条非阻塞登记项）

## 逐项

1. **改动面**：`git show d03dc08 --stat`/`--numstat` 恰 2 文件——B2MediaAccessRevisionDeviceTest.kt（74+/25-）+ docs/notes-B2MR-2026-11-14.md（18+/1-）。**app/src/main、app/src/test、tools/run-cloud-ui-probe.sh、run-volume-device-probe.py、workflow 零 diff**（probe 预算 1200→1500s / 30→40min 等均为 46da489 既有口径，本提交未触碰）✓。

2. **A① 表述修正（§二.1）**：
   - 方法名 `partialToFullUpgradeViaInSessionGrantRerunsScanAndRefusesStaleResult` → `...ConsumesFullPool`（L447）；完成日志改为 "post-boundary batch event = full 47 pool committed and consumed (final-state wiring; strict stale-race not constructed here, registered)"（L691）；类头（L29-33）、分工（L38）、取消语义（L50-53，登记为读码+PR50 单测钉住、本类不作设备级断言）、步骤5（L74-77，第二次重启=时序依赖启发式而非判别器）、断言 c 注释（L87-91 终局提交面判别 + 瞬态覆盖不可检测如实登记）全部为终态口径 ✓。
   - 残留 grep（stale/Stale/refused/拒绝/不覆盖/真实触发/终局判别器）：测试文件零超证据残留。余下命中均为允许语义——L91/L203 "拒绝'仅有动作前正确日志'"（fail-closed 边界行为描述）、L95 "旧读取晚返回不覆盖"（如实登记段引用被校正表述并显式声明不由本类断言）、L100 "终局判别（最终提交池=FULL 全池）"（实际持有的终态判别）✓。
   - 保留断言面完整：真实权限变化（会话内 grant，L551-566）、同进程 pid 恒等（L515/546/566/579/680）、47 项集合面无重复无缺失（L582-588）、生产消费池 batch-site-4 池集相等（L652-673）、夹具自清（L688-690 + @After）✓。

3. **A② 动作边界（§二.2）实现正确性**：
   - 边界标记写入位置正确：`Log.i(TAG, "$REFETCH_BOUNDARY_MARKER before recommend-mode reselect tap")`（L642）在 `ui.clickNode(option, ...)`（L643）**之前**——动作自身批次事件包含在边界后 ✓（若在动作后则必假红）。
   - `latestBatchLogLineAfterBoundary()`（L393-400）：`logcat -d -s DeckScreen B2MediaRevision`（同 dump 两 tag）→ `indexOfLast{contains(marker)}` → `<0 → null`；否则 `subList(markerIdx+1).lastOrNull{contains(BATCH_LOG_ANCHOR)}` ✓。轮询 30s（L655-663），超时 `error()` 消息列明"动作后无新批次事件/仅有动作前日志"（L667-672）→ **fail-closed** ✓。47 项池集相等逻辑未被削弱（L652-654 期望集仍取 storeAfterFill 全 47 名 _id）✓。

4. **边界可靠性论证评估（技术漏洞排查）**：
   - 单 logcat main 缓冲 append 序在同 dump 内单调：成立。生产批次行 = DeckScreen.kt:154 `logBatchRows` → `Log.v("DeckScreen",...)`，与测试 `Log.i("B2MediaRevision",...)` 同进程（instrumentation 同进程，L515 pid 恒等 + 工件实证同 pid）；Log.i(marker) 同步写完成后才 clickNode，批次行写入必然后到达 logd，无乱序路径 ✓。
   - 不用墙钟（NTP/校时跳变 + brief 无毫秒）、不用 pid 过滤（序列边界已排除跨时污染）理由如实 ✓；标记被缓冲环淘汰 → indexOfLast=-1 → null → fail-closed 如实登记 ✓。
   - 排查通过的技术点：(a) 批次单行 47×~55B ≈ 2.6KB < logd 单条 payload 上限 4068B，不截断（run 43604993882 起实证）；(b) `logcat -d -s TAG1 TAG2` 多 tag filterspec 为标准用法（-s 置 *:S 基线、位置 tag specs 优先），双 API CI 实证输出含两 tag；若未来 logcat 行为反转致全静默 → null → 红灯（fail 方向安全）；(c) `ui.shell` = UiAutomation.executeShellCommand 设备内直连（PartialSelectionUi.kt:82-86），不经宿主 pty，LF-only 无 CR 干扰，`.use{}` 读尽流无阻塞；(d) 跨轮重跑残留旧 marker——indexOfLast 取最新 marker，语义仍正确 ✓。
   - 未发现会造成假绿或证据失效的漏洞。

5. **notes §10 一致性**：A①/A② 记录与代码逐条一致；"首版标记误置（tap 之后→已移至 clickNode 之前）"如实记录 ✓。probe b2mr 段（tools/run-cloud-ui-probe.sh:633-679）未动，OK(1) 精确门 + 失败兜底清理保持 ✓。

6. **PR #79 正文核对**："任务 3"段标题即"真实 PARTIAL→FULL 刷新链**终态**接线验收"；"实际已验证行为（表述校正，用户收口指令 §二.1）"明确写"同进程内最终向推荐批次提供完整 47 项集合"、登记竞态语义归 PR50+FPF Phase3、瞬态覆盖不可检测；"批次日志动作边界（§二.2）"段与代码实现一致（tap 前写标记/无边界 fail-closed/依据登记于常量 KDoc）。**无超证据表述** ✓。

7. **CI run 43690226527（@d03dc08）**：completed / **success**（build + device(36) + device(37) 三 job），headSha = d03dc089a... 与复审 head 一致。工件实核（ci-r5/api36、ci-r5/api37）：
   - API36：`OK (1 test)`；时间序证据链 `07:56:24.021 B2MediaRevision: b2mr-refetch-boundary: before recommend-mode reselect tap` → `07:56:24.239 DeckScreen V: currentBatch<-[batch-site-4]: content://...`（同 pid 18609）→ `07:56:25.927 step5c post-boundary batch-site-4 pool equality: 47 ids == full 47 pool` → complete 校正表述行 ✓。
   - API37：`OK (1 test)`；`07:57:18.096` marker → `07:57:18.363` batch-site-4（同 pid 18885）→ `07:57:19.855 step5c ... 47 ids == full 47 pool` → complete 行 ✓。
   - 即：边界标记后的新批次事件被实测接受（47 池集相等），边界机制在真实 CI 上按设计工作。

8. **旧锚残留交叉核对**：`RefusesStale` / `latestBatchLogLine\b` 在 app/ 全零命中；仅 notes §1（L14，初版登记史实）与 §10 更正记录（L119/L125，被校正表述的引用上下文，允许）✓。

## 登记（非阻塞，3 条）

1. **孤儿旧 KDoc**：B2MediaAccessRevisionDeviceTest.kt L387 保留旧一句话注释 `/** 最新生产批次日志行（logcat -d -s DeckScreen 全缓冲尾行；无则 null）。 */`，紧跟新 KDoc（L388-392）形成双注释块——描述旧 whole-buffer 语义，与现实现不符。纯注释残留、不含超证据宣称、不影响行为与证据，建议随下一提交顺手删除该行。
2. **notes §9 行字节级顺带变化未登记**：该行内原字面 CR 字节（0x0D，83e3ccf 引入）在 d03dc08 被替换为 LF（一行拆两行，numstat 18+/1- 吻合）。中性清理（文件不再含裸 CR），probe 文件本身未触碰；但该行记录的 `tr -d '\r'` 转义形态在两版 notes 源码中都未以文本如实表达（probe 实际为 backslash-r 字面，见 run-cloud-ui-probe.sh L66 等 9 处）。建议 §10 补一句登记。
3. **notes §10 "run id 见 §11" 悬空引用**：notes 文件止于 §10，§11 尚未写（run 43690226527 现已 success，PR 正文亦已登记该 run）；建议补写 §11 登记 run id 与双 API 工件证据。

## 判定依据摘要

用户收口指令 §二.1（超证据表述校正为信息流终态口径、保留真实权限变化/同进程/完整集合/生产消费池/夹具清理断言）与 §二.2（批次日志池集相等只认触发动作之后的新事件、tap 前写边界标记、无边界/无新事件 fail-closed、47 项集合相等保留）在代码、注释、notes、PR 正文四面均落实，且由 run 43690226527 双 API OK(1) + post-boundary 时间序证据链实证。三条登记项均为文档级残留，不构成违规或假绿风险。
