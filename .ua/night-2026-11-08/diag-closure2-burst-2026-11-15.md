# 诊断：收口第二轮三连红（2026-11-15 15:00，协调者核查，用户指令"核查为什么三轮还不成功"）

## 结论（一句话）
**目标段全部健康**：StaleScan 段实测 OK(1) 绿（轮 C 工件）、HashRecompute 的 InvalidTestClassError 已定位为我的代码缺陷并已修复（runBlocking\<Unit\>）。三连红 = **上游既有间歇段的下午爆发**（FPF Phase3 ×2 双 API + CardLifecycle ×1），非任何本轮工作引入（master 基线 43713220665 @ 5fbb70cca9 当晨三 job 全绿、FFP/CL 段 2/2 绿；执行者 head 69a9611 未触碰 probe/FPF/CardLifecycle——git show 仅 StaleScanLateReturnDeviceTest.kt +98 / MediaLoadTestHook.kt +9）。

## 三轮逐因（分支 test/stale-scan-2026-11-15，head 69a9611）

| 轮 | run | 失败 job/段 | 签名 |
| --- | --- | --- | --- |
| A | 43714036923 | API36 FPF Phase3 | `IllegalStateException: timed out waiting for 全局`（FPFPhase3Test onSwitchToGlobal 全局批次等待=a11y 语义暴露丢失家族，notes-B2.md:211 既有登记 R1-API36 同族）；"FPF Phase3 expected OK(1)" 门红 |
| B | 43717360920 | API36 CardLifecycleDeviceTest | "Failure or skip observed in CardLifecycleDeviceTest"（不同段、不同形态） |
| C | 43720733892 | API37 FPF Phase3 | 同 A 签名（换 API37）；**StaleScanLateReturn 段工件实测 OK(1) 148.13s（API37）**——执行者定向修复（a11y 窗口失联恢复 69a9611）已生效 |

## TC58 轮 4（43714628520）失败（我的泳道，独立成因）
- HashRecomputeDeviceTest `initializationError` / InvalidTestClassError：`@Test fun x() = runBlocking { ... }` 块尾 `Log.i`（Int）→ 推断返回类型非 void → JUnit4 拒类。build 绿（单测 872 不含 androidTest）、本地仅编译未跑设备故漏网。
- **已修复**：`runBlocking<Unit>` + 块尾显式 Unit；轮 5 待派（commit 待提交）。

## flake 频率观察
- master 晨跑（05:20）：FPF Phase3+CardLifecycle 双 API 2/2 绿。
- 下午窗（12:36-14:14）：3 次执行至少 3 个上游段红（Phase3 API36、Phase3 API37、CardLifecycle API36）——下午窗口爆发形态（与 2026-11-10→11-14 各轮"API36 模拟器基建抖动/CloudCleanupUiTest 间歇"既有登记一致的环境不稳定窗）。
- 留证注意：轮 C 的 ui-failures 采集 rc=1（COLLECTION FAILED 非门控）——Phase3 超时的截屏/树 dump 现场可能未拉回，诊断价值受限；FPF 工件内的 stack 与 phase3.txt 尾部在案。

## 对合并判据的影响
- PR #81 合并判据=三 job 全绿：目标段已绿但上游 flake 爆发使整跑绿概率下降；按既有口径这属"基础设施失败且目标断言已执行"——如实登记不掩盖，等待环境窗口恢复后重派。
- PR #80 轮 5（修复后）同理：HashRecompute 段+全部上游段需同窗全绿。

## 建议（待用户裁决项）
1. **不动门禁语义**：继续重派直至全绿（现状做法；下午窗口不知何时恢复）。
2. **probe 级有界重试**（门禁政策变更，需用户授权）：对 FPF Phase3/CardLifecycle 等既有登记间歇段增加一次段级重试（首轮红→记录→重跑一次→以二轮为准，首轮留档）——将"间歇环境抖动"与"真实回归"在门禁层分离；代价=真实回归也可能被重试掩盖（需首轮留档+失败计数审计补偿）。
3. **a11y 语义丢失根因攻坚**（FPF Phase3 全局批次等待）：框架层 Compose 语义在退出动画窗口的暴露丢失（既有诊断）；工程量大、收益不确定，建议列为独立单元另议。

## 当前执行态
- stale-scan 轮 D（43724140820）运行中（执行者自查续派）。
- TC58 轮 5（HashRecompute 修复）待提交派发。
