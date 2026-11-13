# 包TB 进度笔记：API37 CloudCleanup 提示超时调查+最小修正（wg-TB，分支 fix/cloudcleanup-toast-diag-2026-11-10）

更新时间：2026-11-11 凌晨（收尾单元，单轮闭环）。基线 0dd52eb（整合候选），交付提交 **9979967**（9979967633603501970ec53217d669871d7a2921），已推送原分支。未派 CI（按指示由协调者整合统一派）。

## 一、任务事实更正：门禁失败 run 的归属

- 协调者登记的失败 run **42844759640** 里 CloudCleanupUiTest 在 **API36/API37 双侧均 OK(8)**（含 invalidatedCopyKeepsConfirmedOriginal，无 ui-failures 捕获）；该 run 的 API36 job 失败在 `run-cloud-ui-probe.sh` 收尾——FullRecovery 阶段后 `adb pull .../cloud-ui-failures` 对**不存在目录** pull 报 `failed to stat remote object` 两次，随后脚本退出 1（probe 收尾对无失败目录的 pull 未容忍，属 probe 脚本问题，另一会话在改 probe，此处仅登记证据）。
- 真正带 `Error in invalidatedCopyKeepsConfirmedOriginal` 的是同工作流前一轮 **run 42840727007**（integration/night-2026-11-08，15:05:24Z，API36 job ✓ / **API37 job ✗**）。其 API37 工件（Tabula-VolumeFix-API37 artifact 13776212947）CloudCleanupUiTest instrumentation：Tests run 8, Failures 1，唯一失败即本用例。

## 二、失败形态（run 42840727007 API37，工件证据）

`UiAutomation.executeAndWaitForEvent` 20s 超时，等待条件 = `TYPE_NOTIFICATION_STATE_CHANGED` 且文本含"副本尚未验证，已保留 1 张原图"（MainActivity.kt:1722 的生产 Toast，onConfirm 复核 urisToDelete 为空且 unverified>0 分支）。超时报告里等待期**唯一**收到的事件：

```
TYPE_WINDOW_STATE_CHANGED; EventTime: 147108; PackageName: com.tabula.v3.recyclebin.test;
ClassName: androidx.compose.ui.window.DialogWrapper
```

时间锚定：内核启动 15:08:58.467（uptime 0）→ EventTime 147108ms = 墙钟 **15:11:25.57**，即对话框开窗（back-callback 注册 15:11:25.380）后 **~190ms**——捕获到的是对话框**入场**事件而非关闭事件。

## 三、根因定性：操作未完成（fixture 层确认点按丢失），非反馈缺失、非观测失效

红/绿 A/B 对照（同一用例、同一 API37、相隔 24 分钟）：

| 证据 | 红 run 42840727007 | 绿 run 42844759640 |
|---|---|---|
| 对话框 back-callback | 25.380 注册后 20s **从未清空** | 20.309 注册 → 20.746 清空 |
| 系统侧 Toast 窗口 | **全程无 VRI-Toast**（最近一次在使用例前 15:11:08、使用例后 15:12:43） | 20.727 `WindowManager: setClientSurface ... VRI-Toast#308 ... Toast` |
| app 主线程活动 | tap 后 20s 零日志（无重组/无复核/无删除/无异常/无 ANR） | tap→深度复核→Toast 入队 400ms 完成 |
| a11y 事件 | 仅对话框入场事件（147108） | Toast 的 TYPE_NOTIFICATION_STATE_CHANGED（被过滤器命中） |

链条：对话框 25.380 开窗 → 等待开始 ~25.5（throw 于 45.5 = 20s 整）→ 确认 tap 于开窗后 ~120-190ms 注入，落在 AlertDialog 入场动画窗口内**被丢弃** → onConfirm 从未执行 → 生产 Toast 从未入队。故排除"反馈缺失"（生产路径根本未被调用）与"观测失效"（同一观测机制在绿 run 同 API 上分钟级之后成功捕获同类型 Toast 事件；且系统侧从未创建 Toast 窗口，无物可观测）。

同族先例：本文件 `startAndOpenDialog` 对"清理原图"按钮已登记 API36 单次点按后对话框偶发未出现（run 40723464281）并加有界重点；本次是**确认按钮**路径的同一家族表现。U18 pm clear 归属竞态不相关（本轮 fixture 插入全部 owner 正常解析，无重试日志）。

## 四、最小修正（仅 androidTest，本用例）

`app/src/androidTest/java/com/tabula/v3/CloudCleanupUiTest.kt` invalidatedCopyKeepsConfirmedOriginal（+28/-1）：

- `executeAndWaitForEvent` 的监听先于命令生效（红 run 超时报告能捕获入场事件为证），据此把**有界自验重点**放进命令内：点按"删除原图"后轮询对话框是否真的关闭（"删除原图"从 rootInActiveWindow 消失，null root 视为过渡态继续轮询），仍开着则重点按（最多 5 次、间隔 1.5s、deadline 10s）；
- 有界重试耗尽对话框仍开着 → 命令内抛 IllegalStateException 如实失败，不把"对话框不吃点按"伪装成"Toast 超时"；
- **断言语义零削弱**：Toast 过滤器文本与 `assertTrue("unverified source must remain", present(source))`（副本失效时原图仍在）逐字保留；外层 20s 超时不变；未动 app/src/main、app/src/test、probe 脚本。
- 同一 risk 面的两个相邻用例（laterCandidateIsNotDeleted:257、unknownDeleteVerification...:528 的 `tap("删除原图")`）本轮未改（任务限定本用例；两用例在红绿两轮均绿），如后续登记同族可复用同一模式。

## 五、验证与交付

- `compileDebugAndroidTestKotlin` 通过（EXIT=0）。
- 提交 9979967 推送至 origin/fix/cloudcleanup-toast-diag-2026-11-10（新远端分支，与整合候选 0dd52eb 的关系由协调者整合时处理）。
- 未派 CI。本地无模拟器实跑（额度受限，按任务只要求 compile 绿；根因证据以双 run 工件为据）。
- 附带登记：42844759640 的 API36 job 失败源于 probe 收尾对不存在 cloud-ui-failures 目录的 adb pull 不容忍（两行 `adb: error: failed to stat remote object` → CalledProcessError），供改 probe 的会话参考。
