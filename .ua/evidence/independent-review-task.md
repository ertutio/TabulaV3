# 目标
独立只读复核 TabulaV3 的三处新增静态结论。区分可确认事实、条件路径和过度表述，最多返回 5 条有依据的结论，全报告不超过 3000 Unicode 字符。

# 必要上下文
工作目录：.
公开仓库 ertutio/TabulaV3，固定 master commit d2038b8e0dc8c99fbdf2c754e88dec0a8078a725。这是完整公开源码归档，无 .git。主代理只生成 .ua 分析产物，业务源文件没有修改。
已读 AGENTS 指令由用户在主聊天提供：独立审查为未参与实现的只读会话，结论需主代理核对位置、事实及范围；只交接必要且已授权的公开源码，不交接密钥。源码归档没有适用 AGENTS.md。主代理负责验证与最终验收。

# 调用类型和权限
profile=review；mode=read；model=deepseek-flash[1m]；effort=max。
仅 Read。无可写文件，不执行 shell、构建、测试、网络、MCP 或再次委派。
只读取以下源码文件及指定行段，不读取 .env、配置凭据、.codex 或 worker 状态目录。

# 文件与复核问题
1. app/src/main/java/com/tabula/v3/data/repository/SystemAlbumSyncManager.kt
   读取 303-446、599-732、1001-1133。
   F18：moveImageToBucket 在路径更新返回 0 时，copyImageToPath 可在 null 输入/输出流、短复制或发布更新失败时返回非空 URI，随后调用 deleteOriginalImage。因此移动回退也可能尝试删除唯一完整源，而不仅原图清理入口有此风险。另一 moveImageUsingMediaStore 使用同一未验证复制/名称大小去重路径。请确认触发条件和影响；不得声称已设备复现。
2. app/src/main/java/com/tabula/v3/data/repository/AlbumManager.kt
   读取 1052-1158。
   app/src/main/java/com/tabula/v3/MainActivity.kt
   读取 659-716。
   app/src/main/java/com/tabula/v3/ui/screens/DeckScreen.kt
   读取 1265-1345。
   F19：Deck 排队时即显示已添加并移除卡片；Main 忽略 commitPendingArchive Boolean；commit 先用独立 find/remove 领取 action，再复制。并发提交/撤销可能交叠，失败 action 已移出队列。请分别判断结果反馈缺口和“并发副作用”是否有代码支持，避免把可能性交叠写成必然重复复制。
3. app/src/main/java/com/tabula/v3/data/cache/ImageHashCache.kt
   读取 18-139、170-202。
   app/src/main/java/com/tabula/v3/data/repository/SimilarGroupDetector.kt
   读取 221-292。
   F20：持久哈希和失败状态只按 imageId 记录，ensureHashesComputed 对任何已有缓存都不重算。同 ID 内容编辑或临时不可读恢复后可能继续使用旧哈希/失败。请确认是否存在未纳入上下文的版本失效或重试，若无法确认明确限定范围。

# 交付
逐项给出：接受/修正/不支持，严重性、文件及行号、触发条件、影响和最小处理建议。末尾列实际读取范围与未确认点。没有执行 Android/真机测试，不能将静态路径写成动态复现。仅基于源码回答，不读取其他文档来接受主代理结论。
