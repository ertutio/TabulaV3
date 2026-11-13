# 独立裁决：B1（PR #57，PARTIAL 选择集 A→B）— 2026-11-08 22:40

**裁决结论：选项 A（修订版）——先一次诊断 run 分辨 (b)/(c)，(b) 则修 confirm 落点；(c) 则实施 6.6 Settings 回退，但设硬检查点 04:15 未绿即收拢为"PhaseA+保留读回"交付（即 A 内嵌 C 兜底）。**

## 逐问结论

1. **PhaseA 可按当前证据验收。** R10 双 API 全绿已核对（API36 job log 14:02:32 `OK (1 test)`；API37 同）；结论由地面真值承载（授权前空集→恰={A1,A2}→哨兵/B 集合外，为应用真实 MediaStore 查询读回），**像素传感器不承载 PhaseA 结论**——若驱动错格/确认未提交，地面真值必红。像素怀疑如实声明：白色勾判据为启发式（31x31/阈值15），R10 双 API 双向通过+不收敛必红灯，不构成静默假绿通道，但稳定性证据仅 1 轮（且双 API viewer 均靠 attempt-1 重试通过，有轻微不稳定信号）；诊断轮 PhaseA 重绿即作补强。
2. **选项 A 成立，但 B1 的信号①不可用，需修订主判据。** 关键新发现：**工件缺失根因=文件名含 `|`（identityKey 如 `external_primary|108`）被 upload-artifact v4 整体拒绝（R9-API37 与 R10 双 API 的 job log 同一错误行：`...viewer-overlay-external_primary|108-tree.txt`），非平台瞬时故障**——一行 sanitize 即可恢复证据链。修订信号表见下节。
3. **(c) 先验显著高于 (b)。** 已核对官方文档：Android 14 partial access 页记载的重选唯一路径=**重新请求 READ_MEDIA_IMAGES/VIDEO（对话框 Figure 2）**；photo picker 页明确结果为 URI 会话授权（takePersistableUriPermission 章节）；Android 15 行为变更页无"picker 更新 user-selected 集"记载。Settings 回退可行且值得：复用 collectGridCells/driveCheckedCells/confirmSelection（复用度约 70%），新增 Settings 导航约 100 行，且有文档背书（appinfo→Photos and videos 是记载的管理路径）；对话框重请求路径不可行（生产无 hook，违反无生产改动纪律）。
4. **6.7 最小断言集有两处缺口，必须补：** (i) **跨进程持久化读回**目前只在 PhaseB pre-switch 段（R10 已实证通过——失败堆栈 kt:154 表明 kt:97-150 全部执行：读回 JSON、pid 断言、assertRetention("pre-switch")、onboarding 标记、可见集=A）；PhaseA 自身仅进程内引擎重建验证。收拢时该段必须升为独立保留用例或保留 PhaseB 前置段。(ii) 哨兵 shell 行存在断言（PhaseB kt:209）价值独立（集合外媒体行不受影响的系统侧证据），建议纳入。
5. **时间收敛成立。** 22:40 → 05:30 ≈ 7h ≈ 12+ 轮余量：诊断轮（实现 30-60min + CI 20-30min）→ (b)/(c) 各 1-2 修正轮 → 收拢轮；含一次修正余量，硬检查点 04:15。注：notes 6.8 写"冻结 06:30"，按简报 05:30 从严执行。

## 诊断 run 信号采集清单（给 B1 的执行指令）

**前置（无条件必做，否则证据链再次全灭）：**
- F1 `dumpTree`/`saveScreenshot` 文件名 sanitize：`|` 及 upload-artifact v4 非法字符 → `_`（PartialSelectionUi.kt 两个方法，各一行）。
- F2 confirmSelection 增加确认后回读：轮询 ≤8s `rootInActiveWindow.packageName` 离开 media.module（picker 关闭）；未关 → 坐标重试确认 ≤2 次（每次留证）；每步 logcat 行 `confirmCommitted=true/false`。该修复无论 (b)/(c) 都需要（消除 tap 无回读弱点）。

**主判据（S3，写入 job log 可见的 logcat 行）：**
- ground-truth-B 失败路径输出裁决行：picker 已关 && 集合仍 A ⇒ `verdict=(c) PICK_IMAGES committed but user-selected set unchanged`；picker 未关（重试后仍开）⇒ `verdict=(b) Done tap not committed`。

**佐证（S4/S5/S6，best-effort 不阻塞）：**
- S4 失败后以 `QUERY_ARG_LATEST_SELECTION_ONLY`（`getExtensionVersion(UPSIDE_DOWN_CAKE)>=12` 守卫，API36/37 应可用）查询：返回 B 而 normal 查询=A ⇒ (c) 强佐证；返回 A/空 ⇒ 中性。
- S5 修复后的 `deadline-ground-truth-B` 截屏：picker 开/关视觉直读，独立第二读数（本次不再被 `|` 吞掉）。
- S6 probe 脚本宿主已有 `adb logcat -d`（probe.sh L177）；追加 `grep -iE "user.?select|PhotoPicker|GrantUri|accessible"` 摘要进 job log。

**信号①裁定（B1 原候选）：不可用。** 现机制 `ctx.startActivity`(NEW_TASK) 无 result 接收者，resultData 无人接收也无法由测试进程读取；若改测试 APK 为调用方，则 caller 语义变更（授权对象变为测试包），结论不可迁移到应用。以 S2 的 picker-close 回读替代之。信号③（立即/延迟双查）已被 awaitDeadline 的 20s/150ms 轮询等效覆盖（R10 已排除"20s 内慢更新"），仅在失败路径补一次 picker 关闭后再查询即可。

**判定分支：**
- (b) → 修 confirm 落点/重试（S2 已是半成品），重跑，≤2 轮到绿，A→B 完成。
- (c) → 实施.Settings 回退：`am start -a android.settings.APPLICATION_DETAILS_SETTINGS -d package:${ctx.packageName}`（CI 包名=com.tabula.v3.recyclebin.test，动态取 ctx.packageName）→ "Photos and videos"（候选：Photos and videos/照片和视频）→ "Select photos"/"Select more photos" → 既有选择器原语驱动 {0,1} → 确认 → 地面真值恰={B1,B2} + 既有 PhaseB 后续断言全保留。到 04:15 未绿 → 收拢（下一节）。

## 收拢交付（触发即执行）

- PhaseA 全链断言原样保留（已双 API 绿）；PhaseB 拆分：**pre-switch 保留读回段升为独立用例**（跨进程持久化验收，R10 已实证通过）；A→B 重选段摘除并登记（卡点定性=诊断 run 裁决行 + 6.6 设计）。补哨兵 shell 行存在断言。PR 描述如实登记边界与 (b)/(c) 定性证据。

## 诚实纪律

- 已核对：R10 双 API job log（OK(1)/AssertionError kt:154/artifact error 行）、R9-API37 job log（同 artifact error + readExpectedState 级联失败）、R9-API36 artifact 下载直查（pixelCell 五格采样=背景色实证，与 notes 一致）、probe.sh、PhaseA/PhaseB/Ui/StateContract 四文件全文、官方文档三页。
- 未核对：R10 各 API 的 logcat.txt 与截屏（工件未上传不可得——"reselect confirmed via ..."用哪条确认路径因此不可知）；R9-API36 的截屏像素内容（仅核对了 logcat 行）；notes 6.7/6.8 描述之外的历史轮工件。
- 像素传感器怀疑成立的范围：不破坏 PhaseA 有效性（地面真值承载），但 confirmSelection 落点无回读是真实弱点（(b) 未排除的根源），且传感器稳定性仅 1 轮证据——诊断轮 F2 修复后此弱点即消除。
