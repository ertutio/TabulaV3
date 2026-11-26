# OpenCode Go 兼容性核实（2026-11-25）

目的：判断能否把 OpenCode Go 作为 #95 文字辅助的**第二个可选服务**，由第三方 Android 客户端
直接调用。只依据 opencode.ai 官方域名与官方 GitHub 仓库，不采信第三方博客。

**结论：可用。** 官方明确允许第三方客户端，唯一硬性门槛是每个请求必须带
`x-opencode-session` 请求头。

---

## 1. 它是什么

[OpenCode Go](https://opencode.ai/docs/console/go) 是 OpenCode（Anomaly Innovations, Inc.）
Console 平台下的**订阅制套餐**，不是客户端也不是独立中转品牌。订阅后拿到一个 API key，
通过 Console 的 inference 网关调用精选模型。

三档订阅（官方原文表格）：

| 计划 | 价格 | 包含 |
| --- | --- | --- |
| Go | $10/月 | 较低成本的模型访问 |
| Go Plus | $40/月 | 更高的用量上限 |
| Go Teams | $20/人/月，2 人起 | 每人独立额度 |

> "Go works like any other provider in OpenCode. You subscribe to OpenCode Go and get your API key.
> It's **completely optional** and you don't need it to use OpenCode."

## 2. 能否第三方调用 —— 能

官方 FAQ：

> "Can I use Go with other coding agents? — **Yes.** Go works with OpenCode and other coding agents
> that send typical coding-agent traffic, identify themselves with their own user agent and send
> a session ID. Claude Code, Codex, Hermes and Pi have been validated."

「Validated clients」表列了 OpenCode 之外的 7 个已验证客户端，并有「Known problematic clients」
表按 session 支持情况分类。**没有任何文档禁止第三方客户端，也没有要求必须用官方 App。**

唯一的硬限制：

> "Apps that don't send a session ID **can't use Go models**. They can use pay-as-you-go models,
> paid from your balance."

官方 Android App（`ai.opencode.mobilecode`）是**远程遥控器**，不是模型客户端：

> "OpenCode Mobile is a remote for OpenCode on your computer. Your sessions keep running there;
> the app shows them and lets you steer them."

## 3. 端点

Go 与 pay-as-you-go **不是同一个 base URL**：

| 计划 | Base URL |
| --- | --- |
| Pay-as-you-go | `https://opencode.ai/inference` |
| **Go** | **`https://opencode.ai/inference/go`** |

Chat Completions 完整地址：

```
POST https://opencode.ai/inference/go/openai/v1/chat/completions
```

模型元数据里 DeepSeek 的 `base_url` 是 `https://opencode.ai/inference/go/openai/v1`，
SDK 标为 `@ai-sdk/openai-compatible` —— 即标准 OpenAI 兼容。

## 4. 认证

- `Authorization: Bearer oc_sk_...`
- **不需要** workspace header：「Each key works in one workspace, chosen when it is created.
  No workspace header is needed.」
- **必须**加 `x-opencode-session: ses_...`：

> "Send a stable session ID with every request so Console can route a conversation consistently
> and reuse its prompt cache. Go **requires it**."

**校验顺序**：先验 session 头，再验 key。实测（无凭据的只读探测）：

```
无 session 头   → 400 {"error":{"type":"MissingSessionID"}}
+ session 头     → 401 {"error":{"type":"authentication_error","message":"OpenCode Go requires an API key"}}
+ session + 假key → 401 {"error":{"type":"authentication_error","message":"Invalid credential"}}
```

权限：key 需要 **Models › Request** 才能调用模型（Models › Read 只能列模型）。

## 5. 模型 ID（官方确切字符串）

**只有两个 DeepSeek 模型**，注意**不是** `deepseek-chat` / `deepseek-v3` / `deepseek-reasoner`：

| 模型 ID | 显示名 | context / output | 单价（输入/输出 per 1M） |
| --- | --- | --- | --- |
| `deepseek-v4.1-flash` | DeepSeek V4.1 Flash 1017 | 1,000,000 / 384,000 | $0.15 / $0.6 |
| `deepseek-v4-pro` | DeepSeek V4 Pro | 1,000,000 / 384,000 | $0.66 / $1.98 |

推理内容在 `reasoning_content` 字段（`compatibility.reasoning_field`）。

### 地区限制（会影响中国用户）

> "By default, paid endpoints that train on request data are blocked, and allowed regions are
> **Europe & United States**. Workspaces without saved privacy settings also allow **Global**
> for requests from China."

需要在 **Settings › Privacy › Regions** 选 **Global**，否则从中国发起的 DeepSeek 请求会被拦。
**这一条是本项目最需要注意的部署前提。**

## 6. 订阅包含 API 额度 —— 是

额度按 API 请求计量（不是只覆盖官方 App）：

> "The paid models you use on Go share one allowance."
> - 5 小时限额 = 月额度的 20%，首次使用起算
> - 每周限额 = 月额度的 50%，周一 00:00 UTC 重置
> - 月额度随新计费周期重置

API 响应里带 `opencode.billing_source`（`free` / `balance` / `go` / `go-plus` / `byok`）与
`opencode.allowance`（本次请求消耗了多少额度美元数）。用量查询：

```
GET https://opencode.ai/inference/go/v1/usage
Authorization: Bearer <同 key>
```

官方明确意图包含第三方客户端读取用量：「for example to show them in your own client」。

## 7. 速率与条款

- **用量配额**：有（5h / 周 / 月三层），公开。
- **每分钟速率（RPM/TPM）与并发上限**：官方**未给出**具体数字，只有 Terms 里
  "unreasonable load" 这种非量化表述。
- **独立 AUP**：不存在。限制写在 Terms of Use「Are there restrictions in how I can use the Services?」，
  与第三方客户端相关的是第 (10) 条（禁止不合理负载）与第 (4) 条（禁止多账号绕过配额）。
  第 (4) 条**不**禁止第三方客户端。

---

## 对本项目的实现约束（据此写代码）

| 项 | 值 |
| --- | --- |
| URL | `https://opencode.ai/inference/go/openai/v1/chat/completions` |
| 认证 | `Authorization: Bearer oc_sk_...` |
| 必带头 | `x-opencode-session: ses_<stable-id>`（每批稳定、换批重建） |
| User-Agent | 应用自有标识（官方明确要求不要用通用 SDK/HTTP 库名） |
| 模型 | `deepseek-v4.1-flash`（默认）/ `deepseek-v4-pro` |
| 回退 | 400/401/403/超时/断网/垃圾响应 → 一律回退本地 OCR 结果 |

## 未确认的部分（不推断）

1. **RPM / TPM / 并发上限**：官方无数字，需实测或联系官方。
2. **Android 平台态度**：官方从未就 Android 作专门表述；官方 Android App 不是模型通道。
3. **`x-opencode-session` 的格式约束**：文档只给 `ses_...` 示例，未说明是否强制该前缀或长度限制。
   实测 `ses_test123` 能通过 session 校验。
4. **通用聊天型客户端是否算「typical coding agent traffic」**：官方措辞是 "designed for …
   coding agents"，属设计意图而非硬禁令。**这是最实质的灰区**——本项目的用途是
   截图分类（一次性、低频、结构化输出），与 coding agent 流量形态接近，但不能断言官方认可。
5. **per-key 隐藏限制**：无凭据无法测试。

## 对 #95 的影响

- 需要**独立分支与 PR**（`feature/intent-opencode-go-2026-11-25`），复用 #95 已审计的
  脱敏（`IntentTextSanitizer`）、授权（`IntentAssistAuthorization`）、本地回退。
- **两个服务各管各的 Key**：DeepSeek 用 `intent-assist.key`，Go 用
  `intent-assist.opencode-go.key`，都在 `noBackupFilesDir`。
- **切换服务撤销本批授权**（复用 `IntentAssistAuthorization.onRebuild()`）。
- Go 失败回到本地 OCR，不影响 DeepSeek 路径。

## 有 Go Key 时的一条最小验证

拿到用户明确授权的 Go Key 后，用**一条合成文本**验证订阅权限（不涉及任何真实截图）：

```bash
KEY=oc_sk_...            # 用户提供，不写入任何文件
SESSION=ses_$(date +%s)

curl -sS -X POST https://opencode.ai/inference/go/openai/v1/chat/completions \
  -H "Authorization: Bearer $KEY" \
  -H "x-opencode-session: $SESSION" \
  -H "User-Agent: TabulaV3-IntentAssist/1.0" \
  -H "Content-Type: application/json" \
  -d '{"model":"deepseek-v4.1-flash","temperature":0,"max_tokens":16,
       "messages":[{"role":"user","content":"只回答一个词：LOWER"}]}'
```

判据：返回 `choices[0].message.content` 且带 `opencode.billing_source: "go"` → 订阅可用于 API；
返回 401/403 → Key 或权限问题；返回 400 MissingSessionID → header 没带对。
若返回地区拦截错误 → 该 workspace 的 Settings › Privacy › Regions 需要设为 Global。
