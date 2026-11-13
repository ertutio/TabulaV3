# notes-PA — API36 门禁日志采集诊断（收尾单元 2026-11-10）

分支 `fix/probe-logcat-diagnostics-2026-11-10`（= 整合候选 0dd52eb + 1 commit）。
**提交 SHA：`cee003b`**（已推送 origin；未派 CI、未改 app/，门禁由协调者整合后统一派）。

## 一、定位结论

**根因：套件后证据采集命令 `adb logcat -d -v threadtime -T <窗口起点>` 在 API36 模拟器上
非零退出（rc≠0），不是 timeout=720s，也不是 bash 侧未保护的采集命令。**

- 失败点在 `tools/run-volume-device-probe.py` 的 `window()`（原 line 104）：
  `adb("logcat", "-d", "-v", "threadtime", "-T", before["logcat_since"])`。
  该命令经 `adb()`（check=True）把 **整段 stdout 塞进 RuntimeError** → bash 侧
  `if ! python3 ... window after "$class"` 捕获 → 打印 "Device state or log check failed
  for $class" → `status=1`（脚本继续跑完）→ 最终 `exit 1` → 外层 main()
  `subprocess.run(..., check=True, timeout=720)` 抛 **CalledProcessError**（不是 TimeoutExpired）。
- 两次 run 的确切口径（`--log-failed` 日志行号为 GH log 内行号）：

| run | 套件后触发点 | 时间戳 | 证据 |
|---|---|---|---|
| 42662260995 | `Device state or log check failed for OtherAppMediaAuthDeviceTest`（第 5 个套件后） | 2026-11-09T17:21:03.93Z | log 行 2402；traceback 在行 638-646：`File ...run-volume-device-probe.py, line 104 in window` → `adb logcat -d ...` → `RuntimeError: --------- beginning of system ...`（异常消息为整段 logcat dump，约 1760 行，**末行截断于行中** `...is mapped to sy`，无 adb 错误文本） |
| 42844759640 | `Device state or log check failed for CloudCleanupUiTest`（第 1 个套件后） | 2026-11-10T15:36:54.49Z | log 行 38881；traceback 在行 410-418：同上 line 104 → `RuntimeError: --------- beginning of main ...`（异常消息约 **38470 行**，末行同样截断于行中，无错误文本） |

- 关键形态：两次 rc≠0 但 stdout 都是**先产出了部分 logcat 再中断**（截断于行中、无
  stderr 错误消息）→ adbd/logd 流式读取中断一类的基础设施抖动，而非确定性命令错误
  （同一命令在两次 API37（全绿）与 API36 其余 5 个/5 个套件的 window-after 中都成功）。
- **设备健康判定从未执行到**：`window()` 里 boot_id / SurfaceFlinger PID / DeadSystem
  断言在 logcat 采集**之后**，采集先行失败，门禁是卡在"健康检查的证据采集步骤"，
  不是卡在检测到不稳定。两次 run 全部设备套件断言（OK 计数、FAILURES grep）均绿，
  PARTIAL/FULL 两段也全绿——与"套件全绿、失败在套件后采集段"的用户侧判断一致。

## 二、假设核查

1. **timeout=720s 假设：否证。** 两次外层 traceback 均为 `CalledProcessError`（若是超时
   应为 `subprocess.TimeoutExpired`）。UI 探针段实测耗时（本脚本从启动到 bash 退出）：
   - run 42662260995：17:16:11.9（volume 段结束/首个 pm clear）→ 17:23:58.8（CalledProcessError）≈ **467s**（余量 253s）
   - run 42844759640：15:33:40.0 → 15:42:42.3 ≈ **542s**（余量 178s）
   → 无需调整 timeout；若未来 API36 套件变慢逼近 720s 再另行决策。
2. **"PartialSelection 段采集命令无 || 保护"假设：否证。** 该段（现 lines 240-247）与
   per-suite、FullRecovery、EXIT trap 的全部 logcat/pull/screencap 原本就带 `|| true`；
   本脚本未保护的只有 pre-suite 的 `adb wait-for-device`、getprop 管道、python 夹具
   heredoc——均非"套件后采集"。
3. **tee 管道返回码假设：否证。** 所有 `adb instrument | tee` 都在 `if !`/`||` 保护内，
   `set -o pipefail` 下也被捕获为 `status=1`/`*_status=1`，且两次 run 中 instrument 管道
   全部成功。
4. **`cloud-ui-failures: No such file or directory` pull（两次 run 各出现 2 次）**：
   预期缺席（无失败产物时该文件不存在），带 `|| true`，与门禁失败无关（API37 绿色 run
   中同样出现）。

## 三、已实施改动（cee003b，2 文件 +123/-13）

1. `tools/run-volume-device-probe.py`
   - 新增 `adb_readonly()` + `record_collection_diagnostic()`：只读采集（当前用于
     window-after 的 logcat dump）带 **≤2 次有界重试**；每次失败尝试登记
     （命令/返回码/耗时/输出 head+tail/`adb devices` 连接状态）到
     `device-results/collection-diagnostics.jsonl`，**首败必登记不吞**；恢复成功也登记
     （verdict=recovered-after-retry）。
   - 重试全部失败：整段部分输出留档 `device-results/collection-failure-<cmd>.log`（进
     artifact），RuntimeError 改为**有界摘要**（≤5 行 tail，不再淹没 job log 38k 行）。
   - **门禁效力不变**：采集结果缺失（重试后仍失败）仍抛错阻断完整通过；
     boot_id/SurfaceFlinger PID/DeadSystem 健康判定逻辑一行未动。
2. `tools/run-cloud-ui-probe.sh`
   - 新增 `collect()` 诊断包装，统一接管本脚本全部证据采集（per-suite、PARTIAL、FULL、
     EXIT trap）：登记命令/返回码/耗时/输出摘要/`adb devices` 到
     `device-results/collection-diagnostics.log`，失败输出逐次留档
     `collection-failure-bash-<label>-attempt<N>.log`；logcat -d 类加 ≤2 次有界重试，
     pull/screencap 单次登记不重试（pull 的预期缺席场景无需重试噪声）。
   - 采集失败语义保持非门禁（原 `|| true` 不变），EXIT trap 末尾把诊断 log cat 进
     job log 便于现场可读。
3. 时序统计：因 timeout 假设否证，按任务要求未做阶段耗时入 device-results/ 的改动；
   上述两段实测耗时已在本记录第二节给出，供协调者留档。

## 四、验证

- `bash -n tools/run-cloud-ui-probe.sh` 通过；`python -m py_compile` 通过。
- LF 保持：提交 blob 复核 `.sh`/`.py` 均 LF-only（`.sh` 内 1 个字面 CR 为既有
  `tr -d` 参数，原样保留）。
- 功能仿真（stub adb，本地）：
  - python：3 连败 → 有界 RuntimeError（消息 454 字符，原形态 ~38k）+ JSONL 3 条失败
    证据 + 部分输出留档；1 败 1 恢复 → 返回结果 + 首败与 recovered-after-retry 均登记。
  - bash：失败×2 后恢复 → artifact 正确落盘、attempt1 留档、rc=0；单次失败 rc=3/1 →
    非门禁返回、留档与 stderr 提示正确。
- 提交 blob 复核：`git show HEAD:...` 两文件 LF-only。

## 五、给协调者的决策点

- 无需调整 timeout=720s（根因不在超时；当前余量 ≥178s）。
- cee003b 为纯诊断/重试增强，不改变任何断言口径；整合后统一派 API36 门禁即可，
  若再遇采集失败，`device-results/collection-diagnostics.jsonl`（python 侧）与
  `collection-diagnostics.log` + `collection-failure-*.log`（bash 侧）会给出
  命令级证据（含 rc、输出摘要、adb devices 状态、尝试序号）。
