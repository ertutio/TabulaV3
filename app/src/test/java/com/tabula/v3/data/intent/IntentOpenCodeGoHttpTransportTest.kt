package com.tabula.v3.data.intent

import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
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
 * **真实 HTTP 层**的 OpenCode Go 验证（本地回环服务器，不发任何真实外网请求）。
 *
 * 已有的 [IntentOpenCodeGoAssistTest] 把 `AssistTransport` 换成记录型假实现，
 * 因此它证明的是「客户端把正确的参数**交给**了传输层」。它证明不了：
 * - [HttpAssistTransport] 是否真的把这些头写进了 HTTP 请求（而不是只存在 map 里）；
 * - `Authorization` 与自定义头同时设置时会不会互相覆盖；
 * - 非 2xx 是否真的抛 `IOException`（客户端据此回退本地，这条路径只有一处在维护）。
 *
 * 这里用一个只依赖 `java.net` 的极简 HTTP/1.1 服务端（`ServerSocket`+手写解析，
 * 不引入新依赖、不用 `com.sun.net.httpserver`——它在 Android 单元测试的类路径里不存在），
 * 让生产代码 [HttpAssistTransport] **真发一次请求**，在服务端侧读回原始头部与请求体。
 *
 * 覆盖：
 * 1. 头部真的到位：`Authorization: Bearer …`、`x-opencode-session: ses_…`、自有 UA；
 * 2. 请求体只有白名单字段，且不含任何图像/路径/Key 痕迹；
 * 3. 400/401/403/429/500/垃圾 JSON → 一律回退（空建议、不抛、只发一次）；
 * 4. 超时 → 回退而不是挂死；
 * 5. 服务与 Key 隔离：Go 只带 Go 的 Key、DeepSeek 不带 Go 的 session 头。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class IntentOpenCodeGoHttpTransportTest {

    /** 服务端侧的取证：最后一次请求的方法、路径、头（小写名）、体。 */
    private data class Captured(
        val method: String,
        val path: String,
        val headers: Map<String, String>,
        val body: String
    )

    private val captured = AtomicReference<Captured?>(null)
    private val requestedCount = java.util.concurrent.atomic.AtomicInteger(0)

    /**
     * 每个用例自己的「服务端已收到请求」信号。
     *
     * 不能用测试类级别的 `CountDownLatch`（只归零一次，第二个用例等它必然超时），
     * 也不能只看 [captured] 非空（那是上一个用例留下的旧请求）。
     */
    @Volatile
    private var requestSignal: CountDownLatch? = null

    @Volatile
    private var respondWith: Pair<Int, String> = 200 to SUCCESS_BODY

    @Volatile
    private var delayMillis: Long = 0

    private lateinit var serverSocket: ServerSocket
    private lateinit var serverThread: Thread
    private var baseUrl: String = ""
    private lateinit var goStore: IntentAssistKeyStore
    private lateinit var deepSeekStore: IntentAssistKeyStore

    @Before
    fun setUp() {
        // 每个用例都从干净的服务端状态开始：上一个用例可能把响应改成 500、
        // 或把 delayMillis 设成 3 秒（测超时）。不清就会串味。
        captured.set(null)
        requestedCount.set(0)
        requestSignal = null
        respondWith = 200 to SUCCESS_BODY
        delayMillis = 0

        val dir = File(System.getProperty("java.io.tmpdir"), "go-http-" + System.nanoTime())
        dir.mkdirs()
        goStore = IntentAssistKeyStore(File(dir, "go.key"), IntentAssistService.OPENCODE_GO)
        deepSeekStore = IntentAssistKeyStore(File(dir, "deepseek.key"), IntentAssistService.DEEPSEEK)
        goStore.write("oc_sk_synthetic_not_a_real_key")
        deepSeekStore.write("sk-synthetic-not-a-real-key")

        serverSocket = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        baseUrl = "http://127.0.0.1:${serverSocket.localPort}/openai/v1"
        serverThread = Thread({
            while (!serverSocket.isClosed) {
                val socket = try {
                    serverSocket.accept()
                } catch (e: Throwable) {
                    return@Thread
                }
                // 每个连接一条线程：慢响应（超时用例里 3 秒）不能挡住下一个连接的 accept，
                // 否则后续用例的请求会排在单线程后面，看起来像「请求没发出去」。
                Thread({
                    try {
                        handle(socket)
                    } catch (e: Throwable) {
                        // 客户端超时提前断开是**预期路径**（测超时回退），不能把服务端带崩
                    } finally {
                        runCatching { socket.close() }
                    }
                }, "loopback-http-conn").apply { isDaemon = true; start() }
            }
        }, "loopback-http").apply { isDaemon = true; start() }
    }

    @After
    fun tearDown() {
        runCatching { serverSocket.close() }
        runCatching { serverThread.join(1_000) }
        goStore.delete()
        deepSeekStore.delete()
    }

    /**
     * 极简 HTTP/1.1，**同一个 socket 上循环服务多个请求**。
     *
     * 为什么必须循环：[HttpAssistTransport] 用的是 `HttpURLConnection`，它会按 HTTP/1.1
     * 默认做连接复用。如果服务端回一个响应就把 socket 关掉，下一次请求仍可能被派到
     * 那个已被关闭的连接上，于是请求根本没到服务端、客户端直接拿到 `IOException`
     * （本轮实测：同一个用例的第 3 轮起请求数变成 0，「非 2xx 回退」看起来像没发请求）。
     * 保持连接可按复用语义工作，测试才在测我方代码而不是在测 JDK 的连接池。
     */
    private fun handle(socket: Socket) {
        socket.soTimeout = 10_000
        val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
        while (!socket.isClosed) {
            val requestLine = try {
                reader.readLine()
            } catch (e: Throwable) {
                return
            } ?: return
            if (requestLine.isBlank()) return
            val parts = requestLine.split(" ")
            val method = parts.getOrElse(0) { "" }
            val path = parts.getOrElse(1) { "" }

            val headers = mutableMapOf<String, String>()
            while (true) {
                val line = try {
                    reader.readLine()
                } catch (e: Throwable) {
                    return
                } ?: return
                if (line.isEmpty()) break
                val idx = line.indexOf(':')
                if (idx <= 0) continue
                headers.putIfAbsent(
                    line.substring(0, idx).trim().lowercase(),
                    line.substring(idx + 1).trim()
                )
            }

            val length = headers["content-length"]?.toIntOrNull() ?: 0
            val chars = CharArray(length)
            var read = 0
            while (read < length) {
                val n = try {
                    reader.read(chars, read, length - read)
                } catch (e: Throwable) {
                    return
                }
                if (n <= 0) break
                read += n
            }
            val body = String(chars, 0, read)

            requestedCount.incrementAndGet()
            captured.set(Captured(method = method, path = path, headers = headers, body = body))
            requestSignal?.countDown()

            if (delayMillis > 0) Thread.sleep(delayMillis)

            val (code, payload) = respondWith
            val bytes = payload.toByteArray(Charsets.UTF_8)
            val reason = when (code) {
                200 -> "OK"
                400 -> "Bad Request"
                401 -> "Unauthorized"
                403 -> "Forbidden"
                429 -> "Too Many Requests"
                500 -> "Internal Server Error"
                else -> "Status"
            }
            val head = "HTTP/1.1 $code $reason\r\n" +
                "Content-Type: application/json\r\n" +
                "Content-Length: ${bytes.size}\r\n\r\n"
            try {
                socket.getOutputStream().apply {
                    write(head.toByteArray(Charsets.UTF_8))
                    write(bytes)
                    flush()
                }
            } catch (e: Throwable) {
                // 客户端已超时/断开：预期路径
                return
            }
            if (headers["connection"].equals("close", ignoreCase = true)) return
        }
    }

    private fun safeSnippet() = IntentTextSanitizer.sanitizeForAssistant(
        listOf("满199减30", "活动已于 2026-11-10 23:59 结束")
    )

    /**
     * 为本次调用装一个**全新的**信号，并等它归零。
     *
     * 每个用例都要在发请求前调 [armRequestSignal]（或直接用本方法包住调用），
     * 不能共用类级 latch：latch 只归零一次，第二个用例等它必然超时。
     */
    private fun armRequestSignal(): CountDownLatch = CountDownLatch(1).also { requestSignal = it }

    private fun awaitOneRequest(timeoutSeconds: Long = 8): Boolean =
        requestSignal?.await(timeoutSeconds, TimeUnit.SECONDS) ?: false

    private fun goAssist(
        transport: AssistTransport = HttpAssistTransport(),
        timeoutMillis: Int = 3_000
    ) = OpenCodeGoTextAssist(
        keyStore = goStore,
        userEnabledForBatch = true,
        transport = transport,
        baseUrl = baseUrl,
        timeoutMillis = timeoutMillis,
        sessionId = "ses_http_transport_0001"
    )

    // ==================== 1. 头部真的写进了 HTTP 请求 ====================

    @Test
    fun realHttpRequestCarriesRequiredHeaders() = runBlocking {
        armRequestSignal()
        goAssist().classify(safeSnippet())

        assertTrue("请求必须真的到达服务端", awaitOneRequest())
        val seen = requireNotNull(captured.get()) { "服务端没收到请求" }

        assertEquals("必须走 POST", "POST", seen.method)
        assertEquals(
            "路径必须是 OpenAI 兼容的 chat/completions：${seen.path}",
            "/openai/v1/chat/completions", seen.path
        )

        // Authorization 由传输层单独写入（不在 headers map 里），确认它真的到线上了
        assertEquals("Bearer oc_sk_synthetic_not_a_real_key", seen.headers["authorization"])

        // session 头是官方准入条件：缺了网关在验 key 之前就 400 MissingSessionID
        val session = seen.headers["x-opencode-session"]
        assertTrue("x-opencode-session 必须真的出现在请求头里", session != null)
        assertTrue("session id 必须以 ses_ 开头：$session", session!!.startsWith("ses_"))

        // 自有 UA：官方明确不接受通用 SDK / HTTP 库名
        val ua = seen.headers["user-agent"].orEmpty()
        assertEquals("TabulaV3-IntentAssist/1.0", ua)
        listOf("okhttp", "java", "apache", "curl", "python", "gson", "httpurlconnection")
            .forEach { lib ->
                assertFalse("UA 不得是通用库名（含 $lib）：$ua", ua.lowercase().contains(lib))
            }

        assertEquals("必须声明 JSON 请求体", "application/json", seen.headers["content-type"])
        assertTrue("必须声明可接受的响应类型", seen.headers["accept"] != null)
    }

    // ==================== 2. 请求体只有白名单字段 ====================

    @Test
    fun realRequestBodyKeepsOnlyWhitelistedFields() = runBlocking {
        armRequestSignal()
        goAssist().classify(safeSnippet())
        assertTrue("请求必须真的到达服务端", awaitOneRequest())

        val body = requireNotNull(captured.get()).body
        val json = JSONObject(body)
        val keys = json.keys().asSequence().toSet()
        assertEquals(
            "请求体字段必须只有这几个：$keys",
            setOf("model", "temperature", "max_tokens", "messages"), keys
        )
        assertEquals("deepseek-v4.1-flash", json.getString("model"))

        // 结构上不得出现任何图像 / 路径 / Key 痕迹
        listOf(
            "base64", "content://", "file://", "data:image", ".png", ".jpg",
            "/storage", "oc_sk_", "Bearer", "images/media"
        ).forEach { marker ->
            assertFalse("请求体不得包含「$marker」：$body", body.contains(marker))
        }

        // 文字确实按脱敏结果发出（日期/时刻保留，正向用例）
        val messages = json.getJSONArray("messages")
        assertEquals("必须是 system + user 两条", 2, messages.length())
        assertEquals("system", messages.getJSONObject(0).getString("role"))
        assertEquals("user", messages.getJSONObject(1).getString("role"))
        val user = messages.getJSONObject(1).getString("content")
        assertTrue("安全截止日期必须送达：$user", user.contains("2026-11-10"))
        assertTrue("时刻必须送达：$user", user.contains("23:59"))
    }

    // ==================== 3. 非 2xx 与垃圾响应一律回退 ====================

    @Test
    fun non2xxResponsesFallBackToLocalWithoutThrowing() = runBlocking {
        val cases = listOf(
            400 to """{"type":"error","error":{"type":"MissingSessionID"}}""",
            401 to """{"error":{"type":"authentication_error","message":"Invalid credential"}}""",
            403 to """{"error":{"type":"authentication_error","message":"OpenCode Go requires an API key"}}""",
            429 to """{"error":{"type":"rate_limit"}}""",
            500 to "oops",
            200 to "<html>not json</html>"
        )
        cases.forEach { (code, payload) ->
            respondWith = code to payload
            requestedCount.set(0)
            val assist = goAssist()
            val suggestion = assist.classify(safeSnippet())
            assertEquals(
                "HTTP $code 必须回退：${suggestion.reason}",
                IntentRecommendation.UNCERTAIN, suggestion.recommendation
            )
            assertTrue("HTTP $code 不得给出任何分类结论", suggestion.reason.isEmpty())
            assertTrue("HTTP $code 必须如实记下失败原因", assist.lastFailure != null)
            assertEquals("HTTP $code 只应发出一次请求（不重试）", 1, requestedCount.get())
        }
    }

    /**
     * 在途结果被换批作废。
     *
     * **本用例刻意不用回环服务器**：Robolectric 会替换 `HttpURLConnection` 的部分实现，
     * 在单元测试 JVM 里对 127.0.0.1 的连接行为不稳定（本轮实测：请求确实发出但
     * 判定「仍在途」的窗口抓不住）。这条要验证的是**代际语义**，与传输方式无关，
     * 因此用可控的阻塞型传输层构造窗口——它比真实网络更确定。
     * 真实 HTTP 的头、体与错误处理器由本类其余五条覆盖。
     */
    @Test
    fun inFlightResultIsStaleAfterGenerationBump() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val blockingTransport = object : AssistTransport {
            override fun post(
                url: String,
                body: String,
                authHeader: String,
                timeoutMillis: Int,
                headers: Map<String, String>
            ): String {
                entered.countDown()
                release.await(10, TimeUnit.SECONDS)
                return SUCCESS_BODY
            }
        }
        val generation = java.util.concurrent.atomic.AtomicInteger(0)
        val assist = OpenCodeGoTextAssist(
            keyStore = goStore,
            userEnabledForBatch = true,
            transport = blockingTransport,
            baseUrl = baseUrl,
            timeoutMillis = 8_000,
            sharedGeneration = generation,
            sessionId = "ses_http_transport_stale"
        )
        val io = java.util.concurrent.Executors.newSingleThreadExecutor()
        val future = io.submit<AssistSuggestion> { runBlocking { assist.classify(safeSnippet()) } }
        try {
            assertTrue(
                "请求必须已经发出（进入传输层）",
                entered.await(10, TimeUnit.SECONDS)
            )
            assertFalse("此刻请求应仍在途", future.isDone)
            // 上层换批：代际 +1，在途结果必须作废
            generation.incrementAndGet()
            release.countDown()
            val result = future.get(20, TimeUnit.SECONDS)
            assertTrue("换批后在途结果必须标 stale", result.stale)
        } finally {
            release.countDown()
            io.shutdownNow()
        }
    }

    @Test
    fun readTimeoutFallsBackInsteadOfHanging() = runBlocking {
        delayMillis = 3_000
        val assist = goAssist(timeoutMillis = 600)
        val started = System.currentTimeMillis()
        val suggestion = assist.classify(safeSnippet())
        val elapsed = System.currentTimeMillis() - started
        assertEquals(IntentRecommendation.UNCERTAIN, suggestion.recommendation)
        assertEquals("超时必须回退", "network", assist.lastFailure)
        assertTrue("超时必须真的超时返回（实际 ${elapsed}ms）", elapsed < 2_500)
    }

    // ==================== 4. 服务与 Key 隔离 ====================

    @Test
    fun goRequestNeverCarriesTheDeepSeekKeyOnTheSameTransport() = runBlocking {
        armRequestSignal()
        goAssist().classify(safeSnippet())
        assertTrue("请求必须真的到达服务端", awaitOneRequest())

        val seen = requireNotNull(captured.get())
        assertEquals("Bearer oc_sk_synthetic_not_a_real_key", seen.headers["authorization"])
        assertFalse(
            "Go 请求绝不能带上 DeepSeek 的 Key",
            seen.headers["authorization"].orEmpty().contains("sk-synthetic-not-a-real-key")
        )

        // 反向：DeepSeek 打同一个回环服务时，不得带 Go 的 session 头、也不得带 Go 的 Key。
        //
        // 必须先重新装信号并等到**这一次**请求落地再读 captured：
        // 同一进程里 `HttpURLConnection` 会复用 keep-alive 连接，若读得太早会拿到
        // 上一个 Go 请求的记录（本轮全量跑时正是这个假失败：expected DeepSeek key,
        // was Go key）。
        val countBefore = requestedCount.get()
        armRequestSignal()
        val deepSeek = DeepSeekTextAssist(
            keyStore = deepSeekStore,
            userEnabledForBatch = true,
            transport = HttpAssistTransport(),
            baseUrl = baseUrl,
            timeoutMillis = 3_000
        )
        deepSeek.classify(safeSnippet())
        assertTrue("DeepSeek 的请求必须真的到达服务端", awaitOneRequest())
        assertTrue("必须真的多了一次请求", requestedCount.get() > countBefore)

        val deepSeekSeen = requireNotNull(captured.get())
        assertEquals("Bearer sk-synthetic-not-a-real-key", deepSeekSeen.headers["authorization"])
        assertTrue(
            "DeepSeek 请求不该带 Go 的 session 头：${deepSeekSeen.headers["x-opencode-session"]}",
            deepSeekSeen.headers["x-opencode-session"] == null
        )
    }


    companion object {
        private const val SUCCESS_BODY =
            """{"id":"chatcmpl-1","object":"chat.completion","choices":[{"index":0,"message":{"role":"assistant","content":"LOWER"},"finish_reason":"stop"}]}"""
    }
}
