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

## 六、收尾单元（2026-11-09）：生产授权回调入口测试（wg-N，分支 test/prod-auth-callback-2026-11-09）

**覆盖缺口**：PR#65 审计登记的生产授权回调消费路径——OtherAppMediaAuthDeviceTest 用测试包身份经 `activity.startIntentSenderForResult(intentSender, 40501)` 驱动对话框，结果码投递至 MainActivity **无消费者**（套件 KDoc 自认）；系统对话框确认/取消结果经生产回调消费 + in-flight 释放此前零覆盖。

**状态面（实读生产代码；grep 证实 createDeleteRequest/startIntentSenderForResult/IntentSenderRequest 仅存在于 MainActivity 与 FileOperationManager，SystemAlbumSyncManager/AlbumCleanupEngine 无调用点）**：
- 发起：MainActivity.kt L320-324 `onRequestDeletePermission` → `deletePermissionSession.launch(callback) { deletePermissionLauncher.launch(IntentSenderRequest…) }`；launcher 以 `ActivityResultContracts.StartIntentSenderForResult()` 注册（L288-293）。
- 等待/恢复标志：`DeletePermissionSession.inFlight`（L11，launch 置位 L22 / complete 释放 L36，孤儿结果走 recoveryRevision++ L37）；删除流以 `suspendCancellableCoroutine` 挂起（MainActivity L638-643 回收站 / L776-781 批删 / L820-825 原图清理），重入门 `isDeletingRecycleBin || deletePermissionInFlight`（L627）。
- 回调消费：launcher 回调 → `deletePermissionSession.complete(resultCode == RESULT_OK)`（L290-293）→ resume(allowed) → deleteRecycleBinBatch：拒绝 → `finishDeletion(emptySet, covered)` + Cancelled（RecycleBinDeletion L119-122）；授权 → verify → `finishDeletion(deleted)`（记录消费、journal 关闭）。
- in-flight 的 UI 可观测面：`isDeletingRecycleBin || deletePermissionInFlight` 时回收站界面「正在处理回收站」进度对话框（MainActivity L1128-1141）。

**交付**：新增 `app/src/androidTest/java/com/tabula/v3/ProdAuthCallbackDeviceTest.kt`（2 用例，全程 UI 驱动**应用真实入口**：Deck「回收站」→ 条目 → 全屏查看器「彻底删除」→ 应用确认对话框「删除」→ 生产 deleteRecycleBinImages → 生产 launcher → 系统对话框；UiAutomation 点按沿用 PR65/CloudCleanupUiTest 已证路径 + API36 单击被吞的有界重点按先例）：
1. `systemConsentConfirmConsumedByProductionLauncherAdvancesDeletion`——Allow → in-flight 释放（对话框 3 连续轮询消失=complete(true) 消费）→ 行/文件三面删除 + 记录消费 + journal 回基线 + verify.deleted==行且 unknown 空 + UI 条目消失；哨兵存活。
2. `systemConsentCancelConsumedByProductionLauncherRetainsSource`——Cancel/Back 兜底 → in-flight 释放（complete(false)）→ 源保留三面 + 记录保留 + journal 回基线 + verify=existing（UNKNOWN 不扩大）+ UI 条目仍在；Toast「已取消…剩余 1」为瞬时窗口，观察到则校验内容、未观察到以记录/条目保留等持久断言兜底（KDoc 如实登记）；哨兵存活。

**夹具纪律（沿用 PR65）**：shell 绑定所有权夹具（owner=androidTest APK）+ 集合外哨兵（自有 IS_PENDING 发布行，含归属竞态 3 次有界重试）；断言全部截止时间轮询 + 失败留证（截图+全窗口节点 dump）；@After 集合级 shell 清理双面核对，并还原真实回收站单例（RecycleBinManager.getInstance——本套件经真实 UI 驱动使用共享单例，@After 仅移除本套件登记记录 + 释放本套件残留 journal 条目并双面核对）；真机门禁（allowSyntheticMediaProbe + ranchu/goldfish）保留。

**本地验证**：`:app:compileDebugAndroidTestKotlin` 通过（ANDROID_HOME 指定；两轮修复：effect lambda Boolean 化、@After attempt 改 suspend 本地函数以容纳挂起调用）。本机无模拟器（ANDROID_HOME 无 emulator/system-images），设备执行依赖 CI probe——`tools/run-cloud-ui-probe.sh` 已登记 `ProdAuthCallbackDeviceTest`（expected_tests=2，+注释 2 行/for 列表 1 token/case 1 行）。**合并顺序登记**：与 PR57/PR65 同文件同锚点（后合者解冲突，机械：三方注释并存、for 行取并集、case 行并存）。

**分支/提交**：test/prod-auth-callback-2026-11-09，head `366d2e1`（基 956776d=PR#65 head，1 提交，revert 单提交即可回退）；已推 origin。改动面：androidTest 新文件 + probe 脚本，app/src/main、app/src/test 零改动。CI 未派（按约定协调者统一派发；probe 300s/class 预算内：常规通过路径 ~90-120s，失败级联有界）。

**CI 迭代与阻塞（2026-11-09，累计 3 轮红 → 收拢阻塞，不做下一轮修正）**：
- 轮 1（run [42646353367](https://github.com/ertutio/TabulaV3/actions/runs/42646353367)，head 366d2e1，双 API 双用例同因）：`IllegalStateException: UI flow did not reach expected effect for: deck trash button` at tapDescUntilEffect(:408)。根因 `uiAutomation.windows` 恒空（同 run 各套件 window 行全零实证）→ head 2951ed2 全查找改 rootInActiveWindow。
- 轮 2（run [42653961146](https://github.com/ertutio/TabulaV3/actions/runs/42653961146)，head 2951ed2）：入口链全通（deck→回收站屏→条目→查看器→应用确认框→in-flight→系统对话框），红点移至取消分支：`System consent dialog could not be cancelled` at cancelSystemConsent(:543)。dump 实证系统对话框拒绝键实际文本=**Deny**（`text=Deny clickable=true`），CANCEL_TEXTS 缺项 → 8s 找不到键 → BACK 兜底延迟且遗留对话框污染次例（确认用例 bringAppToForeground 45s 超时）。
- 轮 3（run [42662894360](https://github.com/ertutio/TabulaV3/actions/runs/42662894360)，head 5c035ec=Deny/DENY 补齐+取消判定闭环链+第二段重入验证）：**build job 绿**（compile+单测+release identity 过）；双 device job 各现**新因**——
  - api36（取消用例红，确认用例**转绿**=轮 2 主因已消）：Deny 点按生效（logcat `system consent dialog tapped=Deny` @17:29:33.742 → `system consent dialog window closed` @17:29:34.272）→ 红于第二段 `IllegalStateException: App did not regain foreground with recycle bin ready (count=2 项待处理, item=foreign-cda107b4fac2.jpg)` at waitAppRecycleBinReady(:622)。dump 实证（logcat :108062 起）：active root 已回应用，但 **row1 查看器仍开启**，树内仅 desc=关闭 / text=1 / 2 / desc=恢复 / desc=彻底删除 / 查看器页 desc=条目名——全屏查看器覆盖层使回收站计数/列表节点**不可达**（「覆盖层不改变节点可达性」假设在 API36 不成立）→ 20s 轮询必超时。
  - api37（双用例 2 失败）：Deny 在对话框成为 active window 后 ~0.25s 即点按（in-flight @17:29:25.550 → `tapped=Deny` @17:29:25.795），**进入动画期单次点按被吞且轮 3 重构后无重试** → 对话框滞留：`System consent dialog window did not close after cancel` at waitSystemDialogClosed(:598)；dump 实证（logcat :95541 起 @17:29:45.859）对话框原样在树（text=Deny/Allow clickable=true）→ 次例污染同轮 2（bringAppToForeground(:372) 45s 超时）。
- **阻塞登记（head c02d518，已推 origin）**：probe 类列表摘除回 fdc03967 口径（六类，ProdAuthCallbackDeviceTest 摘除、测试文件与轮 3 代码保留——api36 部分验证生效）；轮 3 证据+解锁路径登记进 probe 注释与套件 KDoc；compileDebugAndroidTestKotlin 过、bash -n 过、改动面仍仅 androidTest+probe（LF）。
- **唯一解锁动作（待验证假设，一次定向修正+一次 CI）**：①取消判定第一段（窗口关闭）后先**关闭查看器**（点 desc=关闭，RecycleBinScreen L798-806 IconButton onClick=onDismiss；效果=彻底删除 desc 消失），再等「N 项待处理」计数与条目重现（绕开覆盖层遮蔽）；②Deny/BACK 点按改为**有界重试直至对话框关闭**（tapDescUntilEffect 同款防吞先例），BACK 兜底保留；随后重入验证段按同一链路收尾。验证通过后重新登记 probe（expected_tests=2）。

## 七、收尾微单元（2026-11-11）：OtherAppMediaAuthDeviceTest API37 有界重点修正（wg-N，预算=一次修正+一次 CI，绿收敛）

**失败链**：`foreignOwnedMediaDeletionCompletesThroughRealSystemConsentDialog` 在 API37 三次同签名失败（run 42653961146 时期一次、42840727007、42857428672）：logcat `tapped=Allow` 已记录但断言 `expected:<[…]> but was:<[]>`（summary.deleted 空，42857428672 红于 OtherAppMediaAuthDeviceTest.kt:562）——授权点按落在系统对话框入场动画窗口内被吞，onConfirm 未执行、MediaProvider applyBatch 未发生。与 CloudCleanupUiTest 已证机制同签名（9979967 同款修法已让 API37 该用例转绿，42856903671 组合门禁 API37 全套件绿）。

**修法（head `47f38c9`，仅 OtherAppMediaAuthDeviceTest.kt，+46/-4）**：tapSystemConsentButton 点按处套用 9979967 同款——新增 `tapAllowWithBoundedRetap`：点按后轮询系统对话框消失（授权结果已由生产 launcher startIntentSenderForResult 消费，行消失仍由 grantThroughRealSystemDialog 轮询为同步点，无需事件监听）；未消失则有界重点（≤5 次/1.5s 间隔/总 20s 封顶，每次重点前从 rootInActiveWindow 重找按钮）；耗尽仍开着 captureUiEvidence 后如实抛 IllegalStateException（不伪装成其它失败）。断言面零改动（确认分支 deleted/existing/unknown 断言逐字保留）；probe（已登记）、app/src/main、app/src/test 零改动。

**本地验证**：`:app:compileDebugAndroidTestKotlin` 通过（ANDROID_HOME=C:/Users/yuxuan/android-sdk）。

**CI（run [42863345514](https://github.com/ertutio/TabulaV3/actions/runs/42863345514)，head 47f38c9，workflow_dispatch 一次）三 job 全绿**：build 1m28s；API36 OK(4 tests)、API37 OK(4 tests)。API37 工件实证（OtherAppMediaAuthDeviceTest/logcat.txt）：`system consent dialog owner=com.google.android.providers.media.module tapped=Allow taps=0` @17:26:02.293 → `system consent dialog closed after taps=1` @17:26:02.748（455ms 后对话框关闭=点按被处理、applyBatch 完成；本轮首击即中，有界重点未触发，防御网在场）。API36 同用例同轮绿（OK(4 tests)），双 API 套件 4/4。

**状态**：预算内绿收敛（1 修正提交 47f38c9 + 1 次 CI 全绿）；累计 API37 该用例失败次数定格为 3（不再 +1）；唯一残余登记项不变（API37 环境敏感性以本轮防御网+同签名先例覆盖，无需额外解锁动作）。

## 八、收尾微单元（2026-11-11 00:30 登记，协调者代笔）

**第 2 轮（in-flight 双证据化）绿**：提交 `90c5d14`（inFlightDialogVisible 改双证据：「正在处理回收站」可见 OR 系统对话框成为 active window——后者在 windows 列表恒空环境仍可从 active-root 观测，run 43260104714 dump 实证），CI run **43260104714 三 job 全绿**：ProdAuthCallbackDeviceTest 双 API OK(2)×3——确认例（空态文本等待生效）与取消例（双段含重入）全部通过。轮 1（caacab5）的 API36 双例修正在本轮保持绿（非侥幸）。

**#66 全链收口**：生产授权回调消费路径（#65 缺口）实现+验证完成。修复链：513d3df（单元三四项修正）→ 47f38c9（OtherAppMediaAuth Allow 有界重点）→ caacab5（观测层两处：有界轮询+空态文本）→ 90c5d14（in-flight 双证据）。执行路径：分支 ff 至组合态 ec65f0c 后叠加（验证空洞修正，登记于 diag-pr66-43251910422.md §0）。累计：ProdAuthCallback 相关红轮 5 次（全部诊断归档）；第 2 轮起全绿。

**剩余登记**：①该套件对环境时序敏感（入场动画窗/窗口列表空），后续如复发按 KDoc 与 diag 文件复核；②release 对照装（unsigned）与 fork 签名 secrets 解锁为独立待验边界（见 T13 登记）。
