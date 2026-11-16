# PR #65 独立审计报告（包C2：他人媒体真实系统授权流程）

审计者：独立只读会话（未参与实现），2026-11-08。基 master 43a8da1，head 956776d。
**总评：可合并。** 全部核对项通过，无阻断问题；4 条建议级记录（见末节），其中合并顺序依赖需在合并时执行。

## 1. 冻结与范围 —— 通过
- `gh pr view 65`：head=956776da…，3 提交（78f601f→4e6569e→956776d）与声明一致，OPEN，mergeable=MERGEABLE/mergeStateStatus=CLEAN（未移动）。
- `git diff 43a8da1..956776d --stat`：恰 3 文件——新增 OtherAppMediaAuthDeviceTest.kt(+683)、CloudCleanupUiTest.kt(+21)、tools/run-cloud-ui-probe.sh(+4/-1)。无 app/src/main、无清单改动。
- 口径小误差：notes 称 probe 脚本"3 行追加"，实际 +4/-1（2 注释行 + for 列表行改写 + 1 case 行）。无实质影响（低）。
- **PR57（B1）冲突面**：实测 PR57 diff——它在同一 for 循环前**同锚点**加 3 行注释、并在循环后追加独立 PARTIAL 块；**不动 for 行与 case 块**。与 PR65 在注释块/for 行区域插入重叠，后合者 rebase 时文本冲突概率高，解决机械（双方注释都保留、for 行取 PR65 版、case 两行并存、PR57 尾部块原样）。语义无冲突（两 PR 套件互不干扰；PR57 的 PARTIAL 预清理按目录+画廊空断言，与 PR65 @After 全清兼容）。谁后合谁解冲突，顺序无硬依赖（记录级）。

## 2. 夹具身份机制 —— 通过（重点核验）
- shell 发起属实：`shell()` 走 `UiAutomation.executeShellCommand` → `shellScript()` base64 解码后由 `/system/bin/sh` 执行 `content insert`，调用方为 shell uid。
- 三类身份矩阵真实成立且**创建后断言校验**（非假设）：foreignOwnedFixture 插入后 `check(row.ownerShell == foreignOwnerPackage)`（L193），否则测试即红；unowned 断言 owner==NULL（L218）；self 走应用进程内 IS_PENDING 发布（update==1 校验）。foreignOwnerPackage=ins.context.packageName（androidTest APK com.tabula.v3.recyclebin.test.test，uid 独立）。
- CI 工件实证（run 42477604680 API36 artifact OtherAppMediaAuthDeviceTest/logcat.txt，本人独立 grep）：`identity appPkg=com.tabula.v3.recyclebin.test appUid=10216 foreignOwnerPackage=…test.test`；`manifest kind=foreign ownerShell=…test.test`（4 行）、`kind=shell-unowned ownerShell=NULL`（2 行）、`kind=self-owned ownerShell=…recyclebin.test`（5 行）。归属绑定双 API 生效。
- 哨兵（集合外）= 自有媒体行（outOfSelectionSentinel），不进任何选择集；4 用例末尾均断言 app+shell 双面存活。@After 按唯一名 shell 删除全部夹具并双面核对无残留，另有 seed 目录删除（带 canonical 防穿越校验）与隔离 manager 目录清理。
- AOSP insertFile isCallingPackageShell 分支引文：未逐行核对上游源码（未核对项），以"创建后 owner 校验 + CI 双 API 通过"为等效实证（低）。

## 3. 四用例断言真实性 —— 通过
- ①读可见+直接删除边界：三身份 present 断言；自有行 `assertEquals(1, delete)`+行消失（对照）；foreign/unowned 经 interceptDirectDelete——**删除成功（>0）会 error() 使测试失败**，SecurityException 才放行，行保留 app+shell 双面。真实非恒真。
- ②未授权按项如实：pendingUris 集合相等、intentSender 非空（真实 createDeleteRequest）、cancelled、deleted 空、remaining 集合相等、error null；**三面保留**（app 查询+shell 查询+shell wc 字节数）；UNKNOWN 不扩大=verifyDeletionState.existing==两行、unknown/deleted 空断言在场（L534-537）；回收站记录按 id 集合保留、journal（pendingDeletionUris）空；哨兵存活。
- ③真实对话框授权删除：tapSystemConsentButton 轮询 providers.media/permissioncontroller 窗口按 Allow（CI 工件实证 `system consent dialog owner=com.google.android.providers.media.module tapped=Allow`）；授权后**三面一致**（app 不在+shell 不在+文件字节不在，L566-568）、记录消费（loadRecycleBin 空）、journal 干净、verify.deleted==行且 unknown 空、哨兵存活。
- ④清理入口组合：deleteInBatches+deleteMediaUrisBatch 生产路径未授权→cancelled+remaining+app+shell 保留+哨兵（缺文件字节面，见建议）。
- 轮询均为截止时间+失败证据留存（截图/窗口 dump），无固定 sleep（仅 2s 重试退避与 100ms 节奏）；onDeleted 未授权时 error() 反向断言。startIntentSenderForResult 形态差异的 API36/37 行为已在 KDoc 固化（轮 1/2 失败→轮 3 收敛，叙事自洽）。
- 小缺口（建议级）：①仅断言 `is SecurityException` 基类——RecoverableSecurityException 由 CI 日志佐证（本人独立 grep：`directDelete foreign exception=android.app.RecoverableSecurityException`）但代码未收紧。

## 4. photo() 有界重试 —— 通过
- 实现：photo() 包 repeat(3)+2s 退避，仍失败 `throw lastError` 如实抛出——重试不可能把真回归洗成绿。与 archive() 既有 publish-0-rows 重试同文件同口径（2s×2，run 40735652083 登记，L126-143 实读）。
- 登记/清理完整性：insertPendingJpeg 在 insert 后**立即** `photos += fixtures.fixture(item)`（L115），先于 openOutputStream——失败轮产生的行也进 @After 清理，无孤儿行。
- U18 根因证据**独立核到工件级**（run 42369922277 API37 artifact logcat.txt，非转述）：56.006 `owner_package_name IN ()`（空 IN=调用方包列表空）→ 56.012 insert-pending media/26 owner=query-null uid=10234 → 56.020 RecoverableSecurityException media/26 → 56.141 media/28 owner=本应用（≈130ms 窗口），失败在夹具创建路径非删除路径。诊断链与修复匹配（本轮 CI 竞态未复现、0 次 retry 日志，属防御性修复——notes 已如实声明）。

## 5. CI 证据 —— 通过
- run 42477604680：build 5m28s + device(36) 8m45s + device(37) 8m19s 三 job 全 ✓；仅 Node20 弃用告警（无关）。
- job 日志：API36 `OtherAppMediaAuthDeviceTest: OK (4 tests)`（12:05:42）、API37 `OK (4 tests)`（12:05:17）；两 device 日志 FAILURES/Error/AssertionError 计数=0；既有 5 套件 OK(8/3/2/2/2) 双 API（CloudCleanupUiTest OK(8) 含 U18 修复在场）。expected_tests=4 与 @Test 实数（4）一致；probe 脚本 bash -n 通过，唯一 CR 行为基线既有 `tr -d '\r'`。

## 6. 安全边界 —— 通过（逐条）
- 无 MANAGE_MEDIA/MANAGE_EXTERNAL_STORAGE 授予：grep 全测试文件仅 `pm grant … READ_MEDIA_IMAGES` 一条（生产可申请的运行时权限）。
- 无 app-op 修改：无 appops/setMode/AppOps 任何调用；app/src/main 零改动（diff 证实）。
- shell 用途清单（全部枚举）：夹具 insert/字节拷贝（cat）/查询/清理 delete+rm seed、文件存在性 test、对话框单次 input tap（模拟用户授权，即用例目的）、权限授予一条。无提权操作。
- 门禁：allowSyntheticMediaProbe 参数 + ranchu/goldfish 硬件双门（L146-147），真机不可误跑。

## 7. 本地复跑 —— 以 CI 为准
- 设备测试本机无模拟器未跑，以 run 42477604680 为准（已核到工件级）。
- 本地 `:app:compileDebugAndroidTestKotlin`（worktree@956776d，ANDROID_HOME 指定）：EXIT=0 通过。生产组合函数（FileOperationManager.deleteImages/verifyDeletionState/NeedsPermission(intentSender,pendingUris)、deleteRecycleBinSelection、deleteMediaUrisBatch、deleteInBatches、pendingDeletionUris）均在基 43a8da1 存在且签名匹配。

## 问题分级
- 阻断：无。
- 建议（不阻塞）：B1 notes"3 行"口径 vs 实际 +4/-1；B2 用例①可收紧断言 RecoverableSecurityException 子类；B3 用例④保留断言缺文件字节面（用例②已有三面覆盖）；B4 AOSP 源码引文未独立核对（CI 实证等效在场）。
- 合并动作项：与 PR57 后合者需解 run-cloud-ui-probe.sh 同锚点小冲突（保留双方注释、for 行含 OtherAppMediaAuthDeviceTest、case 两套并存、PR57 尾部块原样）。
- 未核对项：仅 B4 一项（AOSP 引文）；其余声明均已独立核实，无未验证转述。
