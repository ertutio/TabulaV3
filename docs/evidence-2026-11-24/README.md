# 证据清单（2026-11-24 夜，模拟器 CI 原始产物）

这些文件是 CI artifact 的**原样拷贝**，未做任何编辑，供排查时核对。

对应 run：

| PR | HEAD | run | 结论 |
|---|---|---|---|
| #91 | `c8c21f5` | [45414111617](https://github.com/ertutio/TabulaV3/actions/runs/45414111617) | verify ✅ / API31 ✅ / API33、API35 环境中断 |
| #92 | `ae585b0` | [45421997727](https://github.com/ertutio/TabulaV3/actions/runs/45421997727) | verify ✅ / API31 ✅ / API33、API35 断言失败 |

| 文件 | 来源 | 说明 |
|---|---|---|
| `pr92-api33-probe-summary.json` | #92 API33 | 探针 23 项检查，**全部 `ok=true`**，`success=null`（增量落盘的半截文件） |
| `pr92-api33-error-summary.json` | #92 API33 | 同一轮，带 `environment_unavailable`：`uiautomator dump -> 1 / device 'emulator-5554' not found` |
| `pr92-api33-instrumentation-viewer.txt` | #92 API33 | **卡点 A 完整栈**：`timed out waiting for 回收站` |
| `pr92-api33-verdict.txt` | #92 API33 | 汇总判定：`probe(checks=23,failed=0) ; viewer(exit=1) -> exit 1`（断言失败） |
| `pr91-api33-error-summary.json` | #91 API33 | 同一轮，探针跑到后半段掉线 |
| `pr91-api33-verdict.txt` | #91 API33 | 汇总判定：`probe(no-summary) ; viewer(exit=2) -> exit 2`（未完成） |

## 怎么读这两轮的差别

- **#92 是 exit 1**（断言失败）：探针 23 项全过，设备测试自己红了 —— 属于
  「实现/夹具问题」，需要人看（见 `blockers-liquid-glass-2026-11-24.md` §3）。
- **#91 是 exit 2**（未完成）：探针没跑完就掉线 —— 属于「环境问题」，
  不代表实现有缺陷。

这两条结论**不能互换**：把 #91 的掉线读成"实现坏了"会去改本来没坏的代码；
把 #92 的断言失败读成"模拟器抖动"会让真缺陷漏过去。

## 缺什么

设备测试超时时，`PartialSelectionUi.await()` 会把无障碍树与截图写到**设备侧**
`getExternalFilesDir(null)/cloud-ui-failures/`，当前取证脚本**没有把它拉回来**，
所以这里没有「超时那一刻屏幕长什么样」的证据，也没有该轮的 logcat。
补这两样是下一步排查的前置条件。
