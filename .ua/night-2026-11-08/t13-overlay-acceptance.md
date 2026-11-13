# T13 验收判据：覆盖安装恢复（upgrade-overlay，2026-11-11 协调者拟定）

范围：docs/upgrade-verification-checklist.md 的 **A.1–A.3 可自动化子集**（B.1–B.4 语义抽查的自动化等价断言内嵌；C 回退与 D 边界照旧）。清单的手工步骤维持人工口径，不因本自动化宣称完整覆盖。

## 双 APK 构造
- OLD APK：master `43a8da1` 的 assembleRelease（forkRelease 签名，CI secrets；versionCode 5）
- NEW APK：本分支 head 的 assembleRelease（同签名；versionCode 5——同版本覆盖装 `-r` 合法）
- 双构建沿用 benchmark/build_targets.py 的 baseline/candidate 模式（worktree+overlay），生产源码树 digest 守卫沿用
- 记录两 APK SHA-256 + 源 tree SHA（清单 A.1 要求）

## 阶段与判据
### Phase SEED（旧 APK 安装态）
1. 安装 OLD APK → 启动 → 应用内真实建档：
   - 合成媒体 g1,g2,n1,s1（宿主 shell 绑定所有权+清单）
   - 回收站：g1 入回收站（应用内真实入口）
   - 图集元数据：隐藏 1 张（isHidden=true 持久化路径）
   - 整理进度：AlbumCleanupEngine 对 g1,g2 建组+断点（用 OLD APK 自己的 instrumentation 种子，或 root 写 43a8da1 格式文件——选实现代价小者并登记）
2. 期望状态落盘（expected-state JSON）→ force-stop。

### Phase OVERLAY（覆盖安装）
3. `adb install -r NEW.apk`（断言安装器无签名冲突，rc=0）
4. 冷启动（am start，无 pm clear）→ NEW APK 运行。

### Phase VERIFY（新 APK instrumentation 断言）
5. 回收站条目数与 SEED 一致；g1 记录可读（身份键解析成功）；恢复一张→内容完好（三面）。
6. 图集元数据完整：isHidden=true 幸存（升级前状态保留）。
7. 整理断点续接：同一组/月断点批继续而非重开（跨版本读回）。
8. 媒体真值：g2/n1/s1 行仍在（shell 双面）；无新增缺失。
9. 偏好幂等：重启应用两次，冷却/专清计数不漂移。
10. 哨兵 s1 存活；app 无 crash（logcat 无 FATAL）。

## 登记口径
- 判据 5–10 全绿（双 API）= T13 覆盖安装自动化子集验收；清单 A.4–A.5（更新渠道/鸣谢入口）与 B 全部维持人工口径（blocked 登记）
- 失败→有效失败证据→最小修复（若为新版迁移缺陷）→独立审计
- 阻塞（签名材料/CI 构造限制）→保存已完成实现+解锁步骤

## 实施定稿（2026-11-11 01:20，协调者）
- **种子方案定稿**：OLD APK（43a8da1）+ OLD test APK 安装后，直接运行 OLD 分支**现成的** FullRecoveryPhase1Test——经真实生产代码建立 组(g1,g2)+断点+部分处理(g1)+expected-state.json（零格式猜测；43a8da1 树已含该测试）。元数据面=adb root 注入 albums_metadata.json（单文件简单 JSON，按 43a8da1 AlbumManager.loadMetadata 格式）+ isHidden=true。
- **CI 构造**：仿 build_targets.py 双 worktree——job 内构建 OLD(43a8da1) 与 NEW(分支 head) 各自的 app+androidTest APK（forkRelease）；记录 4 个 SHA-256+源 tree。
- **验证 instrumentation**（新 APK 内）：UpgradeOverlayDeviceTest 断言判据 5–10（expected-state 存在、getCheckpointBatch 续接 g2、g1 保持已处理、回收/恢复语义、元数据 isHidden 幸存、偏好幂等双启、哨兵）。
- **工作流**：build-volume-confirmation 增加 upgrade stage（或独立 workflow upgrade-verification.yml）；门=expected_tests 声明。
- 依赖：无（不依赖 #66/#67）；可与 B2 并行（不同 probe 段）。

## 边界 2 设计（旧快照晚到接线验证，2026-11-14 协调者拟定，待边界 1/3 落码后串行实施）
- **目标**：验证真实 MainActivity 接线——权限变化→扫描取消→界面状态提交——在旧读取晚返回时保持正确（PR50 语义的设备级验证）。
- **现有基础**：Phase3 已注入旧读取（AtomicReference 手动取消）=函数级；本边界补**真实接线级**：权限 shell 变化→应用 mediaAccessRevision 触发→生产扫描重启。
- **设计**：Phase3b（新增段）——FULL 恢复后：①shell 注入新媒体（触发应用扫描在途）②立即 shell revoke→grant（权限变化→应用重扫描）③等待应用稳定 ④断言 UI 最终集合=权限变化后真值（旧扫描晚返回不覆盖）⑤重复两次验证稳定。可观察信号=deck/gallery 节点身份集（复用 Phase3 的成员断言原语）。
- **控制说明**：旧读取"晚返回"由自然扫描时长提供（不做人工注入——真实接线无注入点）；如需确定性，先扩充库容量拉长扫描。若自然时序无法构造晚返回竞态→登记阻塞（附时序实测）。
- **约束**：不动生产注入点；复用 Phase3 断言原语与哨兵纪律。
