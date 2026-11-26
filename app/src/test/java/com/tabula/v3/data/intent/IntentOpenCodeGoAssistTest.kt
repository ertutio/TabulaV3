package com.tabula.v3.data.intent

import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * **OpenCode Go** 作为第二个文字辅助服务的验收（#95 之后的第二次接入）。
 *
 * 覆盖八组，全部打在**生产代码真正发出的请求**上（记录型传输层捕获 URL/头/体）：
 *
 * 1. 零请求：无 Key / 开关关闭 → 0 次；
 * 2. 请求装配：URL、`Authorization: Bearer`、`x-opencode-session: ses_…`、
 *    自有 `User-Agent`、官方模型 id（这三条任一条错，官方网关都会拒绝或降级）；
 * 3. 请求体不含图像/路径，字段只有 model/temperature/max_tokens/messages；
 * 4. 脱敏复用：含敏感字段的行整行不发、独立的截止日期必须发；
 * 5. 失败回退：断网 / 超时 / 401 / 403 / 400 MissingSessionID / 垃圾响应 → 空建议、不抛；
 * 6. 代际：在途请求被 invalidate 或上层 generation+1 后标 stale；
 * 7. 两个服务的 Key 互相独立；
 * 8. 切换服务撤销本批授权（granted == false、epoch 递增）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class IntentOpenCodeGoAssistTest {

    /** 一次请求的完整记录：URL、鉴权头、额外头、请求体、超时。 */
    private data class Recorded(
        val url: String,
        val authHeader: String,
        val headers: Map<String, String>,
        val body: String,
        val timeoutMillis: Int
    )

    /**
     * 记录型传输层。
     *
     * 不 sleep 在 post 里做阻塞是刻意的：延迟用 [gate] 控制（进入请求后等一个 latch），
     * 这样「请求已发出但还没返回」这个窗口是确定的，代际测试不会偶发。
     */
    private class RecordingTransport(
        private val respond: (String) -> String = { SUCCESS_RESPONSE }
    ) : AssistTransport {
        val calls = mutableListOf<Recorded>()

        /** 由测试置位：请求进入后先等它，用来构造「在途」窗口。 */
        @Volatile
        var gate: java.util.concurrent.CountDownLatch? = null

        /** 置位后，进入请求先把这个 latch 减一，测试据此知道「请求已经发出去了」。 */
        @Volatile
        var entered: java.util.concurrent.CountDownLatch? = null

        override fun post(
            url: String,
            body: String,
            authHeader: String,
            timeoutMillis: Int,
            headers: Map<String, String>
        ): String {
            calls += Recorded(url, authHeader, headers.toMap(), body, timeoutMillis)
            entered?.countDown()
            gate?.await(5, java.util.concurrent.TimeUnit.SECONDS)
            return respond(body)
        }
    }

    private lateinit var deepSeekFile: File
    private lateinit var goFile: File
    private lateinit var deepSeekStore: IntentAssistKeyStore
    private lateinit var goStore: IntentAssistKeyStore

    @Before
    fun setUp() {
        deepSeekFile = File.createTempFile("assist-deepseek", ".tmp").apply { deleteOnExit() }
        goFile = File.createTempFile("assist-go", ".tmp").apply { deleteOnExit() }
        deepSeekStore = IntentAssistKeyStore(deepSeekFile, IntentAssistService.DEEPSEEK)
        goStore = IntentAssistKeyStore(goFile, IntentAssistService.OPENCODE_GO)
        deepSeekStore.delete()
        goStore.delete()
    }

    private fun assist(
        enabled: Boolean = true,
        transport: AssistTransport,
        keyStore: IntentAssistKeyStore = goStore,
        model: String = OpenCodeGoTextAssist.DEFAULT_MODEL,
        sessionId: String = "ses_test_session_0001",
        generation: AtomicInteger = AtomicInteger(0)
    ) = OpenCodeGoTextAssist(
        keyStore = keyStore,
        userEnabledForBatch = enabled,
        transport = transport,
        model = model,
        sessionId = sessionId,
        sharedGeneration = generation
    )

    /**
     * 等到「请求已经真的发给传输层」。
     *
     * 为什么不用 `latch.await(...)`：`runBlocking` 里 `async` 与测试体共享同一条事件循环线程，
     * 阻塞式 await 会把那条线程占住，async 里的 classify 根本进不到 `transport.post`——
     * 于是测试自己把前提条件饿死了（第一版就是这么挂的）。
     * 这里改成**挂起式轮询**：`delay` 让出线程，async 才推得动。
     */
    private suspend fun awaitEntered(
        transport: RecordingTransport,
        timeoutMillis: Long = 5_000
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (transport.entered?.count == 0L) return true
            delay(10)
        }
        return false
    }

    private fun safeSnippet() = IntentTextSanitizer.sanitizeForAssistant(
        listOf("满199减30", "活动已于 2026-11-10 23:59 结束")
    )

    // ==================== 1. 零请求 ====================

    @Test
    fun noKeyMeansZeroRequests() = runBlocking {
        val transport = RecordingTransport()
        val assist = assist(enabled = true, transport = transport)
        assertFalse("没有 Key 时 isEnabled 必须为 false", assist.isEnabled)
        val suggestion = assist.classify(safeSnippet())
        assertEquals("没有 Key 时不得发请求", 0, transport.calls.size)
        assertEquals(IntentRecommendation.UNCERTAIN, suggestion.recommendation)
        assertEquals(1, assist.skippedRequestCount)
    }

    @Test
    fun disabledMeansZeroRequests() = runBlocking {
        goStore.write("oc_sk_synthetic_not_a_real_key")
        val transport = RecordingTransport()
        val assist = assist(enabled = false, transport = transport)
        assertFalse(assist.isEnabled)
        assist.classify(safeSnippet())
        assertEquals("开关关闭时不得发请求", 0, transport.calls.size)
    }

    @Test
    fun emptySnippetMeansZeroRequests() = runBlocking {
        goStore.write("oc_sk_synthetic_not_a_real_key")
        val transport = RecordingTransport()
        val assist = assist(enabled = true, transport = transport)
        // 全部行都被脱敏器扣留时，片段为空 → 不发
        val empty = IntentTextSanitizer.sanitizeForAssistant(listOf("验证码 20261110"))
        assertTrue("前提：该片段必须为空", empty.isEmpty)
        assist.classify(empty)
        assertEquals(0, transport.calls.size)
    }

    // ==================== 2. 请求装配（头 + URL + 模型） ====================

    @Test
    fun requestGoesToGoEndpointWithRequiredHeaders() = runBlocking {
        goStore.write("oc_sk_synthetic_not_a_real_key")
        val transport = RecordingTransport()
        assist(enabled = true, transport = transport).classify(safeSnippet())

        val call = transport.calls.single()
        assertTrue(
            "URL 必须打到 Go 套餐路径：${call.url}",
            call.url.endsWith("/inference/go/openai/v1/chat/completions")
        )
        assertEquals(
            "Go 与 pay-as-you-go 不是同一个 base，少了 /go 就是另一个池子",
            "https://opencode.ai/inference/go/openai/v1/chat/completions",
            call.url
        )

        // 鉴权：Bearer + oc_sk_…
        assertEquals("Bearer oc_sk_synthetic_not_a_real_key", call.authHeader)
        assertTrue("必须是 Bearer 方案", call.authHeader.startsWith("Bearer "))

        // session 头：官方准入条件（缺失 → 400 MissingSessionID，且先于验 key）
        val session = call.headers["x-opencode-session"]
        assertNotNull("必须带 x-opencode-session，否则网关 400 MissingSessionID", session)
        assertTrue("session id 必须以 ses_ 开头：$session", session!!.startsWith("ses_"))

        // User-Agent：自有标识，不能是通用 SDK / HTTP 库名
        val userAgent = call.headers["User-Agent"]
        assertNotNull("必须显式设置 User-Agent", userAgent)
        assertEquals("TabulaV3-IntentAssist/1.0", userAgent)
        listOf("okhttp", "java", "apache", "curl", "python", "httpurlconnection", "gson")
            .forEach { lib ->
                assertFalse(
                    "User-Agent 不得是通用库名（含 $lib）：$userAgent",
                    userAgent!!.lowercase().contains(lib)
                )
            }

        // 模型：只允许官方两个 id
        val model = JSONObject(call.body).getString("model")
        assertTrue("模型必须是官方 id 之一：$model", model in OpenCodeGoTextAssist.OFFICIAL_MODELS)
        assertEquals("deepseek-v4.1-flash", model)
        assertFalse("Go 上没有 deepseek-chat", model == "deepseek-chat")
    }

    @Test
    fun modelIdIsValidatedAgainstOfficialList() {
        val transport = RecordingTransport()
        // 官方两个 id 都接受
        assertEquals("deepseek-v4-pro", assist(transport = transport, model = "deepseek-v4-pro").model)
        assertEquals("deepseek-v4-pro", assist(transport = transport, model = "  DEEPSEEK-V4-PRO  ").model)
        assertEquals("deepseek-v4.1-flash", assist(transport = transport).model)

        // 非官方 id（含 deepseek-chat）→ 收敛到默认，且留下可诊断的原始值
        listOf("deepseek-chat", "deepseek-v3", "gpt-4o", "", "   ").forEach { bad ->
            val instance = assist(transport = transport, model = bad)
            assertEquals("非法模型必须回退默认：$bad", "deepseek-v4.1-flash", instance.model)
        }
        assertEquals(
            "被收敛的原始值要留痕，便于诊断",
            "deepseek-chat",
            assist(transport = transport, model = "deepseek-chat").modelFallbackFrom
        )
    }

    @Test
    fun officialModelFromConstructorReachesThePayload() = runBlocking {
        goStore.write("oc_sk_synthetic_not_a_real_key")
        val transport = RecordingTransport()
        assist(transport = transport, model = "deepseek-v4-pro").classify(safeSnippet())
        assertEquals(
            "deepseek-v4-pro",
            JSONObject(transport.calls.single().body).getString("model")
        )
    }

    @Test
    fun sessionIdIsStablePerInstanceAndPrefixed() = runBlocking {
        goStore.write("oc_sk_synthetic_not_a_real_key")
        val transport = RecordingTransport()
        val instance = assist(transport = transport, sessionId = "ses_fixed_for_test")
        instance.classify(safeSnippet())
        instance.classify(safeSnippet())
        val sessions = transport.calls.map { it.headers["x-opencode-session"] }.toSet()
        assertEquals("同一实例内 session 必须稳定（利于官方 prompt cache）", 1, sessions.size)
        assertEquals("ses_fixed_for_test", sessions.single())

        // 不注入时：每实例一个 ses_ 开头的随机串，且不同实例不同
        val a = OpenCodeGoTextAssist(goStore, true, transport = transport)
        val b = OpenCodeGoTextAssist(goStore, true, transport = transport)
        assertTrue(a.sessionId.startsWith("ses_"))
        assertTrue(b.sessionId.startsWith("ses_"))
        assertTrue("换批随实例重建 = 换 session", a.sessionId != b.sessionId)

        // 没有 ses_ 前缀的注入值会被补齐，保证请求头永远是官方形式
        assertEquals(
            "ses_plain",
            OpenCodeGoTextAssist(goStore, true, transport = transport, sessionId = "plain").sessionId
        )
    }

    @Test
    fun defaultsPointAtGoEndpointAndFlashModel() {
        assertEquals(
            "https://opencode.ai/inference/go/openai/v1",
            OpenCodeGoTextAssist.DEFAULT_BASE_URL
        )
        assertEquals("deepseek-v4.1-flash", OpenCodeGoTextAssist.DEFAULT_MODEL)
        assertEquals(
            listOf("deepseek-v4.1-flash", "deepseek-v4-pro"),
            OpenCodeGoTextAssist.OFFICIAL_MODELS
        )
        assertEquals("x-opencode-session", OpenCodeGoTextAssist.HEADER_SESSION)
        assertTrue(OpenCodeGoTextAssist.USAGE_URL.endsWith("/inference/go/v1/usage"))
    }

    // ==================== 3. 请求体：无图像 / 无路径 / 字段白名单 ====================

    @Test
    fun payloadCarriesNoImageOrPathAndOnlyKnownFields() = runBlocking {
        goStore.write("oc_sk_synthetic_not_a_real_key")
        val transport = RecordingTransport()
        assist(transport = transport).classify(safeSnippet())
        val payload = transport.calls.single().body

        listOf(
            "base64", "data:image", "content://", "/storage/", "DCIM", "Screenshots",
            ".png", ".jpg", ".jpeg", ".webp", "image_url", "file://", "缩略图", "bitmap"
        ).forEach { forbidden ->
            assertFalse(
                "请求体不得包含 $forbidden：$payload",
                payload.contains(forbidden, ignoreCase = true)
            )
        }

        val parsed = JSONObject(payload)
        val allowed = setOf("model", "temperature", "max_tokens", "messages")
        parsed.keys().forEach { key ->
            assertTrue("请求体出现了非预期字段 $key", key in allowed)
        }
        assertEquals(0, parsed.getInt("temperature"))
        assertEquals(80, parsed.getInt("max_tokens"))
        val messages = parsed.getJSONArray("messages")
        assertEquals("system + user 两条，不多不少", 2, messages.length())
        assertEquals("system", messages.getJSONObject(0).getString("role"))
        assertEquals("user", messages.getJSONObject(1).getString("role"))
        assertEquals(
            "system prompt 与 DeepSeek 实现逐字相同",
            DeepSeekTextAssist.SYSTEM_PROMPT,
            messages.getJSONObject(0).getString("content")
        )
    }

    // ==================== 4. 脱敏复用 ====================

    @Test
    fun sensitiveLinesAreHeldBackButSafeDeadlineSurvives() = runBlocking {
        goStore.write("oc_sk_synthetic_not_a_real_key")

        // 语义以 #95 的最新规则为准：**只要任意一行命中敏感字段，整张截图就不进文字辅助**。
        // 因此这里必须分成两张「截图」验证，不能把敏感行与安全行放进同一次 sanitize：
        //   A) 含敏感字段的那张 → lines 为空、sendable=false、零请求；
        //   B) 只有安全截止日期的那张 → 请求体里必须保留日期与时刻。
        val secretSnippet = IntentTextSanitizer.sanitizeForAssistant(
            listOf(
                "验证码 4471，请在页面上输入",
                "取件码 注意 说明",
                "活动已于 2026-11-10 23:59 结束"
            )
        )
        assertTrue("含敏感字段的图必须标成含字段", secretSnippet.containsSecretField)
        assertFalse("含敏感字段的图不得可发送", secretSnippet.sendable)
        assertTrue("含敏感字段的图不得有外发文本", secretSnippet.lines.isEmpty())
        assertTrue(
            "被扣留的行要如实计数，供报告说明边界",
            secretSnippet.hadUndecidableLines && secretSnippet.droppedLineCount >= 2
        )
        val blockedTransport = RecordingTransport()
        assist(transport = blockedTransport).classify(secretSnippet)
        assertEquals("整张扣留时 Go 侧必须零请求", 0, blockedTransport.calls.size)

        // B) 干净的那张：不该因为同批有敏感图而被连坐
        val safeTransport = RecordingTransport()
        val safeSnippet = IntentTextSanitizer.sanitizeForAssistant(
            listOf("活动已于 2026-11-10 23:59 结束")
        )
        assertTrue("安全图必须可发送", safeSnippet.sendable)
        assist(transport = safeTransport).classify(safeSnippet)
        val payload = safeTransport.calls.single().body
        listOf("4471", "请在页面上输入", "取件码", "注意", "说明").forEach { secret ->
            assertFalse("敏感内容不得出现在请求体（$secret）：$payload", payload.contains(secret))
        }
        assertTrue("独立的截止日期必须保留：$payload", payload.contains("2026-11-10"))
        assertTrue("时刻必须保留：$payload", payload.contains("23:59"))
    }

    /**
     * **生产路径**的图级扣留：走 builder，而不是直接喂 snippet。
     *
     * 上面那条 `sanitizerIsTheSameInstanceOfCodeAsDeepSeek` 是直接调 `.classify(snippet)`，
     * 绕过了 `refineWithAssist` 的 `sendable` 判断，所以它只能证明「同一份脱敏」，
     * 证明不了「含字段的图不会走到这一层」。这条补上后者：
     * 首行已有部分值 + 次行续值时，Go 侧必须零请求。
     */
    @Test
    fun builderHoldsBackScreenshotsWithSecretFieldsOnGoPath() = runBlocking {
        val now = 1_796_000_000_000L
        fun photo(id: Long) = com.tabula.v3.data.model.ImageFile(
            id = id,
            uri = android.net.Uri.parse("content://media/external_primary/images/media/$id"),
            displayName = "s$id.png",
            dateModified = now,
            size = 1000L + id,
            width = 720, height = 1280,
            bucketDisplayName = "Screenshots",
            volumeName = "external_primary",
            relativePath = "DCIM/Screenshots/",
            dateAdded = now - 10L * 86_400_000L
        )
        // 这次要真的发出去：先给 Go 写一个 Key，否则 isEnabled=false、零请求，
        // 用例就会变成「什么都没验证」而误判通过/失败。
        goStore.write("oc_sk_synthetic_builder_path_0001")
        val transport = RecordingTransport()
        val goAssist = assist(transport = transport)
        assertTrue("前置：Go 实例必须已启用", goAssist.isEnabled)
        val seen = mutableListOf<List<String>>()
        val spy = object : TextAssist {
            override val isEnabled = true
            override suspend fun classify(
                snippet: IntentTextSanitizer.SanitizedSnippet
            ): AssistSuggestion {
                seen += snippet.lines
                return goAssist.classify(snippet)
            }
        }
        val candidates = listOf(
            // 首行部分值 + 次行续值
            IntentCandidateInput(photo(1), photo(1).dateAdded, listOf("验证码 4471", "8899", "请勿转发"), 0.9f),
            // 干净图：应发出
            IntentCandidateInput(photo(2), photo(2).dateAdded, listOf("某视频平台", "复制链接", "扫码观看"), 0.9f)
        )
        IntentBatchBuilder.refineWithAssist(IntentBatchBuilder.classifyAll(candidates, now), spy)

        assertEquals("只有干净那张会到辅助层", 1, seen.size)
        assertTrue(
            "送到辅助层的内容不得来自含字段的图：${seen.first()}",
            seen.first().none { it.contains("4471") || it.contains("8899") }
        )
        goStore.delete()
        assertEquals("Go 侧只应发出一次请求（干净那张）", 1, transport.calls.size)
        val body = transport.calls.single().body
        listOf("4471", "8899", "验证码").forEach { secret ->
            assertFalse("请求体不得包含 $secret：$body", body.contains(secret))
        }
    }

    @Test
    fun sanitizerIsTheSameInstanceOfCodeAsDeepSeek() = runBlocking {
        // 两个服务对同一段 OCR 行必须产生**逐字相同**的 user 消息：
        // 复用同一个 sanitizeForAssistant，不允许各写一份。
        //
        // 这一条必须用**不含敏感字段**的截图：按 #95 的图级规则，含字段的图
        // 整张不进辅助（lines 为空 → 零请求），那就没有请求体可比了。
        // 「含字段的图两侧都零请求」由 builderHoldsBackScreenshotsWithSecretFieldsOnGoPath
        // 与上一条用例负责，不在这里混着测。
        deepSeekStore.write("sk-synthetic-not-a-real-key")
        goStore.write("oc_sk_synthetic_not_a_real_key")
        val deepSeekTransport = RecordingTransport()
        val goTransport = RecordingTransport()
        val lines = listOf("满199减30", "活动已于 2026-11-10 23:59 结束", "仅为促销文案，无个人信息")

        val snippet = IntentTextSanitizer.sanitizeForAssistant(lines)
        assertTrue("前置：这段样本文本必须可发送（否则测不到请求体）：$snippet", snippet.sendable)

        DeepSeekTextAssist(deepSeekStore, true, deepSeekTransport).classify(snippet)
        assist(transport = goTransport).classify(snippet)

        fun userContent(body: String): String {
            val messages = JSONObject(body).getJSONArray("messages")
            return messages.getJSONObject(1).getString("content")
        }
        assertEquals(
            "两侧 user 消息必须逐字相同（同一份脱敏、同一份提示）",
            userContent(deepSeekTransport.calls.single().body),
            userContent(goTransport.calls.single().body)
        )
    }

    // ==================== 5. 失败回退 ====================

    @Test
    fun failuresFallBackToLocalWithoutThrowing() = runBlocking {
        goStore.write("oc_sk_synthetic_not_a_real_key")
        val cases: List<Pair<String, (String) -> String>> = listOf(
            // 断网 / 超时 / 鉴权失败 / 缺 session 头：传输层一律抛 IOException
            "offline" to { _: String -> throw IOException("offline") },
            "timeout" to { _: String -> throw java.net.SocketTimeoutException("timeout") },
            "401" to { _: String -> throw IOException("http 401") },
            "403" to { _: String -> throw IOException("http 403") },
            "400-missing-session" to { _: String ->
                throw IOException("http 400 MissingSessionID")
            },
            // 拿不到可解析内容：垃圾响应 / 空 choices / 没有 content 字段
            "garbage" to { _: String -> "not json at all" },
            "empty-choices" to { _: String -> """{"choices":[]}""" },
            "no-content" to { _: String -> """{"choices":[{"message":{"role":"assistant"}}]}""" },
            "error-envelope" to { _: String ->
                """{"type":"error","error":{"type":"MissingSessionID"}}"""
            }
        )

        cases.forEach { (label, responder) ->
            val suggestion = assist(transport = RecordingTransport(responder)).classify(safeSnippet())
            assertEquals(
                "「$label」必须回退为空建议",
                IntentRecommendation.UNCERTAIN,
                suggestion.recommendation
            )
            assertFalse("「$label」不是过期，只是失败", suggestion.stale)
        }
    }

    @Test
    fun successfulAnswersAreParsedLikeDeepSeek() = runBlocking {
        goStore.write("oc_sk_synthetic_not_a_real_key")
        // KEEP
        val keep = assist(transport = RecordingTransport { """{"choices":[{"message":{"content":"KEEP"}}]}""" })
            .classify(safeSnippet())
        assertEquals(IntentRecommendation.KEEP, keep.recommendation)
        assertEquals(IntentCategory.PROTECTED, keep.category)

        // LOWER
        val lower = assist(transport = RecordingTransport { """{"choices":[{"message":{"content":"LOWER"}}]}""" })
            .classify(safeSnippet())
        assertEquals(IntentRecommendation.LOWER_PRIORITY, lower.recommendation)

        // 模型越权（要求清理）只被解析成空建议
        val rogue = assist(
            transport = RecordingTransport { """{"choices":[{"message":{"content":"DELETE all of them"}}]}""" }
        ).classify(safeSnippet())
        assertEquals(IntentRecommendation.UNCERTAIN, rogue.recommendation)
        assertTrue("AssistSuggestion 没有 expiry 槽位，类型层面无法认定过期", rogue.category != IntentCategory.EXPIRED_COUPON)
    }

    @Test
    fun reasoningContentIsIgnored() = runBlocking {
        goStore.write("oc_sk_synthetic_not_a_real_key")
        // 官方把推理放在 reasoning_content；本项目不用它（也不该把它当答案）
        val transport = RecordingTransport {
            """{"choices":[{"message":{"reasoning_content":"KEEP","content":"LOWER"}}]}"""
        }
        val suggestion = assist(transport = transport).classify(safeSnippet())
        assertEquals(
            "只认 message.content，不被推理内容带偏",
            IntentRecommendation.LOWER_PRIORITY,
            suggestion.recommendation
        )
    }

    // ==================== 6. 代际 / 作废 ====================

    @Test
    fun inFlightResultIsStaleAfterInvalidate() = runBlocking {
        goStore.write("oc_sk_synthetic_not_a_real_key")
        val transport = RecordingTransport()
        transport.gate = java.util.concurrent.CountDownLatch(1)
        transport.entered = java.util.concurrent.CountDownLatch(1)
        val assist = assist(transport = transport)

        val request = async { assist.classify(safeSnippet()) }
        assertTrue("前提：请求必须已经真的发出去了", awaitEntered(transport))
        assist.invalidate()
        transport.gate!!.countDown()
        val suggestion = request.await()

        assertTrue("在途请求被 invalidate 后必须标 stale", suggestion.stale)
        assertEquals(IntentRecommendation.UNCERTAIN, suggestion.recommendation)
        assertFalse("关闭后 isEnabled 立即为 false", assist.isEnabled)
    }

    @Test
    fun generationBumpByUpperLayerMarksStale() = runBlocking {
        goStore.write("oc_sk_synthetic_not_a_real_key")
        val generation = AtomicInteger(0)
        val transport = RecordingTransport()
        transport.gate = java.util.concurrent.CountDownLatch(1)
        transport.entered = java.util.concurrent.CountDownLatch(1)
        val assist = assist(transport = transport, generation = generation)

        val request = async { assist.classify(safeSnippet()) }
        assertTrue("前提：请求必须已经真的发出去了", awaitEntered(transport))
        // 上层换批/换服务：只 +1，不碰 assist 自身
        generation.incrementAndGet()
        transport.gate!!.countDown()
        val suggestion = request.await()

        assertTrue("上层推进代际也必须让在途结果作废", suggestion.stale)
    }

    @Test
    fun rearmResumesRequests() = runBlocking {
        goStore.write("oc_sk_synthetic_not_a_real_key")
        val transport = RecordingTransport()
        val assist = assist(transport = transport)
        assist.classify(safeSnippet())
        assertEquals(1, transport.calls.size)

        assist.invalidate()
        assist.classify(safeSnippet())
        assertEquals("作废后不再发请求", 1, transport.calls.size)

        assist.rearm()
        assist.classify(safeSnippet())
        assertEquals("rearm 后恢复发送", 2, transport.calls.size)
    }

    @Test
    fun staleSuggestionIsIgnoredByBuilder() = runBlocking {
        // 端到端：Go 的在途结果迟到 → refineWithAssist 丢弃 → 判定仍是本地结果
        goStore.write("oc_sk_synthetic_not_a_real_key")
        val transport = RecordingTransport { """{"choices":[{"message":{"content":"LOWER"}}]}""" }
        transport.gate = java.util.concurrent.CountDownLatch(1)
        transport.entered = java.util.concurrent.CountDownLatch(1)
        val assist = assist(transport = transport)

        val classified = listOf(
            IntentBatchBuilder.Classified(
                input = IntentCandidateInput(
                    image = com.tabula.v3.data.model.ImageFile(
                        id = 1,
                        uri = android.net.Uri.parse("content://media/external_primary/images/media/1"),
                        displayName = "s1.png",
                        dateModified = 1_796_000_000_000L,
                        size = 1000L,
                        width = 720, height = 1280,
                        bucketDisplayName = "Screenshots",
                        volumeName = "external_primary",
                        relativePath = "DCIM/Screenshots/",
                        dateAdded = 1_796_000_000_000L
                    ),
                    savedAtMillis = 1_796_000_000_000L,
                    ocrLines = listOf("某平台活动", "扫码查看详情"),
                    ocrConfidence = 0.9f
                ),
                verdict = IntentVerdict(
                    category = IntentCategory.UNCERTAIN,
                    recommendation = IntentRecommendation.UNCERTAIN,
                    expiry = ExpiryState.UNKNOWN,
                    reason = ""
                )
            )
        )

        val refine = async { IntentBatchBuilder.refineWithAssist(classified, assist) }
        assertTrue("前提：请求必须已经真的发出去了", awaitEntered(transport))
        assist.invalidate()
        transport.gate!!.countDown()
        val refined = refine.await()

        assertEquals(
            "stale 建议必须被丢弃，判定保持本地的 UNCERTAIN",
            IntentRecommendation.UNCERTAIN,
            refined.single().verdict.recommendation
        )
        assertFalse(refined.single().verdict.assistantUsed)
    }

    // ==================== 7. 两个服务的 Key 独立 ====================

    @Test
    fun servicesKeepSeparateKeys() {
        // 写到 DeepSeek 不影响 Go
        deepSeekStore.write("sk-deepseek-only")
        assertTrue("DeepSeek 已有 Key", deepSeekStore.hasKey())
        assertFalse("写 DeepSeek 的 Key 不得让 Go 也有 Key", goStore.hasKey())

        // 写到 Go 不影响 DeepSeek
        goStore.write("oc_sk_go_only")
        assertTrue(goStore.hasKey())
        assertEquals("sk-deepseek-only", deepSeekStore.read())
        assertEquals("oc_sk_go_only", goStore.read())

        // 删除一个不碰另一个
        goStore.delete()
        assertFalse(goStore.hasKey())
        assertTrue("删 Go 的 Key 不得影响 DeepSeek", deepSeekStore.hasKey())
        deepSeekStore.delete()
        assertFalse(deepSeekStore.hasKey())
    }

    @Test
    fun eachServiceResolvesItsOwnKeyFile() {
        // 文件名约定：#95 的 intent-assist.key 保持不变（老用户不丢 Key），Go 用新文件
        assertEquals("intent-assist.key", IntentAssistService.DEEPSEEK.keyFileName)
        assertEquals("intent-assist.opencode-go.key", IntentAssistService.OPENCODE_GO.keyFileName)
        assertEquals(
            "DeepSeek 必须沿用 #95 的文件名",
            IntentAssistKeyStore.FILE_NAME,
            IntentAssistService.DEEPSEEK.keyFileName
        )
        assertEquals(
            IntentAssistKeyStore.OPENCODE_GO_FILE_NAME,
            IntentAssistService.OPENCODE_GO.keyFileName
        )

        // 默认服务仍是 DeepSeek（不改已有用户的现状）
        assertEquals(IntentAssistService.DEEPSEEK, IntentAssistService.DEFAULT)
        assertEquals(IntentAssistService.DEEPSEEK, IntentAssistService.fromStoredName(null))
        assertEquals(IntentAssistService.DEEPSEEK, IntentAssistService.fromStoredName("不存在"))
        assertEquals(IntentAssistService.OPENCODE_GO, IntentAssistService.fromStoredName("OPENCODE_GO"))
    }

    @Test
    fun goAssistNeverReadsTheDeepSeekKey() = runBlocking {
        // 只有 DeepSeek 的 Key 时，Go 实例必须零请求（不能拿另一家的 Key 去打 Go 端点）
        deepSeekStore.write("sk-deepseek-only")
        val transport = RecordingTransport()
        val assist = assist(transport = transport, keyStore = goStore)
        assertFalse(assist.isEnabled)
        assist.classify(safeSnippet())
        assertEquals("不得用 DeepSeek 的 Key 请求 OpenCode Go", 0, transport.calls.size)
    }

    // ==================== 8. 切换服务撤销授权 ====================

    @Test
    fun serviceSwitchRevokesBatchAuthorization() {
        val auth = IntentAssistAuthorization()
        auth.acknowledgeNotice()
        auth.onUserToggle(true)
        assertTrue("前提：用户已在当前批开启", auth.granted)
        assertEquals(0, auth.epoch)

        val changed = IntentAssistServiceSwitch.revokeForServiceChange(
            current = IntentAssistService.DEEPSEEK,
            next = IntentAssistService.OPENCODE_GO,
            onRebuild = { auth.onRebuild() }
        )

        assertTrue("换服务必须被判为「变了」", changed)
        assertFalse("换服务后本批授权必须回收", auth.granted)
        assertFalse("外发说明也要重新确认", auth.noticeAcknowledged)
        assertTrue("epoch 必须递增（旧代际的在途结果作废）", auth.epoch > 0)
        assertEquals(IntentAssistAuthorization.Transition.REVOKED_ON_REBUILD, auth.lastTransition)
        assertFalse(auth.effective)
    }

    @Test
    fun selectingTheSameServiceKeepsAuthorization() {
        val auth = IntentAssistAuthorization()
        auth.onUserToggle(true)
        val epochBefore = auth.epoch

        val changed = IntentAssistServiceSwitch.revokeForServiceChange(
            current = IntentAssistService.OPENCODE_GO,
            next = IntentAssistService.OPENCODE_GO,
            onRebuild = { auth.onRebuild() }
        )

        assertFalse("点同一家不算换服务", changed)
        assertTrue("授权必须保留（用户没改任何东西）", auth.granted)
        assertEquals("代际不得推进，否则他自己的在途请求会被误判过期", epochBefore, auth.epoch)
    }

    @Test
    fun switchingBackAndForthRevokesEachTime() {
        val auth = IntentAssistAuthorization()
        var current = IntentAssistService.DEEPSEEK

        repeat(3) { round ->
            auth.onUserToggle(true)
            val next = if (current == IntentAssistService.DEEPSEEK) {
                IntentAssistService.OPENCODE_GO
            } else {
                IntentAssistService.DEEPSEEK
            }
            val changed = IntentAssistServiceSwitch.revokeForServiceChange(current, next) {
                auth.onRebuild()
            }
            current = next
            assertTrue("第 ${round + 1} 次切换必须生效", changed)
            assertFalse("第 ${round + 1} 次切换后授权必须回收", auth.granted)
            assertEquals("epoch 每次切换 +1", round + 1, auth.epoch)
        }
    }

    // ==================== 附：缓存键随服务变化 ====================

    @Test
    fun assistCacheTagIncludesService() {
        // 换服务后「证据不足」的那些图必须重判：缓存键里的服务前缀不同
        assertEquals("deepseek", IntentAssistService.DEEPSEEK.cacheTag)
        assertEquals("opencode-go", IntentAssistService.OPENCODE_GO.cacheTag)
        assertTrue(IntentAssistService.DEEPSEEK.cacheTag != IntentAssistService.OPENCODE_GO.cacheTag)
    }

    // ==================== 附：超时配置真的传到了传输层 ====================

    @Test
    fun timeoutIsHandedToTheTransport() = runBlocking {
        goStore.write("oc_sk_synthetic_not_a_real_key")
        val transport = RecordingTransport()
        OpenCodeGoTextAssist(
            keyStore = goStore,
            userEnabledForBatch = true,
            transport = transport,
            timeoutMillis = 3_000,
            sessionId = "ses_timeout_test"
        ).classify(safeSnippet())
        assertEquals(
            "超时必须交给传输层（断网要能回退，不能挂住整批分析）",
            3_000,
            transport.calls.single().timeoutMillis
        )
    }

    companion object {
        private const val SUCCESS_RESPONSE = """{"choices":[{"message":{"content":"LOWER"}}]}"""
    }
}
