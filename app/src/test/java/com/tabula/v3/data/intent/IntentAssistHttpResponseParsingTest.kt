package com.tabula.v3.data.intent

import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * **真实 HTTP 响应**里的解析回归（本轮修复的端到端证明）。
 *
 * [IntentAssistResponseParsingTest] 用固定响应的传输层证明「解析器能读到带空白的 JSON」，
 * 但它绕过了 `HttpAssistTransport`：证明不了「从网络读回来的字节交给解析器之前的
 * 那一段路（OutputStreamWriter 写、BufferedReader 读、HTTP 头解析）没有再把空白弄丢或弄坏」。
 *
 * 这里起一个只依赖 `java.net` 的极简 HTTP/1.1 回环服务，让它返回**带空白的合法 JSON**，
 * 由生产代码 [HttpAssistTransport] 真的读回来，再走完整 `classify()`。不联网、不用真 Key。
 *
 * 覆盖：
 * 1. `"content": "KEEP"`（冒号+空格）→ 真的读出 KEEP（本轮缺陷的端到端复现）；
 * 2. pretty-print 的多行 JSON（冒号后换行 + 缩进）→ 读出 LOWER；
 * 3. 真实响应常见形态（`id`/`usage`/`opencode.allowance` 等额外字段 + 空白）→ 读出 KEEP；
 * 4. 空 content / 空白 content → 空建议（回退本地）；
 * 5. 合法 JSON 但 `choices` 为空 → 空建议；
 * 6. 非 JSON（HTML 502 页）→ 空建议，不抛异常。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class IntentAssistHttpResponseParsingTest {

    private lateinit var serverSocket: ServerSocket
    private lateinit var serverThread: Thread
    private lateinit var keyStore: IntentAssistKeyStore
    private var baseUrl: String = ""

    @Volatile
    private var respondWith: String = ""

    private val requestCount = AtomicInteger(0)

    @Before
    fun setUp() {
        val dir = File(System.getProperty("java.io.tmpdir"), "parse-http-" + System.nanoTime())
        dir.mkdirs()
        keyStore = IntentAssistKeyStore(File(dir, "deepseek.key"))
        keyStore.write("sk-synthetic-parsing-http-0001")

        serverSocket = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        baseUrl = "http://127.0.0.1:${serverSocket.localPort}"
        serverThread = Thread({
            while (!serverSocket.isClosed) {
                val socket = try {
                    serverSocket.accept()
                } catch (e: Throwable) {
                    return@Thread
                }
                // 每连接一条线程：慢/断开的连接不能挡住后续用例的 accept
                Thread({
                    try {
                        handle(socket)
                    } catch (e: Throwable) {
                        // 客户端提前断开：预期路径
                    } finally {
                        runCatching { socket.close() }
                    }
                }, "parse-http-conn").apply { isDaemon = true; start() }
            }
        }, "parse-http").apply { isDaemon = true; start() }
    }

    @After
    fun tearDown() {
        runCatching { serverSocket.close() }
        runCatching { serverThread.join(1_000) }
        keyStore.delete()
    }

    /**
     * 极简 HTTP/1.1 服务端。
     *
     * **必须按字节读**，不能用 `BufferedReader`：`Content-Length` 是**字节**数，
     * 而请求体里有中文（UTF-8 每字 3 字节），按字符读会少读、然后阻塞到超时，
     * 客户端拿到 `IOException` → `lastFailure=network`（本轮实测踩过一次）。
     * 这里用 `InputStream` 读到 `\r\n\r\n` 再按 Content-Length 读够字节。
     */
    private fun handle(socket: Socket) {
        socket.soTimeout = 10_000
        val input = socket.getInputStream()
        val head = StringBuilder()
        var b = input.read()
        while (b >= 0) {
            head.append(b.toChar())
            if (head.length >= 4 && head.endsWith("\r\n\r\n")) break
            b = input.read()
        }
        if (head.isEmpty()) return
        val len = Regex("(?im)^content-length:\\s*(\\d+)")
            .find(head)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        var read = 0
        while (read < len) {
            val n = input.read()
            if (n < 0) break
            read++
        }
        requestCount.incrementAndGet()

        val body = respondWith.toByteArray(Charsets.UTF_8)
        val resp = "HTTP/1.1 200 OK\r\n" +
            "Content-Type: application/json\r\n" +
            "Content-Length: ${body.size}\r\n\r\n"
        socket.getOutputStream().apply {
            write(resp.toByteArray(Charsets.UTF_8))
            write(body)
            flush()
        }
        // 给客户端一点时间读完再关；直接关会让 keep-alive 复用的下一次请求撞到死连接
        Thread.sleep(50)
    }

    private fun snippet() = IntentTextSanitizer.sanitizeForAssistant(
        listOf("满199减30", "活动已于 2026-11-10 23:59 结束")
    )

    private fun assist() = DeepSeekTextAssist(
        keyStore = keyStore,
        userEnabledForBatch = true,
        transport = HttpAssistTransport(),
        baseUrl = baseUrl,
        timeoutMillis = 5_000
    )

    private fun classify(response: String): AssistSuggestion {
        respondWith = response
        val assist = assist()
        val result = runBlocking { assist.classify(snippet()) }
        assertTrue("请求必须真的发出去了", requestCount.get() >= 1)
        // 失败原因必须为空：非空说明走的是「回退本地」而不是「解析成功」，
        // 那样下面的断言就算通过也没证明解析器。
        assertNull("不该走回退（lastFailure 应为空）", assist.lastFailure)
        return result
    }

    // ==================== 1. 本轮缺陷的端到端复现 ====================

    @Test
    fun spaceAfterColonInRealResponseYieldsKeep() {
        val suggestion = classify(
            """{"choices":[{"message":{"role":"assistant","content": "KEEP"}}]}"""
        )
        assertEquals(
            "从网络读回来的 `\"content\": \"KEEP\"` 必须真的判成 KEEP",
            IntentRecommendation.KEEP, suggestion.recommendation
        )
        assertEquals(IntentCategory.PROTECTED, suggestion.category)
    }

    @Test
    fun prettyPrintedResponseYieldsLower() {
        val suggestion = classify(
            """
            {
              "id": "chatcmpl-parse-1",
              "choices": [
                {
                  "index": 0,
                  "message": {
                    "role": "assistant",
                    "content": "LOWER"
                  },
                  "finish_reason": "stop"
                }
              ]
            }
            """.trimIndent()
        )
        assertEquals(
            "pretty-print（冒号后换行 + 缩进）必须读出 LOWER",
            IntentRecommendation.LOWER_PRIORITY, suggestion.recommendation
        )
    }

    @Test
    fun realWorldResponseShapeWithExtraFieldsYieldsKeep() {
        val suggestion = classify(
            """{"id":"chatcmpl-2","object":"chat.completion","created":1796000000,""" +
                """"model":"deepseek-chat","choices":[{"index":0,"finish_reason":"stop",""" +
                """"message":{"role":"assistant","content":  "KEEP"  }}],""" +
                """"usage":{"prompt_tokens":31,"completion_tokens":1,"total_tokens":32},""" +
                """"opencode":{"billing_source":"go","allowance":0.00012}}"""
        )
        assertEquals(
            "真实响应形态（多余字段 + 空白）必须读出 KEEP",
            IntentRecommendation.KEEP, suggestion.recommendation
        )
    }

    // ==================== 2. 回退语义在真实 HTTP 上同样成立 ====================

    @Test
    fun emptyContentOverRealHttpFallsBack() {
        val suggestion = classify("""{"choices":[{"message":{"content": ""}}]}""")
        assertEquals(IntentRecommendation.UNCERTAIN, suggestion.recommendation)
        assertTrue("空建议不带理由", suggestion.reason.isEmpty())
    }

    @Test
    fun whitespaceOnlyContentOverRealHttpFallsBack() {
        val suggestion = classify("""{"choices":[{"message":{"content": "  \n\t "}}]}""")
        assertEquals(IntentRecommendation.UNCERTAIN, suggestion.recommendation)
    }

    @Test
    fun emptyChoicesOverRealHttpFallsBack() {
        val suggestion = classify("""{"choices":[],"usage":{"prompt_tokens":10}}""")
        assertEquals(IntentRecommendation.UNCERTAIN, suggestion.recommendation)
    }

    @Test
    fun htmlErrorPageOverRealHttpFallsBack() {
        val suggestion = classify("<html><body><h1>502 Bad Gateway</h1></body></html>")
        assertEquals(IntentRecommendation.UNCERTAIN, suggestion.recommendation)
    }

    /** 未知建议在真实 HTTP 上也不得变成清理动作。 */
    @Test
    fun unknownAdviceOverRealHttpNeverBecomesCleanup() {
        val suggestion = classify(
            """{"choices":[{"message":{"content": "DELETE all of them now"}}]}"""
        )
        assertEquals(IntentRecommendation.UNCERTAIN, suggestion.recommendation)
    }
}
