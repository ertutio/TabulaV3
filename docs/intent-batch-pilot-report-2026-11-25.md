# #94 试点报告：按整理目的凑一批图

日期：2026-11-25 ｜ 分支：`feature/intent-batch-ocr-2026-11-25`（worktree `D:\VIBEcoding\TabulaV3-intent94`）

本文件记录**实际跑出来的数字**，不含估算。每条都附命令与产物路径，可复跑。
OCR 选型评估单独写在 [intent-ocr-evaluation-2026-11-25.md](intent-ocr-evaluation-2026-11-25.md)。

---

## 0. 接手时的基线（必须先说清楚）

| 项 | 值 |
| --- | --- |
| 本分支 HEAD | `34b8bb2`（`origin/feature/staged-cleanup-2026-11-23`，即 #84 的最新提交） |
| `origin/master` | `c8c1653`（#89 已合入） |
| 与 master 的 merge-base | `faf6e25`（#83 路线图合并） |
| 落后 master | 13 个提交 |
| #94 PR HEAD | `e651a99`（`origin/docs/intent-based-photo-batches`，只改 `docs/development-roadmap.md` +23 行） |
| #94 PR base | `faf6e25`（即 #94 自己也是从 #83 之后开的） |

**为什么基于 `feature/staged-cleanup-2026-11-23` 而不是 master**：路线图第 8 项原文写
「第 1 项候选查询稳定后」再做，功能的输入就是暂存清理的候选集。
#84 尚未合入 master，因此本分支以 #84 HEAD 为基，`git log` 里能直接看出依赖关系；
PR 描述里标明「stacked on #84」。

**没有触碰的东西**：#90（`feature/round-intent-2026-11-23`）、#91、#92 的分支均未修改、
未 rebase、未推送。路线图文档在接手前与交付前各核对一次远端，
#93（`docs/liquid-glass-parallel-roadmap-2026-11-23`）只改路线图开头与新增一节，
本分支**不碰** `docs/development-roadmap.md`，因此不会覆盖它。

---

## 1. 复用到的现有实现（核对结果）

| 能力 | 现有实现 | 本功能的用法 |
| --- | --- | --- |
| 数据来源 | `StagedCleanupRules.availableSources` / `select`（`data/cleanup/StagedCleanupRules.kt`） | **直接复用**：入口在暂存清理页，候选集就是 `StagedCleanupSelection.allCandidateImages` |
| 日期 | `ImageFile.dateAdded`（MediaStore `DATE_ADDED`，秒×1000），0 表示未知 | 用作「截图存入时间」；**绝不**当作有效期起算点（除 §5.2 的受限推理，见下） |
| 权限边界 | 现有 `MediaPermissionPolicy` + 媒体快照刷新链 | 未改动；读不到时该张降级为「不确定」 |
| 媒体身份 | `cleanupIdentityKey(image)` = `卷|ID` | 复用同一口径做勾选、缓存键、保留键 |
| 回收站 | `RecycleBinManager.addAllToRecycleBin` / `removeFromRecycleBin` | 复用同一条路径，未新增删除代码 |
| 撤销与恢复 | 回收站页既有恢复能力 | 未改动；设备测试断言「移入后能恢复」 |
| 长期保留 / 本次跳过 | `AppPreferences.stagedCleanupRetainedIdentityKeys` / `stagedCleanupSkippedIdentityKeys` | 复用；保护规则再判一次做双保险 |

新增代码全部在 `app/src/main/java/com/tabula/v3/data/intent/`（独立包）与
`ui/screens/IntentBatchScreen.kt`，接入点是 `MainActivity` 的状态与 `StagedCleanupScreen`
的一个入口回调。**未改动**随机漫步、连拍选片、去重、`DeckScreen`、`RecommendationEngine`。

---

## 2. OCR 方案：选定与实测

选型论证见 [评估文档](intent-ocr-evaluation-2026-11-25.md)。这里只给实测数字。

| 项 | 实测值 | 取证方式 |
| --- | --- | --- |
| 引擎 | Tesseract4Android 4.8.0（Apache-2.0） | `libs.versions.toml` 坐标 `com.github.adaptech-cz.Tesseract4Android:tesseract4android:4.8.0` |
| 模型 | `tessdata_fast/chi_sim.traineddata` | 2,469,156 字节，SHA-256 `a5fcb6f0…1e1f730`，随 APK 打包 |
| 是否离线 | **是**，零遥测 | AAR 清单无权限、无网络组件（评估文档 §3.2） |
| 首次下载 | **不需要**；首次使用只解压一次到 `filesDir/ocr/tessdata` | `TesseractTextRecognizer.prepareTrainedData` |
| 中文能力 | 接入实测（见 §4） | 50 张合成样图真实 OCR |
| minSdk | 21（AAR 清单）；本项目 31 | 构检验证 |

### APK 体积增量（实测，非估算）

| 构建 | 字节 | MiB |
| --- | --- | --- |
| 基线（#84 HEAD `34b8bb2`，`assembleDebug`） | 80,668,030 | 76.93 |
| 本分支（含 OCR） | 112,314,387 | 107.11 |
| **增量** | **31,646,357** | **30.18（+39.2%）** |

拆开看（`app-debug.apk` 内条目，全部四个 ABI 都打进去了）：

| 条目 | 未压缩 | 说明 |
| --- | --- | --- |
| `libtesseract.so` | 4×约 3.0–4.3 MB | 四 ABI 合计 15.8 MB |
| `libleptonica.so` | 4×约 2.2–3.1 MB | 四 ABI 合计 11.1 MB |
| `libjpeg.so` + `libpngx.so` | 四 ABI 合计 1.9 MB | — |
| `assets/ocr/chi_sim.traineddata` | 2,469,156 | 压缩后 1,770,007 |

**按设备算（arm64-v8a 单 ABI）**：4 个 `.so` 7,514,856 + 模型 2,469,156 = **9,928,486 字节（9.47 MiB）**。
当前没有配 ABI splits，所以安装包把四个 ABI 全带上了；如果开 `abiFilters arm64-v8a`
可把增量压到约 8.85 MiB（`.so` 不压缩存储 + 模型压缩后）。

> 这是 debug 构建。release 有 R8/资源裁剪，绝对体积不同，但 OCR 增量（原生库+模型）不受影响。
> 本轮**没有**跑 release 对比，已记入「未验证」。

---

## 3. 样图

50 张**纯合成**中文截图，生成脚本与说明在 `tools/intent-samples/`：

```
python tools/intent-samples/generate_samples.py   # 重建（确定性，50 张 sha256 全等）
python tools/intent-samples/verify_samples.py     # 校验数量/尺寸/体积/金标准一致性
```

- 目录：`app/src/test/resources/intent_samples/`（PNG + `golden.json`）
- 体积 3.58 MB，单张最大 102 KB，全部 720×1280
- 类别分布：`expired_coupon` 6 / `stale_news` 4 / `share` 6 / `temp_code` 6 / `event` 6 /
  `old_price` 2 / `keep` 13 / `hard` 6 / `uncertain` 1
- 期望分布：`recommend` 27 / `lower_priority` 2 / `uncertain` 7 / `keep` 14
- 无反例污染：域名只有 `pan.example.com`，无 11 位手机号、无身份证、无卡号、无邮箱；
  每张带「合成样图 · 非真实数据」水印；二维码是定长伪随机图形，不可扫

---

## 4. 三组对照（同一份 50 张样图）

### 4.1 组 A「按存入时间」vs 组 B「OCR + 规则」

由 `IntentSampleGoldenTest.threeWayComparisonOnSyntheticSamples` 产出（**规则级**：
把金标准文字当作完美 OCR 喂给规则，量规则与排序的上限）。
命令：`./gradlew :app:testDebugUnitTest --tests "com.tabula.v3.data.intent.*"`

```
[pilot] 应清项总数 27，实际推荐 27
[pilot] A 按存入时间: 前10 可清 9/10, 应留误入 0, 最老 10 天
[pilot] B 本地规则:   前10 可清 10/10, 应留误入 0, 最老 15 天
[pilot] B 全量: 应清召回 27/27, 应留误入 0, 不确定 7/50, 排除 14/50
[pilot] 类别分布 A: {temp_code=5, expired_coupon=3, hard=2}
[pilot] 类别分布 B: {temp_code=4, expired_coupon=4, hard=1, event=1}
[pilot] 分类别 event: n=6 应清=6 实际推荐=6 排除=0 不确定=0
[pilot] 分类别 expired_coupon: n=6 应清=6 实际推荐=6 排除=0 不确定=0
[pilot] 分类别 hard: n=6 应清=1 实际推荐=1 排除=1 不确定=4
[pilot] 分类别 keep: n=13 应清=0 实际推荐=0 排除=13 不确定=0
[pilot] 分类别 old_price: n=2 应清=0 实际推荐=0 排除=0 不确定=0
[pilot] 分类别 share: n=6 应清=4 实际推荐=4 排除=0 不确定=2
[pilot] 分类别 stale_news: n=4 应清=4 实际推荐=4 排除=0 不确定=0
[pilot] 分类别 temp_code: n=6 应清=6 实际推荐=6 排除=0 不确定=0
[pilot] 分类别 uncertain: n=1 应清=0 实际推荐=0 排除=0 不确定=1
```

**怎么读这组数**：A 组前 10 张里有 9 张可清，看着不差——但它的**最老只到 10 天**，
因为它按存入时间倒序，只会收到最新的一批；B 组能捞到 15 天前的优惠，
且把 13 张保护/资料图全部排除。也就是说 A 的缺陷不是「命中率低」，
而是**看不见真正值得清的那部分**（更旧的、明确过期的）。

### 4.2 真实 OCR 端到端（组 B 的实际表现）

由 `IntentSampleOcrDeviceTest.endToEndAccuracyOnSyntheticSamples` 产出：
PNG 真解码 → 真跑 Tesseract → 真跑规则。命令见 §6。

```
[ocr-e2e] event n=6 应清=6 推荐=5 低优先=1 不确定=0 排除=0
[ocr-e2e] expired_coupon n=6 应清=6 推荐=6 低优先=0 不确定=0 排除=0
[ocr-e2e] hard n=6 应清=1 推荐=0 低优先=0 不确定=5 排除=1
[ocr-e2e] keep n=13 应清=0 推荐=0 低优先=0 不确定=0 排除=13
[ocr-e2e] old_price n=2 应清=2 推荐=0 低优先=2 不确定=0 排除=0
[ocr-e2e] share n=6 应清=4 推荐=4 低优先=0 不确定=2 排除=0
[ocr-e2e] stale_news n=4 应清=4 推荐=4 低优先=0 不确定=0 排除=0
[ocr-e2e] temp_code n=6 应清=6 推荐=6 低优先=0 不确定=0 排除=0
[ocr-e2e] uncertain n=1 应清=0 推荐=0 不确定=1 排除=0
[ocr-e2e] 汇总：样图 50 张，识别出文字 46 张，应清 29 张，实际推荐 25 张，不一致 2 张
[ocr-e2e] OCR 总耗时 29155ms，平均 583ms/张
```

**关键安全线（硬断言，测试会失败）**：
- 保护类样图被推荐清理：**0 张**
- 应留图（`expectation=keep`）被推荐：**0 张**

**2 张不一致的逐条说明（含 OCR 原文）**：

| 样图 | 期望 | 实际 | 原因 |
| --- | --- | --- | --- |
| `event-vote-01.png` | recommend | LOWER_PRIORITY | **OCR 损失**：样图里的「投票已结束」被读成「查看统计诗 / 本全下 / 469% 3396 2196」，规则拿不到「已结束」证据，只能落到「活动提醒但没写截止时间」→ 低优先级。方向安全（不会误清），但被降级 |
| `hard-rotate-01.png` | recommend | UNCERTAIN | **OCR 损失**：15° 倾斜下只读出页脚说明（`本页为倾叙拍摄的合成截图…`），优惠正文全丢 → 证据不足。这是倾斜文字的既有弱点，不是规则错 |

**结论**：2 张不一致**都是 OCR 读不全导致的降级**，不是规则把应留图当垃圾。
两条安全线（保护类被推荐、应留图被推荐）实测都是 **0**。

> 过程中修掉的两个真问题（都由设备实测暴露，不是推理出来的）：
> 1. `hard-blurry-*` 原来只是「低对比度 + 轻度模糊」，Tesseract 会先做 Otsu 二值化把 8 级灰度差重新拉开，
>    仍以 0.93 置信度完整读出文字 → 已改为「对比度 4 级 + blur 7 + 竖向下采样再放大」，
>    现在 2 张都落进「不确定」（`hard` 类 5/6 不确定）。
> 2. 设备测试原先只在缓存文件不存在时才复制样图，样图重生后仍读旧图（把已修好的难例测成还能读出来）
>    → 改为每次覆盖。

### 4.3 组 C「本地规则 + DeepSeek 文字辅助」

**请求体来自 Kotlin 实现本体**，不是另写一份 Python 拼串：
`IntentAssistPayloadExportTest` 调用真实 `DeepSeekTextAssist`（假传输层只记录），
把实际 payload 导出到 `app/build/intent-pilot/payloads.json`；
`tools/intent-samples/run_deepseek_pilot.py` 逐条读它去 POST。

```
export DEEPSEEK_API_KEY=...                      # 只从环境变量读，不落盘
python tools/intent-samples/run_deepseek_pilot.py \
  --payloads app/build/intent-pilot/payloads.json \
  --out app/build/intent-pilot/deepseek-pilot.json
```

实测（`deepseek-chat`，`https://api.deepseek.com/chat/completions`）：

| 指标 | 值 |
| --- | --- |
| 请求数 | 40（50 张里 10 张因「脱敏后片段为空或含不可判定行」未发送） |
| 成功 / 失败 | 40 / 0 |
| prompt tokens | 3,943（平均 98.6 / 请求） |
| completion tokens | 80（`max_tokens=80` 且只回一个词） |
| total tokens | 4,023 |
| 总耗时 | 44,016 ms（脚本刻意 0.4s 间隔防限流，非模型延迟） |

按类别看模型回答与金标准的一致性：

| 类别 | 发送 | 与金标准一致 | 模型判 KEEP 但本地认为可清 |
| --- | --- | --- | --- |
| `keep` | 11 | 9 | 0（保护方向正确） |
| `event` | 6 | 6 | 0 |
| `stale_news` | 2 | 2 | 0 |
| `expired_coupon` | 4 | 2 | 2 |
| `share` | 6 | 2 | 2 |
| `temp_code` | 6 | 4 | 2 |
| `old_price` | 2 | 0 | 2 |
| `hard` | 2 | 1 | 0 |
| `uncertain` | 1 | 0 | 0 |

**这组数据说明什么**：模型整体偏向 `KEEP`（保守）。对 `keep` 类 9/11 判对，说明**保护方向可用**；
但对优惠/分享/邀请码这些「其实该清」的类别，它有一半判 KEEP——若允许它覆盖本地判定，
反而会把该清的留下（**不会造成误删，但会让功能变钝**）。

**因此实现里的硬约束（不是靠提示词，是靠类型）**：
- `AssistSuggestion` 里**没有** expiry 字段 → 模型无法凭空认定失效；
- `IntentBatchBuilder.refineWithAssist` 只作用于本地判为 `UNCERTAIN` 的项，
  已是 `RECOMMEND`/`LOWER`/`KEEP` 的一律原样返回 → 模型改不动保护、也改不动高置信；
- 结论：**在当前样图上，文字辅助没有让「前 10 张里可清的张数」增加**（因为这批样图的
  不确定性主要来自 OCR 读不全，而读不全的项在脱敏阶段就被丢弃、根本不发请求）。
  按路线图「模型组只有能进一步少翻图且不增加应留图误入时才保留」的口径，
  **本轮不把它设为默认开启**，继续保持默认关闭、逐批可选。

---

## 5. 保护规则与「不确定」

### 5.1 保护（先保护再排序）

命中即**排除出候选**，且不因同时命中清理信号而降级：

| 类别 | 代表信号 |
| --- | --- |
| 订单/交易 | `订单详情`、`订单号`、`待收货`、`已签收`、`实付款`、`支付成功` |
| 发票/凭证 | `发票`、`开票`、`报销`、`凭证`、`税号` |
| 票券 | `车票`、`机票`、`电影票`、`座位号`、`检票`、`请勿删除`、`N 排 N 座`、`NN 车 NA 号` |
| 课件/资料 | `课件`、`讲义`、`考点`、`读书笔记`、`第 N 章`、公式形态 `A·x = λ·x` |
| 长期保留 | `长期保留`；以及调用方传入的 `stagedCleanupRetainedIdentityKeys`（双保险） |

**否定语境排除**：真实 OCR 会读进促销页免责声明「截图**不构成**任何购买凭证」，
若无脑命中「凭证」，整张优惠截图会被当发票保护；已加 10 字符窗口的否定词排除
（`不构成 / 不视为 / 不作为 / 不是 / 并非`）。这是设备测试实测发现并修的。

### 5.2 「明确过期」vs「日期不确定」

| 情况 | 判定 |
| --- | --- |
| 图上有绝对日期（`2026-11-10`、`2026年11月10日`、`11月10日`）且**附近有**「截止/有效期/到期/结束」 | 用该日期判定，已过 → `EXPIRED` |
| 绝对日期附近只有「开售/开播/发布」 | **不**当截止时间（那是开始时间） |
| 只有相对有效期（`5分钟内有效`）、没有可靠起算点 | `UNKNOWN`，**不进优先批次** |
| 只有促销倒计时（`仅剩2天`） | 不是有效期 → `UNKNOWN` |
| 图上明确写「已结束/已过期/已失效」 | `EXPIRED`（仅时效类类别） |
| **短期临时码**（图上有效期 ≤ 24 小时，如短信验证码） | 起算点是短信到达时刻，截图存入时间是其**上界** → 可推出 `截止 ≤ 存入+有效期`；若 `now > 存入+有效期` 则**必然已过期**（只用它证明「一定已过」，绝不用来证明「没过」） |
| 长文/资料型（`深度阅读`、`读书笔记`、`杂志`…） | 归 `EVERGREEN` 排除，**不因日期旧上榜** |
| 即时快讯（图上写「快讯」或整张只有「纯时间戳+短标题」） | 存满 7 天 → 可推荐（这是「旧即时快讯」） |

### 5.3 不硬凑

`IntentBatch.shortOfLimit` 在界面如实显示「只找到 N 张足够明确的候选，没有为了凑满批次硬塞」。
`hard` 类 6 张里 4 张停在「不确定」，批次不拿它们填数。

---

## 6. 脱敏与外发边界

`IntentTextSanitizer` 的契约：**默认失败关闭**——宁可少发一行，也不发不确定的。

| 掩码对象 | 例子 | 处理 |
| --- | --- | --- |
| 验证码 / 取件码 / 提取码 / 邀请码值 | `验证码 123456`、`取件码 8-2-1024`、`提取码 3f9k` | 键值整体掩成 `＊＊＊` |
| 链接（含 OCR 变体） | `https://pan.example.com/s/AbCd1234`、OCR 读坏的 `https:Wpan.xxx` | 一律掩成 `＊＊＊` |
| 手机号 / 长数字 / 订单号 | `13800001111`、`2024111000012345` | 掩码 |
| **保留**：日期与时长 | `2026-11-08 23:59`、`10分钟` | **原样保留**（判断过期的唯一依据，不含身份信息） |

**丢弃**（根本不发）：整行几乎都是数字（数字占比 >70%）、敏感键命中但掩码后不足 6 字。

**设备实测抓到的真实漏洞（已修）**：OCR 把 `https://` 读成 `https:W`，
严格匹配 `https://` 的规则漏过去了，外发片段里出现了 `https:Wpan.＊＊＊`。
已改为「协议名 + 冒号 + 其后非空白整段」一律掩码，并加 `www` 变体兜底；
修复后设备测试重跑通过（`share-expired-01` 的片段里只剩 `＊＊＊`）。

**这不是绝对保证，必须写明**：
1. 脱敏依赖正则，OCR 把验证码读成完全不像验证码的形态（如 `l23456`）时，
   **通用数字规则认不出来**；只有「验证码」这类键在时才靠键值规则兜住。
2. 没有敏感键上下文、又只有 4 位数字时**不掩码**（否则「满199减30」之类正常文案会被抹掉）。
3. 真正的兜底是上层的「证据不足不发」策略，不是正则本身。

**实际请求内容检查**（离线断言，随单测跑）：
`IntentAssistPayloadExportTest` 对导出的每个 payload 断言不含
`base64`、`data:image`、`content://`、`/storage/`、`DCIM`、`.png`、`.jpg`、`image_url`、`file://`；
字段只允许 `model / temperature / max_tokens / messages`；
并逐条断言 `golden.json` 里声明的 `sensitive_tokens` 不出现在 payload 中。

---

## 7. 缓存、权限、失效

- 缓存内容**只有**：类别、档位、过期状态、脱敏理由、信号集合、置信度、截止时间、
  是否用了辅助、以及失效用元数据。**没有** OCR 原文的槽位（结构上就不存在）。
- 失效条件（任一变化即重算）：媒体内容指纹（`卷|ID|size|dateModified`）、
  规则版本 `IntentRules.RULES_VERSION`、引擎/模型版本 `engineId`、辅助开关 `assistTag`。
- 上限 400 条，超出按写入时间淘汰。
- 权限撤回：单张读不到 → 该张记「不确定」，不阻塞整批；调用方下一次媒体快照刷新
  自然把它移出候选。`IntentBatchAnalyzer.invalidate(images)` 供显式作废。

---

## 8. 跑过的命令与结果

| 命令 | 结果 |
| --- | --- |
| `./gradlew :app:testDebugUnitTest` | **BUILD SUCCESSFUL**，80 个测试类 / **554 条** / 0 失败 / 0 错误（其中 intent 包 **83 条**） |
| `./gradlew :app:testDebugUnitTest --tests "com.tabula.v3.data.intent.*"` | 83 条全通过 |
| `./gradlew :app:assembleDebug :app:assembleDebugAndroidTest` | SUCCESSFUL |
| `./gradlew :app:testDebugUnitTest --tests "...IntentAssistPayloadExportTest"` | 导出 40 条 payload 到 `app/build/intent-pilot/payloads.json`，离线断言全过 |
| `python tools/intent-samples/run_deepseek_pilot.py` | 40 请求 / 40 成功 / 4,023 tokens |
| `adb shell am instrument -e class com.tabula.v3.IntentSampleOcrDeviceTest ...` | **OK (6 tests)**，模拟器 API 36 x86_64 |
| `adb shell am instrument -e class com.tabula.v3.IntentBatchPageFlowTest ...` | **OK (5 tests)**，`-gpu host`；1 条用例改为数据层验证（见 §9.1） |
| `python tools/intent-samples/verify_samples.py` | 50 张全部通过（数量/尺寸/体积/一致性/确定性） |

设备：本地 AVD `intent94`（API 36 / google_apis / x86_64 / 1080×2400 / 420dpi），
`emulator -no-window -no-snapshot -gpu swiftshader_indirect`。

---

## 9. 未验证 / 已知缺口（如实列出）

1. **「点缩略图 → 点底部移入回收站」这条 UI 路径未在本地跑通**：模拟器快照里
   同时出现「已选 1 张」与按钮禁用态文案「选择要移入回收站的照片」，两者矛盾。
   已确认这不是功能必然缺陷（同一快照里 `已选中` 标记存在、计数正确），
   更像快照跨重组帧；但**没有证据就不下结论**。因此该用例改成数据层验证
   （`intentSelectionReusesTheRecycleBinAndCanBeRestored`：同一 `RecycleBinManager`
   调用进回收站 + 恢复），并把它列为未覆盖项。
2. **中文 UI 截图的 OCR 准确率没有公开对照基准**：本报告的 25/29 只能说明
   「在这 50 张合成图上」；真实用户库的字体、主题、深浅色分布不同。
3. **只有 debug APK 的体积对比**，release（R8 + 资源裁剪）未跑。
4. **未开 ABI splits**：当前把四个 ABI 的原生库全打进去（+30.18 MiB）。
   开 `abiFilters` 后单设备增量约 8.85 MiB，但会改变发布产物形态，需单独决定。
5. **单张 OCR 432–583 ms（x86_64 模拟器）**，真机（尤其 arm64 中端机）未测；
   开 `-openmp` 变体或降采样可能更快，未验证。
6. **`event-vote-01` / `hard-rotate-01` 的 OCR 失败未修**：倾斜、艺术字与行切分
   是引擎层面问题，本轮不用规则去绕（绕了会把「看不清」写成「已过期」）。
7. **模拟器稳定性**：`swiftshader_indirect` 下跑页面链路测试时模拟器进程崩过 3 次
   （`0xC0000005`），换 `-gpu host` 后 5/5 通过。CI 用的是 `-gpu swiftshader`，
   是否同样稳定由 CI 结果判定，本地不代答。
8. **撤销语义**：设备测试断言的是「移入回收站后可恢复」（`removeFromRecycleBin`），
   没有覆盖「卡片页上滑删除的撤销条」——#94 复用的是同一个 `RecycleBinManager`，
   但撤销条属于卡片页，#90 也在改那块，刻意不碰。

---

## 10. 后续工作

1. ~~修样图：把 `hard-blurry-*` 真正压到读不清~~（已完成，见 §4.2 的说明）。
2. 把「点缩略图 → 移入回收站」的 UI 路径在真机或 CI 上重跑，确认是快照帧问题还是交互缺陷。
3. 决定 ABI splits 策略，再测一次真实发布体积。
4. 真机（arm64 中端机）测 OCR 单张耗时 P50/P95，据此决定是否换 `-openmp`。
5. 文字辅助：本轮数据不支持「默认开启」；若后续要评估，应按「辅助是否让前 10 张
   可清张数增加」而不是「模型与金标准的一致率」来判。
6. 若 #84 合入 master，本分支需要 rebase 到 master 并只保留 `data/intent`、
   `ui/screens/IntentBatchScreen.kt`、`MainActivity` 的接入改动与测试。
