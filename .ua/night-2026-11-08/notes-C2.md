# 包C2 进度笔记：他人媒体的真实系统授权流程（wg-N，分支 test/other-app-media-auth-2026-11-08）

更新时间：2026-11-08 晚（会话进行中）。基线 origin/master=43a8da1037ca50f34c9fabd8a41c2ac505c93824。

## 一、U18 间歇失败诊断结论（已闭环，先于实现完成）

**失败实例**：run 42369922277（API37）`CloudCleanupUiTest.unknownDeleteVerificationKeepsSourceRecordAndReportsReason` 报 `RecoverableSecurityException: com.tabula.v3.recyclebin.test has no access to content://media/external/images/media/26`（迭代 1 同套件 OK(8) 通过——间歇）。

**根因链（工件证据，api37/CloudCleanupUiTest/logcat.txt）**：
1. 失败点在 `@Before setup → photo() → openOutputStream`（写 JPEG 字节），栈为 `ContentResolver.openOutputStream` —— 不是删除路径，是**夹具创建路径**。
2. 时间线：12:00:56.012 `insert-pending media/26 owner=query-null`（紧随插入的 OWNER 查询返回 null cursor）→ 56.020 provider 侧 `DatabaseUtils` 记录同一异常（openFile→checkAccess→enforceCallingPermissionInternal，即写被拒）→ 56.141 `insert-pending media/28 owner=com.tabula.v3.recyclebin.test`（下一次插入归属正常）。26→28 的 130ms 空档正是归属竞态窗口。
3. 机制：pm clear 后本进程对 MediaStore 的**首个插入**，provider 侧调用方身份（包列表）解析未就绪——logcat 56.006 有直接证据 `SQLiteQueryBuilder: Allowing abusive custom column: CASE WHEN owner_package_name IN () ...`（空 IN 列表=调用方包列表为空）。该行被创建但 owner 归属为空 → 创建者（本应用）对自己的 pending 行不再匹配 `owner_package_name IN (<调用方包>)` 写面过滤 → openOutputStream 抛 RecoverableSecurityException。失败的是按字母序第一个执行的测试（current=1），其 @Before 恰是 pm clear 后首次夹具插入。
4. **定性：夹具创建层环境竞态，非生产缺陷，也非"他人媒体"问题**（media/26 本应是应用自有行）。类比既有先例：archive() 的 API37 "publish 影响 0 行"重试（run 40735652083 登记）。
5. **修复**：CloudCleanupUiTest.photo() 改为有界重试（3 次尝试、间隔 2s，与 archive() 重试同口径；每轮插入的行全部登记进 photos 供 @After shell 清理；仍失败如实抛出）。未改 app/src/main。

## 二、任务前提的事实更正（影响方案，已核实）

- 任务登记"当前所有设备测试夹具经 shell 创建"不成立：CloudCleanupUiTest.photo/VolumeDeletionDeviceTest.fixture 均为**应用进程内** `contentResolver.insert`（shell 仅参与 pm grant/清理删除/UI 点按）。当前覆盖口径=自有媒体（应用拥有者）。
- "测试包（instrumentation 运行于 recyclebin.test uid）以自己的身份创建"不可行：`am instrument` 将测试代码装载进**目标应用进程/uid**（debug 应用=com.tabula.v3.recyclebin.test；run 42369922277 CloudMediaIdentity 日志 uid=10234 实证）。进程内 insert 无法产生跨 uid 归属。跨身份夹具只能由 shell 侧创建。
- 落地方案（真实"另一个应用"）：shell `content insert` 显式绑定 `owner_package_name=<androidTest APK 包名 com.tabula.v3.recyclebin.test.test>`（CI 设备上真实安装、uid 独立）。AOSP MediaProvider 源码（main 分支，insertFile）明文：`isCallingPackageSelf() || isCallingPackageShell()` 分支允许 shell 任意设置归属。已验证的其余规则：无主行（owner NULL）写面同样拒绝（`NULL IN (...)` 不匹配）；READ_MEDIA_IMAGES 覆盖他人/无主行读面；直接删除可读不可写行→RecoverableSecurityException；createDeleteRequest→PermissionActivity 对话框（无 MANAGE_MEDIA 必现，正向按钮=R.string.allow"Allow"）→ Allow 后 provider applyBatch 完成删除。

## 三、交付实现（wg-N）

新增 `app/src/androidTest/java/com/tabula/v3/OtherAppMediaAuthDeviceTest.kt`（4 用例，全部走真实系统 + 生产组合，有清单 + 集合外哨兵）：
1. `foreignOwnedMediaIsVisibleButDirectDeleteIsDeniedBySystem`——三身份对照矩阵：自有行直接删除成功（现有口径）/ 他人行与无主行直接删除被 SecurityException 拒绝、源保留；哨兵存活。
2. `otherPartyMediaWithoutConsentRetainsSourceWithPerItemResults`——回收站生产组合（deleteRecycleBinSelection+真实 FileOperationManager.deleteImages/verifyDeletionState），未授权 → NeedsPermission(真实 createDeleteRequest intentSender) 捕获、Cancelled、remaining 如实、记录保留、journal 关闭；UNKNOWN 语义不扩大（verify=existing 而非 unknown）；app/shell/文件三面源保留。
3. `foreignOwnedMediaDeletionCompletesThroughRealSystemConsentDialog`——真实系统授权对话框全链：am start 前台 → startIntentSender → 轮询 providers.media 对话框 → 点 Allow → 行消失为同步点 → 删除三面一致、记录消费、journal 干净、哨兵存活。
4. `cleanupEntryCompositionWithoutConsentRetainsForeignSource`——清理入口组合（deleteInBatches+deleteMediaUrisBatch）未授权同样 Cancelled+保留。

配套：
- CloudCleanupUiTest.photo() 有界重试（第一节）。
- tools/run-cloud-ui-probe.sh 最小追加（class 列表+expected_tests=4+注释行；LF 校验通过，bash -n 通过）。**与 agent-B1 同文件冲突面已最小化（仅 2 行插入+1 case），如 B1 先合入需顺序合并，笔记在此登记。**

本地验证：`compileDebugAndroidTestKotlin` 通过（internal 生产组合函数经 friend-path 引用；ImageFile 路径修正 data.model）。

## 四、CI 迭代与证据

- PR：https://github.com/ertutio/TabulaV3/pull/65（基线 43a8da1；提交 78f601f → 4e6569e）。

**轮 1（run [42473731064](https://github.com/ertutio/TabulaV3/actions/runs/42473731064)，head 78f601f）**：build 绿；API37 job 全绿——**新套件 4/4 通过**（含真实对话框授权删除用例）+ 既有 5 套件零失败；API36 仅新套件用例 3 失败（summary.deleted 空）。
- 关键证据（API36 OtherAppMediaAuthDeviceTest/logcat.txt）：
  - shell owner 绑定在 API36/37 均被 provider 接受：`manifest kind=foreign ... ownerShell=com.tabula.v3.recyclebin.test.test`（真实他人归属生效）；无主行 `ownerShell=NULL`；他人/无主行直接删除均 `RecoverableSecurityException` 且源保留（用例 1/2/4 双 API 通过=授权边界+按项保留+UNKNOWN 不扩大全链实证）。
  - 用例 3 失败根因：`PermissionActivity` 已启动（`START u0 {act=create_delete_request ...} (BAL_ALLOW_ALLOWLISTED_COMPONENT) result code=0`）但随即 `W MediaProvider: android.content.pm.PackageManager$NameNotFoundException: Missing calling package at PermissionActivity.resolveCallingAppInfo(PermissionActivity.java:621) → onCreate:197` → AOSP 的 catch→finish → 对话框不出现 → 点按轮询 20s 超时（"Capturing UI evidence for: consent dialog"，active root 一直是应用窗口）。
  - 根因定性：API36 的 Mainline MediaProvider 模块以 `Activity.getCallingPackage()` 解析发起方——经 `Context.startIntentSender`（非 Activity 上下文）发起时为 null；API37 模块版本不触发该路径（同代码通过）。生产流程从 MainActivity（startIntentSenderForResult）发起，故既有套件对话框在双 API 正常（本轮 CloudCleanupUiTest API36 OK(8) 实证）。
- 附带核实：本轮 photo() 竞态未复现（0 次 retry 日志）——U18 修复为针对已证机制的防御性修复，机制证据以 run 42369922277 工件为准。

**轮 2（run [42475900934](https://github.com/ertutio/TabulaV3/actions/runs/42475900934)，head 4e6569e）**：API37 全绿；API36 同用例仍失败但**启动形态已改变**——对话框进入应用任务（task 22，topActivity=PermissionActivity，与生产一致），仍 `Missing calling package`。据此推翻"Activity 上下文"假设：真正的判别变量是**结果记录**（生产日志 `(rr=…)` vs 我的 `(sr=…)`）——`Activity.getCallingPackage()` 仅在带结果预期的启动（startActivityForResult/startIntentSenderForResult）被系统记录（AOSP Activity.getCallingPackage 语义：仅在 startActivityForResult 系启动时非空）。

**轮 3（run [42477604680](https://github.com/ertutio/TabulaV3/actions/runs/42477604680)，head 956776d）——三 job 全绿，收敛**：build 实跑非缓存；API36/API37 双设备全部 6 套件零失败零 skip——CloudCleanupUiTest OK(8)（U18 修复轮防御性重试在场，本轮竞态未复现）、CloudRestoreUiTest OK(3)、CreateAlbumDiagnosticDeviceTest OK(2)、ForegroundPermissionUiTest OK(2)、PixelRefreshUiTest OK(2)、**OtherAppMediaAuthDeviceTest OK(4) 双 API**。工件证据（双 API logcat）：`system consent dialog owner=com.google.android.providers.media.module tapped=Allow`（真实对话框出现并授权）、`manifest kind=foreign ... ownerShell=com.tabula.v3.recyclebin.test.test`（他人归属绑定生效）、他人/无主行直接删除 `RecoverableSecurityException` 且源保留、未授权组合 Cancelled+remaining+记录保留+journal 关闭、UNKNOWN 不扩大（verify=existing）、授权后三面一致删除+记录消费、集合外哨兵全程存活。

**最终 head `956776da1c29b34f6dc17b11438cd3fc2c83e4c7`，3 提交（78f601f→4e6569e→956776d），revert 逐提交即可完全回退。**

## 五、剩余（交付完成态）

- CI 已收敛绿（run 42477604680，head 956776d，三 job 全绿）；PR #65 OPEN/MERGEABLE，等人工评审，按仓库约定不自动合并。
- 登记候选（不阻塞，供后续）：①API36/API37 模拟器 MediaProvider 为 Mainline 模块，其 `getCallingPackage` 行为差异已在套件 KDoc 固化——未来模块升级不改变断言面（断言的是行/文件/记录状态，非对话框内部）；②photo() 重试本轮竞态未复现（0 次 retry 日志），防御性修复以 run 42369922277 工件为机制证据；③tools/run-cloud-ui-probe.sh 与 agent-B1（PR57）同文件，本轮改动仅 3 行（注释+列表+case），合并顺序无冲突实质。

## 六、收尾微单元：PR#66 ProdAuthCallbackDeviceTest 两轮 CI 红 → 阻塞登记（2026-11-09，后续会话）

单元：PR#66（test/prod-auth-callback-2026-11-09，堆叠 #65）ProdAuthCallbackDeviceTest 2 用例（生产回收站入口→彻底删除→应用确认对话框→生产 launcher 发起/消费系统对话框结果，确认/取消两分支）CI 失败的一轮诊断+一次定向修正；纪律=额度受限，超预算即转阻塞登记，不做第三轮。初始 head 366d2e1（CI run 42646353367 双 API 红）。

**轮 1（run [42646353367](https://github.com/ertutio/TabulaV3/actions/runs/42646353367)，head 366d2e1）诊断**：build 绿；双 API 双用例同因 `IllegalStateException: UI flow did not reach expected effect for: deck trash button`（tapDescUntilEffect :408；:705/:696=用例入口第一步）。工件实证（api36/ProdAuthCallbackDeviceTest/logcat.txt :107984 留证）：失败瞬间 deck 节点树**含** desc=回收站（自身不可点击、父可点击、bounds 非空），但全程**零次** "tap deck trash button (attempt=N)" 日志——所有查找走 `ins.uiAutomation.windows`，而本环境（API36/37 CI 模拟器）该列表**恒为空**（同 run 全部套件留证 dump 的 window 行全零；PR65/CloudCleanupUiTest 的逻辑查找全走 rootInActiveWindow，windows 仅用于留证枚举，故从未暴露）。

**定向修正（head 2951ed2）**：ProdAuthCallbackDeviceTest 全部节点查找（findNodeInAnyWindow / findNodeInAppWindows / systemDialogRoot / 应用确认对话框窗口定位）统一改走 `rootInActiveWindow`——应用屏、应用对话框（Compose AlertDialog 独立窗口）、系统授权对话框（providers.media 窗口成为 active window）皆可达（绿套件已证路径，CloudCleanupUiTest :220/:238、PR65 :395 同款）；留证 dump 的 windows 枚举保留（本环境下为无害空转）。compileDebugAndroidTestKotlin 过。

**轮 2（run [42653961146](https://github.com/ertutio/TabulaV3/actions/runs/42653961146)，head 2951ed2）**：build 绿；双 API 设备红——**修正生效**，入口链路全通（deck 回收站 tap →「1 项待处理」回收站屏 → 条目 → 查看器彻底删除 → 应用确认对话框 → in-flight 进入 → 系统对话框出现），红点移至取消分支：
1. 取消用例：`IllegalStateException: System consent dialog could not be cancelled`（cancelSystemConsent :543，栈 :727）。工件实证（api36 logcat :112403 dump）：系统对话框按钮实际文本 **Deny / Allow**（`text=Deny clickable=true`），而套件 CANCEL_TEXTS={Cancel, 取消, Don't allow, 不允许} 缺 "Deny" → 8s 找不到取消键 → BACK 兜底不可靠（对话框在 8s 判定窗过期后 ~0.1s 才关闭，logcat :112402 后 Transition CLOSE @16:36:00.989）。
2. 确认用例：被次例遗留污染——`RuntimeException: Could not launch intent … within 45000 milliseconds`（bringAppToForeground :338，栈 :660；次例遗留系统对话框/悬挂删除流致启动 45s 不空闲）。

**按纪律转阻塞登记（head fdc0396，测试文件保留）**：
- tools/run-cloud-ui-probe.sh：ProdAuthCallbackDeviceTest 从探针类列表与 expected_tests case 摘除，注释改登记说明（LF 保持、bash -n 过；改动仅注释块+列表+case 三处）。
- 套件 KDoc 新增「阻塞登记」节：两个 run ID + 复现栈 + 工件路径 + 解锁路径（**登记为待验证假设，未经 CI 验证**：CANCEL_TEXTS 补 "Deny"（轮 2 dump 直接证据）± 有界重试点按（tapDescUntilEffect 同款防吞）；随后以探针独立重跑本类验证，绿后重新登记进类列表）。
- app/src/main、app/src/test 零改动；OtherAppMediaAuthDeviceTest 未动（非本单元）。

**OtherAppMediaAuthDeviceTest 红（run 42646353367 与 42653961146 的 API37 foreignOwnedMediaDeletionCompletesThroughRealSystemConsentDialog 同签名失败；API36 双轮 4/4 绿）独立定性**：
- 结论：**独立环境抖动（API37 系统对话框单次点按被吞/未生效），非本套件/本 PR 交互污染**。依据：①探针类顺序 OtherAppMediaAuth 先于 ProdAuthCallback（probe 脚本 for 行），本套件不可能上游污染，且模拟器为每 job 全新；②run 1 中 API36 OtherAppMediaAuth 4/4 绿与本套件双红并存（解耦实证）；③PR65 自身 run 42477604680 双 API 全绿；④两轮失败签名一致：`tapped=Allow` 记录后 30s 行仍在（`expected:<[content://media/external_primary/images/media/117]> but was:<[]>`，两轮 id 恰同=117），留证截图中对话框仍开着——PR65 的 tapSystemConsentButton 单次点按无重试，正是套件 KDoc 已登记的「单次点按偶发被过渡层吞掉」先例类（CloudCleanupUiTest run 40723464281），本轮在 API37 槽位两连发。
- **重跑建议**：单独重派 probe 复验该类即可（API36 双轮绿、API37 镜像内既有绿先例）；若再红，建议对 tapSystemConsentButton 加有界重试点按（属 PR65 范围，本单元按纪律未动）。

**最终态**：分支 head `fdc0396`（366d2e1 → 2951ed2 定向修正 → fdc0396 阻塞登记）；PR#66 保持 OPEN 等人工处置。ProdAuthCallbackDeviceTest 2 用例代码在库、探针摘除（CI 不再执行），解锁路径与全部证据已固化进套件 KDoc。完全回退=revert 3 提交。
