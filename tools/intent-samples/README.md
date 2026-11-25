# intent-samples · 意向整理样本生成器

为 TabulaV3 的「按整理目的凑一批图」能力生成 **50 张纯合成中文截图样图** 与配套金标准
`golden.json`。产物落到 `app/src/test/resources/intent_samples/`，供单元测试与
Robolectric 用例按类别读取。

> **全部内容为合成假数据。** 不含任何真实用户截图、真实手机号、真实订单号、真实链接、
> 真实验证码、真实姓名或真实平台账号。

---

## 1. 产物位置

| 路径 | 内容 |
| --- | --- |
| `tools/intent-samples/generate_samples.py` | 生成脚本（唯一的写入入口） |
| `tools/intent-samples/verify_samples.py` | 校验脚本（数量 / 尺寸 / 体积 / 金标准一致性 / 确定性） |
| `tools/intent-samples/.sha256_baseline.json` | 确定性基线（50 张 PNG 的 sha256） |
| `app/src/test/resources/intent_samples/*.png` | 50 张 720×1280 PNG |
| `app/src/test/resources/intent_samples/golden.json` | 金标准（UTF-8 无 BOM，键为文件名） |

本目录只写这两处，不触碰 `build.gradle.kts`、不产生 git 提交。

---

## 2. 如何重跑

```bash
# 生成（会先清空目标目录里的 *.png，再全量重建）
python tools/intent-samples/generate_samples.py

# 校验（--record 仅在需要重置确定性基线时使用）
python tools/intent-samples/verify_samples.py
```

依赖：Python 3.12 + Pillow（脚本不使用 numpy 等额外包）。

### 字体回退链

脚本按顺序探测，命中即用；全部缺失时退回 Pillow 内置位图字体（无中文字形，仅保证不崩）：

1. `C:/Windows/Fonts/msyh.ttc`（微软雅黑）
2. `C:/Windows/Fonts/MSYH.TTC`
3. `C:/Windows/Fonts/msyhbd.ttc`
4. `C:/Windows/Fonts/simhei.ttf`（黑体）
5. `C:/Windows/Fonts/simsun.ttc`（宋体）
6. Linux `NotoSansCJK-Regular.ttc` / `wqy-zenhei.ttc`
7. macOS `PingFang.ttc`
8. `PIL.ImageFont.load_default()`

### 确定性

- 全局种子 `20261125`，每个文件再混入 `crc32(文件名)` 派生子种子 → 同一台机器重跑字节一致。
- 生成结束后 `verify_samples.py` 会用 `.sha256_baseline.json` 逐张比对 sha256。
- 重跑幂等：每次先 `unlink()` 目标目录下所有 `*.png`，再全量写回，不会残留旧图。

### 体积控制

720×1280 起步；若某张超过 118 KB，就依次尝试 `RGB → 256 → 192 → 128 → 96 → 64` 色
调色板量化（`MEDIANCUT`、关闭抖动），取第一个达标结果。当前 50 张全部命中 `rgb` 直存。

---

## 3. 每张图对应的验收意图

`expectation` 四档语义：

| 值 | 含义 |
| --- | --- |
| `recommend` | 有明确失效 / 过期证据，应作为清理候选推荐 |
| `lower_priority` | 有陈旧迹象但缺少明确截止日期，降级而非直接推荐 |
| `uncertain` | 证据不足（无文字、不可读、只有二维码），应保持不确定而不是猜 |
| `keep` | 必须保留：长期凭据、知识资料、显式长期保留 |

`must_keep_protected = true` 表示保护规则**必须**排除该图，即使图里同时出现过期文案。

### expired_coupon（6 张）

| 文件 | expectation | 保护 | 存入天数 | golden_lines | expected_signals | deadline | sensitive |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `coupon-expired-01.png` | recommend | — | 9 | 满199减30<br>仅剩2天<br>活动已于 2026-11-10 23:59 结束 | coupon, expired_date, deadline | 2026-11-10 | — |
| `coupon-expired-02.png` | recommend | — | 15 | 满99减10<br>仅剩3天<br>活动已于 2026-11-08 23:59 结束 | coupon, expired_date, deadline | 2026-11-08 | — |
| `coupon-expired-03.png` | recommend | — | 6 | 满299减50<br>仅剩1天<br>活动已于 2026-11-12 23:59 结束 | coupon, expired_date, deadline | 2026-11-12 | — |
| `coupon-expired-04.png` | recommend | — | 4 | 新人立减 20<br>仅剩2天<br>活动已于 2026-11-15 23:59 结束 | coupon, expired_date, deadline | 2026-11-15 | — |
| `promo-flash-01.png` | recommend | — | 21 | 限时秒杀<br>全场 5 折<br>今日 24:00 截止<br>活动截止 2026-11-03 | coupon, deadline, expired_date | 2026-11-03 | — |
| `promo-flash-02.png` | recommend | — | 19 | 限时秒杀<br>整点开抢<br>今日 24:00 截止<br>活动截止 2026-11-05 | coupon, deadline, expired_date | 2026-11-05 | — |

### old_price（2 张）

| 文件 | expectation | 保护 | 存入天数 | golden_lines | expected_signals | deadline | sensitive |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `coupon-oldprice-01.png` | lower_priority | — | 32 | 历史价格对比<br>历史低价 ¥199<br>当前价格 ¥259<br>价格走势（近 90 天） | old_price | — | — |
| `coupon-oldprice-02.png` | lower_priority | — | 37 | 历史价格对比<br>历史低价 ¥88<br>当前价格 ¥129<br>价格走势（近 90 天） | old_price | — | — |

### stale_news（4 张）

| 文件 | expectation | 保护 | 存入天数 | golden_lines | expected_signals | deadline | sensitive |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `news-live-ended-01.png` | recommend | — | 13 | 直播预告 11月8日 20:00<br>本场已结束<br>回到直播间 | news, live, ended | — | — |
| `news-live-ended-02.png` | recommend | — | 12 | 直播预告 11月9日 19:30<br>本场已结束<br>回到直播间 | news, live, ended | — | — |
| `news-flash-old-01.png` | recommend | — | 25 | 地铁 3 号线延长运营时间<br>2026-10-30 07:42 | news, expired_date | — | — |
| `news-flash-old-02.png` | recommend | — | 27 | 示例城市发布寒潮提示<br>2026-10-28 18:05 | news, expired_date | — | — |

### share（6 张）

| 文件 | expectation | 保护 | 存入天数 | golden_lines | expected_signals | deadline | sensitive |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `share-expired-01.png` | recommend | — | 20 | 项目资料.zip<br>提取码已过期<br>复制链接<br>https://pan.example.com/s/AbCd1234 | share, extract_code, expired_date, link | — | — |
| `share-expired-02.png` | recommend | — | 18 | 季度汇报模板.pptx<br>提取码已过期<br>复制链接<br>https://pan.example.com/s/XyZ98765 | share, extract_code, expired_date, link | — | — |
| `share-link-01.png` | uncertain | — | 16 | 团建照片合集<br>示例网盘 · 相册<br>复制链接 | share, link | — | — |
| `share-link-02.png` | uncertain | — | 24 | 参考书目整理.pdf<br>示例网盘 · 文档<br>复制链接 | share, link | — | — |
| `share-cloud-01.png` | recommend | — | 22 | 示例网盘 · 中转站<br>有效期至 2026-11-01 | share, deadline, expired_date | 2026-11-01 | — |
| `share-cloud-02.png` | recommend | — | 26 | 示例网盘 · 中转站<br>有效期至 2026-10-25 | share, deadline, expired_date | 2026-10-25 | — |

### temp_code（6 张）

| 文件 | expectation | 保护 | 存入天数 | golden_lines | expected_signals | deadline | sensitive |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `code-sms-01.png` | recommend | — | 3 | 【示例平台】验证码 123456，5分钟内有效。 | temp_code, verification_code | — | `123456` |
| `code-sms-02.png` | recommend | — | 2 | 【示例平台】登录验证码 246810，10分钟内有效。 | temp_code, verification_code | — | `246810` |
| `code-pickup-01.png` | recommend | — | 5 | 取件码 8-2-1024<br>示例驿站 · 合成网点<br>已取件 | temp_code, pickup | — | `8-2-1024` |
| `code-pickup-02.png` | recommend | — | 7 | 取件码 2-3-0518<br>示例驿站 · 合成网点<br>已取件 | temp_code, pickup | — | `2-3-0518` |
| `code-invite-01.png` | recommend | — | 11 | 临时入群邀请<br>邀请码 INV-8842<br>有效期至 2026-11-02 | temp_code, deadline | 2026-11-02 | `INV-8842` |
| `code-invite-02.png` | recommend | — | 9 | 临时入群邀请<br>邀请码 GX-2026-77<br>有效期至 2026-11-04 | temp_code, deadline | 2026-11-04 | `GX-2026-77` |

### event（6 张）

| 文件 | expectation | 保护 | 存入天数 | golden_lines | expected_signals | deadline | sensitive |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `event-ticket-01.png` | recommend | — | 14 | 示例音乐会 · 合成场次<br>11月5日 10:00 开售<br>已结束 | event, ticket, ended | — | — |
| `event-ticket-02.png` | recommend | — | 12 | 示例话剧 · 合成场次<br>11月6日 20:00 开售<br>已结束 | event, ticket, ended | — | — |
| `event-signup-01.png` | recommend | — | 16 | 周末城市徒步报名<br>报名截止 2026-11-06<br>已截止 | event, deadline, ended | 2026-11-06 | — |
| `event-signup-02.png` | recommend | — | 13 | 示例读书会报名<br>报名截止 2026-11-09<br>已截止 | event, deadline, ended | 2026-11-09 | — |
| `event-vote-01.png` | recommend | — | 17 | 群投票：下次聚会地点<br>投票已结束<br>共 128 人参与 | event, ended | — | — |
| `event-vote-02.png` | recommend | — | 19 | 问卷：产品体验回访<br>问卷已结束<br>共 64 人参与 | event, ended | — | — |

### keep（13 张）

| 文件 | expectation | 保护 | 存入天数 | golden_lines | expected_signals | deadline | sensitive |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `news-article-01.png` | keep | — | 46 | 城市公共空间的三种功能<br>示例杂志 · 深度阅读<br>一、通行与停留 | news | — | — |
| `news-article-02.png` | keep | — | 51 | 旧城改造中的步行网络<br>示例杂志 · 城市观察<br>二、街巷尺度 | news | — | — |
| `news-article-03.png` | keep | — | 58 | 阅读笔记：照明设计与夜间街道<br>示例账号 · 读书笔记<br>三、照度与色温 | news | — | — |
| `protect-order-01.png` | keep | 是 | 30 | 待收货<br>订单号 TB202411250001<br>实付 ¥128.00 | order, order_no | — | — |
| `protect-order-02.png` | keep | 是 | 41 | 待收货<br>订单号 TB202411250002<br>实付 ¥256.00 | order, order_no, phone | — | — |
| `protect-order-03.png` | keep | 是 | 55 | 已完成<br>订单号 TB202411250003<br>实付 ¥59.90 | order, order_no | — | — |
| `protect-invoice-01.png` | keep | 是 | 62 | 电子发票（普通发票）<br>开票金额 ¥1,280.00<br>发票号码 00000001（合成） | invoice, order_no | — | — |
| `protect-invoice-02.png` | keep | 是 | 48 | 电子发票（普通发票）<br>开票金额 ¥368.00<br>发票号码 00000002（合成） | invoice, order_no | — | — |
| `protect-ticket-01.png` | keep | 是 | 33 | 示例影院 · 3 号厅<br>7 排 12 座<br>2026-11-20 19:30<br>请勿删除，入场需出示 | ticket | — | — |
| `protect-ticket-02.png` | keep | 是 | 35 | 示例站 → 示例站<br>08 车 12A 号<br>2026-11-22 08:15<br>请勿删除，检票需出示 | ticket | — | — |
| `protect-slide-01.png` | keep | 是 | 70 | 第三章 线性代数基础<br>A·x = λ·x<br>12 / 48 | courseware | — | — |
| `protect-slide-02.png` | keep | 是 | 66 | 第四章 概率与统计<br>P(A|B) = P(A∩B) / P(B)<br>27 / 48 | courseware | — | — |
| `protect-longterm-01.png` | keep | 是 | 90 | 示例相册 · 家庭合影<br>已加入长期保留<br>共 36 张（合成） | long_term | — | — |

### uncertain（1 张）

| 文件 | expectation | 保护 | 存入天数 | golden_lines | expected_signals | deadline | sensitive |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `share-justlink-01.png` | uncertain | — | 28 | 示例网盘<br>扫码查看文件 | share | — | — |

### hard（6 张）

| 文件 | expectation | 保护 | 存入天数 | golden_lines | expected_signals | deadline | sensitive |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `hard-blurry-01.png` | uncertain | — | 21 | 满199减30<br>活动已于 2026-11-10 23:59 结束 | — | — | — |
| `hard-blurry-02.png` | uncertain | — | 23 | 限时秒杀<br>今日 24:00 截止 | — | — | — |
| `hard-nowords-01.png` | uncertain | — | 8 | （无文字） | — | — | — |
| `hard-nowords-02.png` | uncertain | — | 11 | （无文字） | — | — | — |
| `hard-mixed-01.png` | keep | 是 | 19 | 限时优惠已结束<br>订单号 TB202411250007<br>待收货 | coupon, expired_date, order, order_no | 2026-11-18 | — |
| `hard-rotate-01.png` | recommend | — | 10 | 满199减30<br>活动已于 2026-11-13 23:59 结束 | coupon, expired_date, deadline | 2026-11-13 | — |

---

## 4. 契约与不变量

`golden.json` 顶层：

```json
{ "version": 1, "samples": [ /* 50 条，键为文件名 */ ] }
```

单条字段：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `file` | string | PNG 文件名，与目录内文件一一对应 |
| `category` | enum | `expired_coupon` / `stale_news` / `share` / `temp_code` / `event` / `old_price` / `keep` / `uncertain` / `hard` |
| `expectation` | enum | `recommend` / `lower_priority` / `uncertain` / `keep` |
| `must_keep_protected` | bool | `true` 时 `expectation` 必须为 `keep` |
| `golden_lines` | string[] | 图上实际绘制的主要文字行（顺序与绘制顺序一致） |
| `expected_signals` | string[] | 固定词表：`coupon` `old_price` `expired_date` `deadline` `news` `live` `share` `extract_code` `temp_code` `verification_code` `pickup` `event` `ended` `order` `invoice` `ticket` `courseware` `long_term` `link` `phone` `order_no` |
| `deadline_date` | string | `YYYY-MM-DD` 或空字符串 |
| `screenshot_saved_days_ago` | int | 模拟的存入天数，用于驱动候选排序 |
| `sensitive_tokens` | string[] | 验证码 / 取件码 / 提取码原文片段，供脱敏测试；没有就 `[]` |

不变量（`verify_samples.py` 全部强制）：

1. 目录内 `*.png` 恰好 50 个，且与 `golden.json` 的 `file` 集合完全相等（无孤儿、无缺失）。
2. 每张 PNG 都能被 Pillow `verify()`，尺寸 720×1280，单张 < 120 KB，总体积 < 4 MB。
3. 类别计数：`expired_coupon 6 / stale_news 4 / share 6 / temp_code 6 / event 6 / old_price 2 / keep 13 / uncertain 1 / hard 6`，合计 50。
4. 期望分布：`recommend 27 / lower_priority 2 / uncertain 7 / keep 14`，合计 50。
5. `must_keep_protected=true` ⇒ `expectation=keep`。
6. `temp_code` 类必须声明非空 `sensitive_tokens`，且每个 token 都要出现在该条的 `golden_lines` 里。
7. 模型自检：渲染函数返回的「实际绘制逻辑行」必须与声明的 `golden_lines` 逐字相等，否则脚本以退出码 2 中止 —— 图与金标准不允许脱节。

---

## 5. 合成数据规范

| 类型 | 用到的占位写法 |
| --- | --- |
| 手机号 | `138****0000`、`1069****0000` |
| 订单号 | `TB202411250001` ~ `TB202411250007` |
| 发票号 | `00000001`、`00000002` |
| 链接 | `https://pan.example.com/s/AbCd1234`、`https://example.com/invite/INV-8842` |
| 验证码 | `123456`、`246810`（图上标注「合成示范值」） |
| 取件码 | `8-2-1024`、`2-3-0518` |
| 邀请码 | `INV-8842`、`GX-2026-77` |
| 姓名 / 地址 | `示例用户（合成）`、`示例省示例市示例路 0 号（合成地址）` |
| 平台 / 机构 | `示例网盘`、`示例商城`、`示例驿站`、`示例影院` —— 一律「示例」前缀 |

每张图右下角带 `合成样图 · 非真实数据` 水印（`hard-blurry-*`、`hard-nowords-*` 因刻意
不可读、无文字而省略；`protect-slide-*` 用课件页脚 `示例课件 · 合成内容` 代替）。
二维码为定长伪随机图形，**不可扫描**，图上均有「二维码不可扫描」说明。

---

## 6. 覆盖清单（50 张）

| 组 | 前缀 | 张数 | 期望 | 关键判别点 |
| --- | --- | --- | --- | --- |
| A 过期优惠 | `coupon-expired` | 4 | recommend | 「仅剩2天」+ 明确「活动已于 … 结束」 |
| A 限时活动 | `promo-flash` | 2 | recommend | 「今日 24:00 截止」且图上写明 2026-11-03/05 截止 |
| A 旧比价 | `coupon-oldprice` | 2 | lower_priority | 只有价格、**没有**截止日期 → 走「旧比价」而非「过期」 |
| B 直播 | `news-live-ended` | 2 | recommend | 预告时间 + 「本场已结束」 |
| B 快讯 | `news-flash-old` | 2 | recommend | 「快讯」+ 2026-10-28/30 日期 |
| B 长文 | `news-article` | 3 | keep | 只有标题正文、无时效词 → 不应作为清理候选 |
| C 失效分享 | `share-expired` | 2 | recommend | 「提取码已过期」+ 平台名 + 复制链接 |
| C 普通分享 | `share-link` | 2 | uncertain | 只有平台名 + 标题 + 复制链接，无失效字样 |
| C 网盘中转 | `share-cloud` | 2 | recommend | 「有效期至 2026-11-01/10-25」已过 |
| C 仅二维码 | `share-justlink` | 1 | uncertain | 只有一个二维码 + 平台文案 |
| D 短信验证码 | `code-sms` | 2 | recommend | 需脱敏，`sensitive_tokens` 含 6 位码 |
| D 取件码 | `code-pickup` | 2 | recommend | 「已取件」+ 码已在 `sensitive_tokens` |
| D 邀请码 | `code-invite` | 2 | recommend | 「有效期至 2026-11-02/04」 |
| E 抢票提醒 | `event-ticket` | 2 | recommend | 「11月5日 10:00 开售」+「已结束」 |
| E 报名 | `event-signup` | 2 | recommend | 「报名截止 2026-11-06/09」+「已截止」 |
| E 投票/问卷 | `event-vote` | 2 | recommend | 「投票/问卷已结束」 |
| F 订单 | `protect-order` | 3 | keep | 「待收货」+ 金额 + 订单号 |
| F 发票 | `protect-invoice` | 2 | keep | 「电子发票」+「开票金额」 |
| F 票券 | `protect-ticket` | 2 | keep | 座位号 + 二维码 + 「请勿删除」 |
| F 课件 | `protect-slide` | 2 | keep | 公式 + 章节标题 |
| F 长期保留 | `protect-longterm` | 1 | keep | 「已加入长期保留」 |
| G 模糊 | `hard-blurry` | 2 | uncertain | 极低对比度灰字（前景/背景差约 8 级 + 1.2px 高斯模糊） |
| G 无文字 | `hard-nowords` | 2 | uncertain | 纯渐变照片，`golden_lines` 为空 |
| G 混合 | `hard-mixed` | 1 | keep | 同一图既有订单号又有「限时优惠已结束」→ 保护优先 |
| G 倾斜 | `hard-rotate` | 1 | recommend | 文字整体倾斜 15° |

---

## 7. 已知取舍

- `hard-blurry-*` 的 `golden_lines` 记录的是**语义上应被读到的内容**，不是 OCR 一定能读到的
  结果；这两张的 `expected_signals` 为空数组，正是为了让测试表达「读不出来时不要猜」。
- `hard-nowords-*` 的 `golden_lines` 与 `expected_signals` 都是空数组，图片本体无文字。
- `news-flash-old-*` 用日期而非「已过期」字样表达陈旧，测的是日期推理而不是关键词匹配。
- `share-link-*` 刻意没有失效提示，用于验证「无证据 → uncertain」而不是被平台名关键词
  带成 recommend。
- 全部 PNG 均由 Pillow 现绘，未嵌入 EXIF；如需模拟拍摄设备信息，应在生成脚本里显式补，
  而不是引入外部图片。
