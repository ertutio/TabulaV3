# 合并后独立审计：PR #75（任务 A——非空回收记录跨覆盖安装升级 + 真实 UI 恢复）

审计角色：独立审计员（未参与实现；实现 worktree=wg-RA，分支 test/upgrade-recycle-2026-11-12，head eac4b98c，MERGED 2026-11-13T06:17:57Z，merge commit b172bf80，base=master）。
审计方法：gh PR/compare/run API + wg-RA 源码通读 + run 43375199019 双 API 工件下载抽查。未跑模拟器/构建。

## 结论：**PASS（证据链闭环）**——合并净交付恰 4 个新文件，零生产代码触碰；判据 1–5 工件实证完整；合并 head 有直接绑定的全绿 run。两条 P3 级登记（无需修复，见文末）。

## 逐项

1. **范围（审计方法勘误先行）**：`gh pr diff 75` 的文件清单会显示 app/src/main / app/src/test / gradle 等大量"改动"——这是**旧集成分支的显示噪音**，非本 PR 交付：分支基点含 T06/T10/T11b/N1/N2/A4 等前序单元提交，均已先经其他 PR（含 #73）入 master。以合并提交第一父做净 diff（fc6a6f65...b172bf80）为权威口径：**恰 4 个新增文件**——
   `.github/workflows/upgrade-recycle-verification.yml`、`tools/run-upgrade-probe-recycle.sh`、`app/src/androidTest/.../UpgradeRecycleSeedTest.kt`（+170）、`app/src/androidTest/.../UpgradeRecycleDeviceTest.kt`（+390）。
   **app/src/main 与 app/src/test 零 diff**（compare files=4，无任何生产/单测文件；gradle/libs.versions.toml 亦不在净交付）✓。notes-RA.md 第 0/6 节已预登记该收敛行为（"#73 合入后本 PR diff 自动收敛为 4 个新文件"），与实测一致 ✓。

2. **OLD 种子经真实生产写入口**：UpgradeRecycleSeedTest 经 `RecycleBinManager.addAllToRecycleBin(memberRows)` 入站（wg-RA RecycleBinManager.kt:89）；生产委托链 grep 独立证实 MainActivity.kt:878 `recycleBinManager.addToRecycleBin(image)` → RecycleBinManager.kt:87 `addToRecycleBin = addAllToRecycleBin(listOf(image))`——与 MainActivity.onRemove 同一事务路径，非写死文件格式猜测；recycle_bin.json 落盘由生产 AtomicRecycleBinStorage 自证，测试不手工伪造 ✓。OLD 生产源零触碰由 workflow build job 双门核验：43a8da1 worktree 的 `app/src/main` ls-tree digest 前后相等 + porcelain 白名单（唯一 untracked=种子 test）；run 43375199019 工件 source-identity.txt 实证 oldHead=43a8da1、oldTree=41b11eeb、oldProductionTreeDigest=0420b11e…、OLD release APK sha256=c5308472…（与 notes 记载逐字符一致）✓。

3. **身份键**：全部断言用 `mediaIdentityKey()` = `volumeName|id`（LocalImageRepository.kt:702），工件实证 members=`external_primary|17,18,19` + 哨兵 `|20`（API36）/`|18,19,20`+`|21`（API37）；displayName/uri/volumeName 逐字段复核为辅，非 displayName 单面 ✓。合成媒体经生产 MediaStore insert（FullRecoveryStateContract.seedPhoto：UUID 名互异无 `|`、`(n*37) or 0xFF000000` 颜色互异、JPEG 可解码）✓。

4. **升级流程（宿主探针 tools/run-upgrade-probe-recycle.sh）**：装 OLD+test → 种子 instrumentation → force-stop → adb root 直读 recycle_bin.json（pre-launch 磁盘真值，root 重定向注入 app 私有 files + chown/600/restorecon 读回校验）→ `adb install -r` NEW（rc=Success 门 + 签名冲突/INSTALL_FAILED 反门，无 pm clear）→ 用例 1：**任何 UI 启动前**三面一致（pre-launch 注入真值 == 进程内直读 recycle_bin.json == 生产 loadRecycleBin，逐字段逐序）→ **真实 UI 恢复**（startActivitySync → "图库" → "回收站" → 列表项（contentDescription=displayName）→ "恢复"，走生产 restoreImagesInOneTransaction，非 repository 内部直调）→ 恰余 2 项（身份键集合精确匹配、无重复、恢复项离站、哨兵永不入站）→ 恢复项回图库（生产投影 loadAllData ∖ 回收站）→ postrestore 磁盘真值注入 → **两轮冷重启**（force-stop rc 门 + 杀净断言 + am start -W + pidof 存活门；run 43375199019 r-restart-1/2.txt Status: ok 双 API）→ 用例 2 新进程（pid 换代锚）：恢复持久恰 [m2,m3] + 两次 loadRecycleBin 幂等 + postrestore 三面一致 + 物理 media 行/字节完好（成员+哨兵）✓。

5. **断言纪律**：测试内无固定 sleep——awaitUntil 截止轮询（250ms/30s，false 恒不通过）+ UI waitFor 20s 截止轮询；宿主 sleep（2/3/5/8s）均为基建 settle/杀净验证后置检查，非"睡等断言" ✓。失败留证：withEvidence JSON（bin 状态+pid）落盘后重抛 + 宿主 trap（logcat/properties/package dumpsys）+ 各阶段 window 稳定性窗（boot_id/系统进程/DeadSystem 门，window-*.json issues=[] ×3 阶段 ×双 API）✓；文件名无 `|` ✓；哨兵 s1 存活：shell 面 content query 4 名各恰 1 行（升级前后两轮）+ 测试内 rebuildRow+解码 ✓。**偏差（P3）**：留证无截图/树 dump 形态（logcat/dumpsys/JSON 替代）；notes 自述口径与实现一致，未夸大。

6. **CI 复核**：workflow=3 job（build + device(API36) + device(API37)），全绿 4 连发：43271456222@ac1d13a2（R1 首轮，2026-11-12T17:37）、43367169104@57aeefb7、43371406081@35c85ac2、**43375199019@eac4b98c（合并 head 直接绑定，2026-11-13T05:55，先于 merge 06:17）**——conclusion 均 success、分支名一致、3 job 全 success ✓。注意：brief 所引 run 43271456222 的 headSha=ac1d13a2 而非 eac4b98（notes 如实记为"R1 首轮"）；**合并 head 的绑定证据是 run 43375199019**，本次审计已以其工件为准复核 ✓。

7. **判据 1–5 工件实证（run 43375199019，双 API 均核）**：三门 instrumentation `OK (1 test)` ×3 ×2API（r-seed/seed.txt、r-verify/disktruth-restore.txt、r-persist/persist.txt）✓；判据 2 文件面 r-pre-upgrade/recycle-bin-prelaunch.json（恰 3 记录、volumeName=external_primary、哨兵缺席、每名恰 1）+ shell 面 media-truth-shell.txt（4 名各 1 行）+ app 面（种子内自证）✓；判据 3 三面一致由 disktruth-restore OK(1) 背书 ✓；判据 4 r-verify/recycle-bin-postrestore.json 恰 2 记录且恢复项缺席（API36 [18,19] 恢复 17 离站；API37 [19,20] 恢复 18 离站）+ upgrade-recycle-postrestore.json remainingKeys 互证 ✓；判据 5 persist.txt OK(1) + r-restart-*.txt ×2 轮 ✓；FATAL EXCEPTION=0（双 API r-logcat-final.txt）✓；overlay install Success 留证 r-installs.txt ✓。

8. **单元门 393/0**：R1 build-record 工件 XML 汇总实测 **393 tests / 59 suites / 0 failures / 0 skipped**，与 notes 记载逐项一致 ✓；合并 head eac4b98 的 build-record 实测 430/65/0（后续并入分支新增 37 测试，含 FROM-CACHE 复用）——notes 数字为 R1 口径且明确归属 R1，无夸大 ✓。

9. **notes-RA.md 交叉核对**：新文件清单=净交付 4 文件 ✓；R1 run id/结论/3 job ✓；身份键/恢复后余项（双 API 语义）✓；source-identity/OLD sha256 c5308472… ✓；判据→断言→工件路径映射全部可溯 ✓；写入口委托链行号（RecycleBinManager.kt:87）实测吻合 ✓；"迭代计数 1 轮（R1 即全绿）"与 run 列表一致（其后 3 个 run 为合并前置推送，均绿）✓。未发现夸大宣称。

10. **probe/workflow 改动性质**：两文件均为**新增**，无既有脚本改动（run-volume-device-probe.py 未入净交付，window() 仅复用）；57aeefb7 对新探针的 +9 行为强化（force-stop rc 门 + 杀净断言），无行为语义回退 ✓。

## 登记（P3，无需修复；若采纳请后续 PR/notes 补记）
- **P3-1 留证形态**：失败留证无截图/UI 树 dump（现状=withEvidence JSON + logcat/properties/dumpsys + window 窗）。功能等效且文件名卫生达标，属口径登记差异。
- **P3-2 证据绑定表述**：对外口径建议以 run 43375199019（head=eac4b98）作为合并 head 的绑定 CI 证据；run 43271456222 为 R1/ac1d13a2 首轮证据。另 393/0 为 R1 口径，合并 head 实为 430/0（更强），notes 未更新但不构成失实。
