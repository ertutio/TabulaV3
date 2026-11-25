# API 37 真机验证现场（2026-11-25）

两轮真机尝试，**均未取得六步终态**。本目录是原始产物，供接手者核对。

| 轮次 | 时间 | HEAD | 结果 |
|---|---|---|---|
| 第 1 轮 | 12:49 | `0efce6a` | `@Before` 报 `already registered!`，进程崩溃 |
| 第 2 轮 | 14:09 | `7dee5de` | 同一错误，栈顶落在新加的 `uiAutomation()` |

设备：OnePlus PLR110（`ro.hardware=qcom`），API 37（Android 17），ABI arm64-v8a。

## 文件

| 文件 | 说明 |
|---|---|
| `api37-run2-instrumentation.txt` | 第 2 轮 instrumentation 完整输出 |
| `api37-run2-uiautomation-lines.txt` | 两轮 logcat 里所有 UiAutomation 相关行 |
| `api37-run2-crash.txt` | 第 2 轮 crash buffer |

## 两轮共同点（这是关键）

### 1. 实例哈希完全相同

```
12:49:11.176  Cannot call disconnect() while connecting UiAutomation@b893e23[id=-1, displayId=0, flags=0]
14:09:04.552  Cannot call disconnect() while connecting UiAutomation@b893e23[id=-1, displayId=0, flags=0]
```

`@b893e23` 在两次**独立进程**（PID 8144 / 23882）、相隔 80 分钟的运行里完全一致。
Java 对象哈希跨进程相同，只能是同一个持久对象，或者日志取自同一处缓存。
这一点尚未查清，**是本轮最重要的未解线索**。

### 2. 第 2 轮 `Registering` 次数为 0

```
grep -c 'Registering UiTestAutomationService' logcat-full.txt  → 0
```

第 1 轮有 1 次（`flags=0x0`），第 2 轮**一次都没有**。

含义：第 2 轮从未成功发起注册，`connect()` 在服务端就被
`mUiAutomationService != null` 拒绝。也就是说**第 1 轮那次注册在系统侧从未被清除**，
跨进程遗留了下来。

### 3. 两次都是 `id=-1`

`id=-1` = `CONNECTION_ID_UNDEFINED`，即从未进入 CONNECTED。
两轮 logcat 里都**没有任何** `Init UiAutomation` / CONNECTED 痕迹。

## 已排除

| 假设 | 排除依据 |
|---|---|
| 并行 instrumentation | 两轮各只有 1 个 instrumentation 进程 |
| flags 切换导致 disconnect 重连 | 全仓库无 `getUiAutomation(flags)` 调用，flags 恒 0 |
| 每次获取都重复注册 | 第 1 轮只注册 1 次；第 2 轮 0 次 |
| APK 包名/目标错配 | `targetPackage` 实测 = `com.tabula.v3.recyclebin.test` |
| 权限未授予 | 失败发生在授权之前 |

## 未证实

- "主线程被 `executeShellCommand` 占住导致 `client.init()` 回不来"：
  机制上成立（`init` 由 `connectServiceUnknownThread` post 到主线程），
  但日志里没有 `init` 相关记录，**不能当作根因**。
- "真机独有"：CI 从未跑过 `allowRealDevice` 路径，无对比基线。

## 建议接手方向

1. **先查 `@b893e23` 为何跨进程相同** —— 这比"为什么报 already registered"更基础。
2. 查系统侧残留：`adb shell dumpsys accessibility` 看 `UiAutomationService` 是否仍挂着；
   若是，重启设备可验证"残留是否跨重启"。
3. 第 2 轮 0 次注册说明问题在**服务端拒绝**，不是客户端重复；
   应查 `UiAutomationManager.mUiAutomationService` 的生命周期（谁注册、谁该销毁）。
4. 清理现场时**不要**只看客户端 `Instrumentation.mUiAutomation`——
   服务端 `mUiAutomationService` 才是 `already registered!` 的直接来源。

## 清理状态（两轮均已执行）

- 合成样图：0 张（`@Before` 即失败，从未创建）
- 两个隔离包：均已卸载，`firstInstallTime` 核对为本轮安装
- 正式包 `com.tabula.v3`：v3.4 / versionCode 6 / `lastUpdateTime` 2026-11-17 未变
- 系统设置：动画缩放 1.0/1.0/1.0 未改动
