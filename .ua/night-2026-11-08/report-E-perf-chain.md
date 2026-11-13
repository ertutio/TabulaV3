# E1：PR6→PR42→PR45 性能工具链整体范围审查（主代理执行，2026-11-08 17:02–17:06）

审查基线：master `43a8da1`；三 PR head 逐一 checkout-free 审查（git show/diff 于协调克隆，未动工作树）。
审计角色声明：本报告由未参与三 PR 实现的会话产出。

## 1. 范围独立性（无夹带）

| PR | head | 提交数 | 文件数 | 改动 | 生产代码 |
| --- | --- | --- | --- | --- | --- |
| #6 perf/emulator-baseline-v1 | `eb8b94b8` | 3 | 12 | +959 | **零**（workflow+benchmark 模块+protocol+scripts+docs 计划） |
| #42 fix/perf-tool-fixtures-2026-11-05 | `fa675f27` | 3 | 2 | +190 | **零**（test_report_fixtures.py + .gitignore） |
| #45 fix/perf-trace-validation-2026-11-06 | `c85c2aae` | 6 | 5 | +1565/−6 | **零**（schema 生成器+内嵌 schema 表+report.py 校验链+真实 trace 夹具+测试） |

- U16 登记的误提交 16k 行 proto 源文件已清理：c85c2aae 树中 benchmark/scripts 仅含 generate_perfetto_trace_schema.py(132) 与 perfetto_trace_schema.py(933 生成数据)，无 proto 源残留。
- benchmark/ 路径与各工作组当前写入区（androidTest、tools/run-cloud-ui-probe.sh、app/src/test）零交集；master 无 benchmark/ 目录，合并无冲突面（GitHub 三 PR 均 MERGEABLE）。
- `settings.gradle.kts`（eb8b94b8）不含 `:macrobenchmark`——模块由 build_targets.py 在 CI 临时 worktree 注入，master 构建面不受影响。

## 2. 协议一致性（实测交叉核对）

- 迭代数：protocol-v1.json `iterations{10,10,5,5,5}` = TabulaPerformanceBenchmark `measureRepeated(iterations=…)` = report.py `EXPECTED`。三处一致。
- 动作：12 swipe/迭代、18 步、250ms settle、6 viewer 周期——protocol↔benchmark.kt（advance(12)/steps 18/sleep 250/repeat(6)）一致。
- 事件门：report.py 要求 `rightSwipe=180`（c/d 各 60+f_memory 60）、`viewerOpenClose=31`（smoke 1+e_viewer 30；f_memory 不记 viewer 事件）——与 benchmark.kt 实际 event() 调用点逐处吻合。
- 会话：rounds a/b 反向顺序执行版本（session b labels.reverse()）；两会话环境必需字段相等 + 同 APK（build.json 含 app/test SHA256）强制。
- 编译：CompilationMode.Full，benchmark 变体=release 派生+profileable+非 debuggable（aapt badging 断言）+R8 mapping 非空强制；生产源码树 digest 在构建前后核对（tree_digest 守卫）。
- 身份：baseline 冻结 `474129ed…` 保留于 protocol.json 与 workflow BASE_COMMIT；libraryVersions/metadata 全量记录（build.json）。

## 3. 报告有效性传播（valid 依赖校验全过）

`make_report`→`read_session`→trace 校验链：instrumentation OK(7) → 用例齐全/无重复 → 迭代数逐一核对 → TTID 正数 → 帧 P95 存在 → memory 5×4 阶段×迭代顺序核对 → 事件计数精确 → `validate_round_traces`（每预期用例至少一份可归属 trace，全部文件经 gzip 完整解压 CRC / zstd 显式拒绝 / depth≤32 显式 ValueError / 918 消息 schema 递归实际解析）→ 会话环境必需字段相等 → 跨会话同 APK → 指标 schema 相等。任一失败即 ValueError 传播，CI step 失败，不产出 valid 报告。审计轮 1–5 各反例（压缩、嵌套、深度、框定）均有对应拒绝路径与测试。

## 4. 工作流与构建变体

- `performance-baseline.yml`：`paused` job 为活动门（echo），build/measure/report 由 `if: ${{ false }}` 停用——恢复=删除 paused 门与 if 条件（需单独授权，本次不动）。concurrency 组独立（performance-v1-*），与 build-volume-confirmation 互不干扰。action 均按 SHA 固定。
- report job：`python benchmark/scripts/report.py out report/` 失败即 job 失败；产物 baseline.json/metrics.csv/README.md，保留 90 天。

## 5. 与 master（43a8da1）的适配核对

| 适配点 | 结论 |
| --- | --- |
| 16 个协议 prefs 键（theme_mode…has_acknowledged_disclaimer） | **全部存在**于 master `data/preferences/AppPreferences.kt`（键各 1 处），`tabula_prefs.xml` 文件名一致（grep 计数证据） |
| 卡片定位 contentDescription | master ImageCard.kt:119 `contentDescription = imageFile.displayName`，与 benchmark `descStartsWith("perf_fixture_")` + 数据集命名吻合 |
| 文本合并 | 三 PR 均 MERGEABLE，无冲突 |
| 合并后需核对（登记，不阻塞合并评审） | ①协议枚举**值**（DARK/FIXED/RANDOM_WALK 等）与 master 当前枚举一致；②合并后 build job 的 report unittest 绿 + 下次授权测量时 a0_fixtureSmoke 解码预检实跑；③benchmark 变体 overlay 锚点 `compileOptions {` 仍存在（build_targets.py 已自带守卫报错） |

## 6. 真缺失夹具清单（区分错误实现的负例；建议追加，不阻塞）

1. **F1 事件计数反例**：rightSwipe=179 / viewerOpenClose=32 时 make_report 必须拒绝（门存在于 read_session，无夹具钉住）。
2. **F2 memory 行反例**：rows=19/21、缺阶段、迭代重复的拒绝夹具缺失。
3. **F3 重复 benchmark case**：benchmarkData.json 同 case 两条 → "Duplicate benchmark" 拒绝门无夹具。
4. **F4 环境字段缺失（非不等）**：`environments["a"][field]` 缺字段时抛 KeyError 而非 ValueError——传播仍失败（不产生 valid），登记为错误类型一致性小改进，非缺陷。
5. **trace↔运行来源绑定**：文件名弱归属已声明为协议边界（v2 改进项：测量时内嵌 session 标识），不补夹具。

## 7. 保留 / 适配 / 拆分结论

| PR | 结论 | 说明 |
| --- | --- | --- |
| #45 | **原样保留** | 独立复审已过、35/35 python、校验链完整（审计轮 1–5 反例全部落地） |
| #42 | **原样保留** | 8 项原始夹具全部仍有效（迭代错配/环境/APK/跨源/坏 JSON/缺证据…），PR45 在其上扩展为 32 项；无被替换废弃项 |
| #6 | **适配合并** | 仅需保持 paused 门原样合入（暂停态不变）；其余无适配改动 |
| 拆分 | **不拆分** | 三 PR 单一职责（协议+工作流 / 报告夹具 / trace 校验），堆叠链 6→42→45 顺序合并即可 |

合并顺序建议（需人工评审/合并授权）：#6 → #42 → #45；合并后最小重验 = build job report unittest + （授权后）一次 baseline-only workflow 演练。

## 8. 真实测量前置（维持原门控）

恢复 measure 需授权；工具链已具备完整拒绝条件（坏证据不能产出 valid）。A/A 条件盘点已在 PROGRESS 登记；获授权后按"同工作流同轮跑 baseline+候选、两会话反向顺序"执行。
