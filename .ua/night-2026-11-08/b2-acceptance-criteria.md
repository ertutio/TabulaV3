# B2 验收判据：T07 FULL→PARTIAL→FULL（2026-11-09，协调者拟定，执行者可在设计笔记中细化但不得降低）

复用：#57 的系统设置重选机制（3492728 的 PartialSelectionUi/StateContract）、#52 的跨进程恢复原语（FullRecovery*Test/StateContract/Ui、shell 编排）、PR65/66 的 shell 绑定所有权夹具与哨兵纪律。基分支：origin/test/partial-selection-ab-2026-11-08（3492728）。

## 媒体集合与持久进度设计
- 成员（全部宿主 shell 绑定所有权 + 清单 + 集合外哨兵 S）：
  - G 组成员：g1、g2（将被分析成组并部分处理）
  - 非组成员：n1（FULL 下可见、PARTIAL(B) 下不在 B 集合）
  - B 集合专有：b1、b2（PARTIAL 阶段才可读）
  - 哨兵：s1（任何阶段都不进任何集合/选择集）
- 成员身份断言一律用 displayName + mediaIdentityKey（月名/数量相同不视为集合证明）。
- 跨进程边界：每阶段结束 force-stop 应用（进程死亡），下一阶段新 instrumentation + am start——沿用 #52 多进程模式，阶段间应用数据（files/shared_prefs）保留。

## 阶段与判据（每条都必须有对应断言）
### Phase FULL
1. pm grant READ_MEDIA_IMAGES（+VISUAL_USER_SELECTED 清理）→ 启动 → 地面真值：可见恰={g1,g2,n1,b1,b2}（s1 也在但仅作为哨兵存在性核对，不计入集合断言）。
2. 真实入口建立 G 组清理（g1,g2 成组）→ 保存分组记录 + 断点 + **处理 g1**（部分处理：记录"已处理 g1"）→ 断点=组内指向 g2 待处理。
3. 期望状态落盘（FullRecoveryStateContract 形态）：visible 全集、组键、断点索引、已处理集合。

### Phase PARTIAL（FULL→PARTIAL 切换）
4. 宿主 revoke READ_MEDIA_IMAGES（进程死亡）→ 重启 → 权限对话框 → "Select photos" → 选择器**只选 b1、b2**（像素驱动，#57 原语）→ PARTIAL(B) 建立。
5. 地面真值：应用可见恰={b1,b2}；g1/g2/n1 **不可读但可恢复**（shell 查询三面核对行仍在）。
6. **进度完整保留**：跨进程读回组键/断点/已处理记录与 Phase FULL 落盘一致；清理视图对 B 集合的行为符合引擎语义（B 未成组→无批次可断言时，按 #57 收尾同款以"组记录保留+非永久完成"断言，不以月名/数量替代）。
7. 哨兵 s1 全程行存在 + 集合外不可见。

### Phase FULL（恢复）
8. 宿主 grant READ_MEDIA_IMAGES → 重启（grant 不杀进程则 am start 重启对齐 #52 口径）→ 地面真值：可见恰={g1,g2,n1,b1,b2}（无重复、无缺失）。
9. **已处理保持**：g1 仍为已处理（不重新进入待处理）；g2 依断点恢复为待处理；组/断点/记录无重复无丢失。
10. **旧快照晚到不覆盖**：恢复后制造旧快照晚到条件（或以既有 PR50 竞争门控的存在性+最终状态一致性断言），最终 UI/查询状态与第 9 条一致。

### 通用断言纪律
- 每阶段前后 shell 双面核对（app 查询 + shell 查询）+ 哨兵存活。
- 截止轮询、失败留证（截图+树 dump，文件名不含 `|`）、无固定 sleep。
- 预期 case 数量声明；数量门接 probe 脚本。

## 生产问题协议
有效失败（目标断言确实执行）→ 保存证据 → 最小修复 → 独立审计。设备条件不足 → 保存已完成实现 + 具体阻塞 + 解锁步骤（合格交付）。

## 验收口径
阶段 1–10 全断言绿（双 API）= B2 完整验收；任一阶段受阻 = 已完成阶段单独验收 + 阻塞登记（解锁步骤）。
