# T13 / PR #73 独立审计（2026-11-12，全新会话静态核验+CI 证据）

**总评：可合并。** 范围干净、构造真实、断言无恒真、三项登记偏差均与源码/CI 实测一致；CI run 43058202316 三 job 全绿且绑定 head 860bedb（PR headRefOid=origin 分支=run headSha 三方一致，head 未移动）。

## 逐项结论
1. **范围（PASS）**：`git diff --name-status 488bd21..860bedb` 恰 3 个 `A`（upgrade-verification.yml 180 行 / run-upgrade-probe.sh 351 行 / UpgradeOverlayDeviceTest.kt 267 行），app/src/main 与 app/src/test 零改动。
2. **双 APK 构造（PASS）**：yml 双 worktree（head+43a8da1）双 `git status --porcelain` dirty 守卫，source-identity.txt 记 newHead/newTree/oldHead=43a8da1/oldTree；badging 门校验同包名 com.tabula.v3.recyclebin.test+versionCode 5+新旧 test APK 包名相等；SHA256SUMS 记 6 件。CI 构件实测：newHead=860bedb…、newTree=f322006…、oldTree=41b11ee…。`git show 43a8da1:app/src/androidTest/.../FullRecoveryPhase1Test.kt` 存在且自带 allowSyntheticMediaProbe/ranchu 门（probe 参数匹配）。
3. **种子与覆盖链（PASS）**：probe 顺序与判据 5–10 映射完整：装 OLD app+testAPK→Phase1 am instrument（OK(1) 门+失败模式 grep+window 前后）→root 注入 metadata(isHidden:true 读回校验)+内部哨兵+冷却种子（按 expected-state 成员键）→启动 OLD 一次校验 metadata 未被改写→force-stop→`install -r` NEW（断言 Success/无 "signatures do not match"/无 INSTALL_FAILED，构件 lastUpdateTime 16:29:22→16:29:44）→NEW testAPK→用例 1→shell 媒体真值→两次冷重启→用例 2→FATAL 门+收证。同签名（双方 debug key）+同 versionCode 5+同 applicationId，`-r` 合法。
4. **断言真实性（PASS）**：判据 5=SEED 计数(0) 断言+真实回收/恢复演练（身份键解析→恢复→归零→字节面 BitmapFactory 可解码+无缺失）；6=hiddenAlbumCount==1+UpgAlbum isHidden+空图集 imageCount==0；7=checkpoint (组,1) 跨版本读回+getCheckpointBatch 续接非 null；8=生产 rebuildRow 身份键一致+loadAllData 全含+shell 面 content query 每名恰 1 行（R2 实测 3 行×1）；9=13 字段快照零漂移（字段清单逐项 opt() 对比，漂移字段入报错信息）；10=哨兵内容精确匹配+phase1Pid≠phase2Pid 进程换代锚+FATAL 门（实测 0）。awaitUntil 截止轮询、withEvidence 落盘后重抛、无裸 sleep、无吞失败（probe 每门 fail() 置 status 并以退出码结算）。构件中 OK(1)×3、FATAL 0、快照 13 字段实值（grp_d8aeced48875dc59/位置1/3 键/回收 0/hidden 1/Upg 幸存）。
5. **登记偏差（均合理，如实）**：① debug 插桩对（applicationIdSuffix=.recyclebin.test，构件 versionName=3.3.5-recyclebin-test）——AGP 8.9.0 testBuildType 默认 debug，`assembleReleaseAndroidTest` 任务确不存在（build.gradle.kts 无 override，外部佐证一致）；release 对照物按 A.1 构造记录为 unsigned——`gh api .../actions/secrets` total_count=0 证实"无任何 secrets"，forkRelease 签名以 TABULA_KEYSTORE_FILE 存在性条件化（缺省不崩），与声明一致。② 种子=单月组 3 成员+断点位置 1、零成员被处理——43a8da1 Phase1 源码与构件 expected-state 一致；判据 5 按 Phase1 真实行为落地并明文登记（哨兵=私有文件，无 s1）。③ 冷却被 OLD 生产逻辑合法清空——RecommendationEngine.kt:253 `allImages.size<=batchSize → clearAllPickRecords` 属实，构件 pre/post 升级 pick_timestamps 均 0 条 time_ 键，实测吻合；登记为种子边界发现，其余 12 字段实值。偏差原文在 PR body 指向 coord/notes-T13.md。
6. **CI 证据（PASS）**：run 43058202316=event push、headSha=860bedb、conclusion success、jobs=build(14 步)+device(API36)+device(API37) 全绿；R1=run 43054397232（23f167f，failure，双 API 均红于 shell 媒体面）→860bedb 修复=删 `--where` 过滤改全量 display_name 列表+root→unroot 兜底+双份原始输出留证；R2 构件 media-truth 实录 3 名各 1 行。

## 问题分级
- **P1/P2：无。**
- **P3-a**：判据 9 的冷却字段因偏差③为"空 vs 空"对比（该单字段无正荷载荷）；已如实登记，可选改进=OLD force-stop 后重新注入种子再覆盖升级。
- **P3-b**：860bedb 提交信息将 R1 红因归于 root uid 不可见，而 R2 构件显示 root 模式全量查询成功（真因更可能是 `--where` 过滤路径）；叙述不精确但兜底+留证设计稳妥，不影响结论。
- **P3-c**：幂等对比用 JSON toString 逐字段比较（org.json 无序），依赖同代码同插入序，当前 13 字段规模下无实际风险。
