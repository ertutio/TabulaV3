# notes-RA：任务 A——非空回收记录跨版本升级与恢复（upgrade-recycle，2026-11-13 凌晨）

分支：`test/upgrade-recycle-2026-11-12`（基 test/upgrade-overlay-2026-11-11 @ 545570e7，
即 T13 全绿框架基点：upgrade-verification.yml / run-upgrade-probe.sh / UpgradeOverlayDeviceTest /
T13 演练与 waitsem 修复全部在位）。
新文件（零触碰其它文件）：`tools/run-upgrade-probe-recycle.sh`、
`.github/workflows/upgrade-recycle-verification.yml`、
`app/src/androidTest/java/com/tabula/v3/UpgradeRecycleSeedTest.kt`、
`app/src/androidTest/java/com/tabula/v3/UpgradeRecycleDeviceTest.kt`。
任务 B（冷却/进度）= 并行单元 test/upgrade-cooldown-2026-11-12，独立 workflow/probe/test 文件，零共享文件。

## 0. T13 缺口与本单元边界

T13 演练（run 43264360831）留三缺口：回收记录空、有效冷却空、处理进度空。
本单元只补**回收记录**（任务 A）；冷却/进度归任务 B。实现上回收记录与冷却/进度
在 T13 框架里分属不同存储（recycle_bin.json vs tabula_pick_timestamps.xml /
tabula_album_cleanup.xml），物理上无交集，两单元可并行。

## 1. OLD 写入口选择（登记）

**选择：直接复用 43a8da1 真实生产写入口 `RecycleBinManager.addAllToRecycleBin`（新增
OLD 侧种子 test `UpgradeRecycleSeedTest`，workflow 构建期拷入 OLD worktree 的 androidTest
源再构建 OLD test APK）。** 依据：

- 43a8da1 的生产调用链 MainActivity.onRemove → `recycleBinManager.addToRecycleBin(image)`
  单条变体即委托 `addAllToRecycleBin(listOf(image))`（RecycleBinManager.kt:87）——
  addAllToRecycleBin 就是该版本唯一的生产入站事务入口（去重 + deletedAt 盖戳 +
  AtomicFile fd.sync/finishWrite + 读回校验一次完成）。
- OLD 自带 CloudRestoreUiTest.prepareTrash 的真实种子方式正是
  `trash.addAllToRecycleBin(removed)`（其 UI 手势只用于恢复路径）——仓库既有口径。
- 对比项 CloudCleanupUiTest 的纯 UI 种子路径（Deck 滑卡回收）依赖推荐引擎批量与
  手势时序，脆弱且夹具面更大；实现代价显著高，不选。登记为设计取舍。

**夹具清单（UpgradeRecycleSeedTest.seedRecycleRecordsViaProductionEntry）**：
- 合成媒体 4 张（复用 OLD/NEW 逐字节一致的 FullRecoveryStateContract.seedPhoto：
  真实 MediaStore JPEG，`fullrec-<uuid>-<n>.jpg`，Pictures/FullRecoveryFixtures/，
  颜色 `eraseColor((n*37) or -0x1000000)` 互异、displayName UUID 互异）。
- 成员 m1/m2/m3（3 张）经 rebuildRow 得生产 ImageFile 行 → `addAllToRecycleBin([m1,m2,m3])`。
- 集合外哨兵 s1（第 4 张）：**不入站**，只登记身份；全程断言其记录永不出现、媒体行永不消失。
- 身份明确：每成员登记 `identityKey`（`mediaIdentityKey()` = `volumeName|id`，
  如 `external_primary|17`）+ 全字段（id/uri/displayName/dateModified/size/width/height/
  bucketDisplayName/orientation/deletedAt/volumeName/relativePath）。
- 期望状态写 `getExternalFilesDir/upgrade-recycle-expected-state.json`
  （文件名与 T13 的 full-recovery-expected-state.json 区分；结构含 members[3]（全字段）、
  restoredCandidate(=m1)、outsideSentinel(s1)、seedPid）。写入前先 app 面自证（判据 2 的
  app 查询面）：loadRecycleBin 回读恰 3 条、身份键有序一致、deletedAt>0、rebuildRow
  媒体行仍在、4 张 JPEG 全部可解码、s1 不在站。
- 生产写耐久性边界：recycle_bin.json 的落盘由生产 AtomicRecycleBinStorage 自证
  （fd.sync + finishWrite + 读回不等即抛 IOException("回收站文件提交失败")）——种子测试
  不做额外 flush。

**OLD 生产代码零修改的核对面（workflow build job）**：worktree add 43a8da1 后先取
`app/src/main` 全文件 sha256 聚合 digest，拷入种子 test（唯一允许的 untracked 文件
`?? app/src/androidTest/java/com/tabula/v3/UpgradeRecycleSeedTest.kt`）后再取 digest，
前后相等为门；另以 `git status --porcelain` 过滤后非空即 fail。OLD APK 来源
（43a8da1^{tree} + 该 untracked androidTest 夹具）记录进 upgrade-recycle-build-record 工件。

**双端可编译**：种子 test 只引用 OLD/NEW 逐字节一致的类（RecycleBinManager/ImageFile/
FullRecoveryStateContract/LocalImageRepository/FileOperationManager 均 diff 实测 identical；
mediaIdentityKey 在两侧同文件同实现），该文件同时编入 NEW test APK（NEW probe 不驱动它）。

## 2. 判据 → 断言/门映射

判据 2（覆盖安装前，shell 读回 OLD 持久状态，三面）：
- app 查询面 = 种子测试内断言（loadRecycleBin 恰 3 + rebuildRow 行在 + 可解码）。
- 文件面 = probe `adb root cat files/recycle_bin.json` 留证 r-pre-upgrade/，grep 恰 3 个
  `"displayName"`、每成员名恰 1 次、volumeName 在。
- shell 查询面 = probe content query（root 全列表，unroot 回退，双份留证）每名恰 1 行
  （4 名：3 成员 + s1）。

判据 3（覆盖安装后、任何修复/重算前磁盘读回）：
- 时序：force-stop OLD → `adb install -r` NEW → **app 未启动前** adb root 直读
  recycle_bin.json 存证 r-pre-upgrade/recycle-bin-prelaunch.json，并以 root cat 重定向
  同内容注入私有 `files/upgrade-recycle-disk-truth.json`（chown/chmod 600/restorecon，
  T13 验证过的私有目录注入法）→ 装 NEW androidTest → instrumentation。
- 用例 1 verifyDiskTruthThenRestoreViaUi 在**任何 UI 启动前**断言三面一致：
  (a) 注入的 pre-launch 磁盘真值 JSON 解析记录 == (b) 进程内直读
  File(filesDir,"recycle_bin.json") 解析记录 == (c) 生产 loadRecycleBin() 记录，
  逐字段（13 字段）逐序相等；成员身份键有序 [m1,m2,m3]；deletedAt 全>0。
  启动链侧证：TabulaApplication.onCreate 无回收站触点；MainActivity 启动链的
  reconcileRecycleBinDeletion 在 pending journal 为空时立即 no-op 返回（种子从不
  beginDeletion → journal 空）→ 无修复/重算写入，磁盘读回即种子原文。

判据 4（真实界面恢复一项）：
- 真实 UI 入口（43a8da1 与 head 的 RecycleBinScreen/MainActivity 恢复路径逐字节一致）：
  startActivitySync(MainActivity) → waitFor("图库") → gesture("回收站")（TopBar
  contentDescription）→ waitFor("3 项待处理") → gesture(m1.displayName)（回收站列表项
  AsyncImage contentDescription=displayName）→ gesture("恢复")（查看器恢复按钮）——
  走生产 restoreImagesInOneTransaction（verifyDeletionState 核验 →
  removeAllFromRecycleBin → allImages/imagesForSorting 回插 → recycleBinLoadRevision++）。
- 恢复项回图库（生产投影口径）：loadAllData().allImages 含 m1 身份键 且
  loadRecycleBin 不含（= projectSnapshot 的"快照−软删除"投影含 m1，即回到图库流）；
  物理面 rebuildRow(m1) 行在 + JPEG 可解码。
- 未恢复项仍在回收站：生产 loadRecycleBin 恰 [m2,m3]（有序、无重复、无缺失、m1/s1
  不在）；UI 面 keyevent 4 退出查看器 → waitFor("2 项待处理") + m2/m3 displayName
  节点在活动窗口。
- 无串项/重复/无关丢失：身份键集合精确匹配（非 contains）；s1 记录从未出现；
  4 张媒体行 shell 面（probe 复查 content query 每名恰 1 行）+ 文件面（pull 后
  displayName 恰 2 个、m1 名不在、volumeName 在）。
- 判据 4 的 UI 断言映射登记：`恢复项回图库` 的自动化等价 = 生产投影口径
  （loadAllData ∖ recycleBin 含 m1）+ 物理行/字节完好；Deck 卡片无 displayName 文本
  节点，图库 UI 逐名可见性不做脆弱断言（登记为映射，非放弃）。

判据 5（force-stop → 新进程读回）：
- probe：用例 1 后 pull recycle_bin.json 存证 r-verify/，root cat 重定向注入
  `files/upgrade-recycle-postrestore-disk-truth.json`（fix_owner）→ force-stop →
  am start（生产冷启动装载恢复态、进程存活门）→ force-stop → 用例 2 instrumentation
  （新进程）。
- 用例 2 verifyRestorePersistsInNewProcess：postrestore 快照 pid ≠ 本进程（换代锚）；
  新进程 loadRecycleBin == 恢复后期望 [m2,m3]（逐字段）；**幂等**：第二次
  loadRecycleBin 与第一次逐字段相等；注入的 postrestore 磁盘真值 == 生产读 == 进程内
  直读文件三面一致；m1 行在 + 可解码、s1 行在。

断言纪律：awaitUntil 截止轮询（250ms 步进/30s 截止，false 恒不通过）+ withEvidence
失败留证（upgrade-recycle-evidence.json 落盘后重抛）+ 测试创建文件名无 `|`；
UI waitFor 20s 截止（沿用仓库 CloudRestoreUiTest 口径）。probe 无 `|` 管道断言、
逐门 grep、双份原始输出留证。

## 3. probe 门（expected_tests 逐门 grep）

- 种子：`OK (1 test)`（r-seed/seed.txt）
- 用例 1：`OK (1 test)`（r-verify/disktruth-restore.txt）
- 用例 2：`OK (1 test)`（r-persist/persist.txt）
- 附加门：overlay install `Success`/无签名冲突/无 INSTALL_FAILED；pre-upgrade bin 文件
  恰 3 名；shell 媒体真值（升级前后两轮）每名 1 行；post-restore bin 文件恰 2 名且
  恢复名缺席；两次进程存活；logcat 无 FATAL。

## 4. workflow（upgrade-recycle-verification.yml）

复制 upgrade-verification.yml 改：name、push 分支 = test/upgrade-recycle-2026-11-12、
script = tools/run-upgrade-probe-recycle.sh、工件名 upgrade-recycle-*、
OLD worktree 步骤加种子 test 拷贝 + 生产 digest 前后相等门 + untracked 白名单门。
矩阵/SDK/KVM/API37 cmdline-tools 预检与 T13 全同（T13 R2 全绿的 proven 配置）。

## 5. 运行证据

- 实现前本地核：wg-RA `:app:compileDebugAndroidTestKotlin` BUILD SUCCESSFUL（ac1d13a 内容）；
  43a8da1 scratch worktree + 种子 fixture 拷入后 `:app:compileDebugAndroidTestKotlin`
  BUILD SUCCESSFUL（双端可编译实证）；bash -n / YAML parse OK；scratch worktree 已移除。
- **R1（run 43271456222，commit ac1d13a，push 触发）= 首轮全绿（build + device API36 +
  device API37，conclusion success）**。逐门证据（工件 upgrade-recycle-API36/API37，
  本地留证副本 ci-r1/ 已核后删除）：
  - 三门 instrumentation `OK (1 test)` ×双 API（r-seed/seed.txt、
    r-verify/disktruth-restore.txt（12.9s，含 UI 恢复全程）、r-persist/persist.txt）
  - 种子面（API36 例）：members=`external_primary|17,18,19` + 哨兵 `|20`；pre-upgrade
    recycle_bin.json 恰 3 记录（id 17/18/19，deletedAt 全>0，volumeName=external_primary）
  - 判据 2 三面：文件面（pre-upgrade 拉回恰 3 记录）+ shell 面（root 全列表 content
    query，4 名各恰 1 行）+ app 面（种子测试内 loadRecycleBin/rebuildRow/解码全过）
  - 判据 3：disktruth-restore 12.9s 通过 = pre-launch 磁盘真值（宿主 NEW 启动前 root
    直读注入）== 进程内直读 recycle_bin.json == 生产 loadRecycleBin 三面逐字段一致
  - 判据 4：真实 UI 恢复后 recycle_bin.json 恰 2 记录（API36: [18,19]，恢复项 17 离站；
    API37: [19,20]，恢复项 18 离站——API37 的 MediaStore id 基址不同，内容语义一致）；
    postrestore 快照 remainingKeys/restoredKey 与期望状态互证；恢复后 shell 媒体真值
    4 名各恰 1 行（物理媒体零损毁）；UI 面 "2 项待处理" 由用例内 waitFor 断言
  - 判据 5：两次冷重启 Status: ok + 进程存活；persist 0.15s 通过 = 新进程读回恰未恢复
    两项 + 两次 loadRecycleBin 幂等 + postrestore 磁盘真值三面一致
  - 附加门：install 全 Success（overlay install -r，lastUpdateTime 17:52:43→17:53:02 同
    versionName 替换）；window issues=[] ×3 阶段 ×双 API；logcat FATAL EXCEPTION=0
  - 源身份（upgrade-recycle-build-record/source-identity.txt）：newHead=ac1d13a、
    oldHead=43a8da1（oldTree 41b11eeb）、oldProductionTreeDigest=0420b11e…、
    fixture 仅 untracked androidTest 种子测试；OLD release unsigned APK sha256
    c5308472… 与 T13 R2 记录一致（43a8da1 确定性构建，生产零改动旁证）
  - 单元门：393 tests / 0 failed（:app:testReleaseUnitTest，59 类）

**迭代计数：1 轮（R1 即全绿）；无重复卡点，无阻塞登记。**

## 6. 阻塞/登记

- 无阻塞。依赖登记：本单元分支基于 T13 的 test/upgrade-overlay-2026-11-11（PR #73）
  ——PR base=master，合并顺序须 #73 先合（#73 合入后本 PR diff 自动收敛为 4 个新文件）。
- 种子 fixture 编译面已本地实证双端可编译（OLD 43a8da1 scratch worktree + NEW head）。
- 冷却/进度缺口归任务 B（test/upgrade-cooldown-2026-11-12 并行单元）；本单元未触碰
  tabula_pick_timestamps.xml / tabula_album_cleanup.xml 相关状态。
