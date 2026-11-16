# TabulaV3 独立审计：PR #79（B2 三阶段并入 master + B2PermissionRefreshDeviceTest）
审计：全新会话静态核验 + CI 证据（未跑模拟器）。2026-11-14。head=09df1707（gh pr view 79，OPEN/CLEAN，base master=f7450366）。

## 总评：**可合并**（无阻断问题；2 条轻微备注，见分级）

## 单元 1（PR #79 结构）
1. 分支关系：b1a93cd 父=6c12c51+f7450366（git parents 实证），两者均为其祖先，ace7625 为 6c12c51 祖先 ✓；head=b1a93cd+4 增量（4018edf FPF 段归位→0206d37 PhaseB 正向核验→e95f705 探针 720s→1200s→09df1707）✓。
2. diff f7450366..head 恰=B2 增量、零 D 条目：A=FPF 5 文件（Phase1/2/3/StateContract/Ui）+B2PermissionRefreshDeviceTest+notes 快照；M=PhaseBTest/PartialSelectionUi（并集：仅加参数 verifyPkgs/正向包名核验+错误页 BACK 重试，master 侧 goneMarkers/重试逻辑在位）/两 probe 脚本（FPF 段+预算）。✓
3. master 零丢失：8 类循环行两 ref 逐字节相同（CloudCleanup/CloudRestore/CreateAlbumDiagnostic/ForegroundPermission/PixelRefresh/OtherAppMediaAuth/ProdAuthCallback/CardLifecycle）；ProdAuthCallbackDeviceTest/CardLifecycleDeviceTest 两 ref 均在场；24/26 master androidTest 文件未触碰，2 个 M 为真实并集（hunk 逐行核）。✓

## 单元 2（B2PermissionRefreshDeviceTest 3 例 + probe 新段）
1. 范围/接线行号全部核对命中：onResume:342→checkPermission(foregroundRefresh=true):347；applyAccessLevel:395-407（revision++:405，级别变更或带权限回前台分支）；TabulaApp 组合函数 MainActivity.kt:422 内 LaunchedEffect(mediaAccessRevision,...):583-620 键控重启→loadAndApplyMediaSnapshot；门控按钮"授权访问照片"MainActivity.kt:1906；DeckScreen.kt:720 快照向可见消费面同步。✓
2. 例 1：FULL 前置断言（两权限态+Policy=FULL）→地面真值恰 2 夹具（去重+集合断言+shell 行）→deck 渲染→PID 落盘→注入 late→集合面恰 3→awaitGone(late,5s)（真实现：出现即 error+留证，缺席则满窗）→旧集合保持（确定性缺席依据=app/src/main ContentObserver grep 零命中，实证）。✓
3. 例 2：依赖门 check()（状态文件+phase1Pid>0，缺失即失败非跳过）→NONE 前置→assertNotEquals(pid) 新进程→门控屏→assertNeverVisible(3 夹具+deck 标记,10s) 含 appWindowOk>0 有效性门（观察窗不重现有效性有门）→shell 面 3 行仍在库（权限语义非删除）→用例末自清带断言。✓
4. 例 3：NONE 前置→自有夹具恰 2（shell 全库名单）→门控屏+pid0==pidof→pm grant 即时断言+FULL 级别+pid 不变→Home→am start→60s 截止轮询三条件 AND（logcat currentBatch 含两夹具行 id+门控消失+卡节点可见），超时 error 带全量留证→终验 pidof 恒等→自清。无恒真/无吞失败（全部断言实谓词；失败路径 dumpTree+截图）。✓
5. probe 新段 run-cloud-ui-probe.sh:514 起：gallery 0 行硬门、逐步 OK(1) 精确门+FAILURES/INSTRUMENTATION_FAILED/Process crashed/shortMsg/负状态码 grep 门、revoke 前后 pidof 留证（pid_before 空为 instrumentation 退出时序，如实登记不强门）、step1/2 红则 dependency-skip+按名兜底清理、段末权限回收。✓

## 预期 case 数与约束、CI
- 3 例：类 KDoc 明示"预期 case 数=3"；probe 每步 OK(1) 精确门；CI 两侧日志各 3 个 B2 步 OK(1)（API36 22:16:48/22:17:20/22:17:49）一致。✓
- 约束：app/src/main、app/src/test 零改动（diff 0 文件）✓；LF——全部新增/修改 blob CR 字节=0，唯一例外 run-cloud-ui-probe.sh 1 CR 为 master 既有（两 ref 同值），非本分支引入 ✓。
- CI run 43514530630：success，headSha=09df1707 绑定 ✓；build/device(36,36)/device(37,37.0) 三 job 全绿 ✓；双 API 证据链对称：B2 段头+revoke kill evidence+OK(1)×12+无 "B2 …failed"/FAILURES 标记（"failed" 命中均为 ||true 兜底的 failures 目录取与 adb 启动重试，非断言吞没）✓。

## 问题分级
- 阻断：无。
- 轻微：①任务书父链"ace7625→e95f705→b1a93cd"中段序与实际相反（e95f705 在 b1a93cd 之后：实际链 b1a93cd→4018edf→0206d37→e95f705→head），实质断言（b1a93cd=6c12c51∪f7450366、head=b1a93cd+增量）不受影响；②probe.sh 1 个 CR 字节为 master 既有遗留，建议后续轮次顺手清理。
