package com.tabula.v3.data.intent

import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 把「真实实现会发出去的请求体」导出来，供两件事用：
 *
 * 1. **离线审计**：断言请求体里没有图像/base64/路径/URI/文件名；
 * 2. **在线试点**：`tools/intent-samples/run_deepseek_pilot.py` 直接读这个文件里的
 *    payload 原文去调 API，保证试点用的是**本实现实际生成的请求**，
 *    而不是另外写一份 Python 拼串（那样量出来的用量没有意义）。
 *
 * 输出：`app/build/intent-pilot/payloads.json`。不含 Key，不含原图。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class IntentAssistPayloadExportTest {

    private val now = 1_796_000_000_000L

    private fun locateGolden(): File = listOf(
        File("src/test/resources/intent_samples/golden.json"),
        File("app/src/test/resources/intent_samples/golden.json")
    ).first { it.exists() }

    /** 用真实的 [DeepSeekTextAssist] 生成请求体，但传输层只记录、不联网。 */
    private class RecordingTransport : AssistTransport {
        val payloads = mutableListOf<String>()
        override fun post(url: String, body: String, authHeader: String, timeoutMillis: Int): String {
            payloads += body
            return """{"choices":[{"message":{"content":"LOWER"}}]}"""
        }
    }

    @Test
    fun exportRealPayloadsForPilotAndAudit() = kotlinx.coroutines.runBlocking {
        val root = JSONObject(locateGolden().readText(Charsets.UTF_8))
        val samples = root.getJSONArray("samples")
        val transport = RecordingTransport()

        val keyFile = File.createTempFile("pilot-key", ".tmp").apply {
            writeText("sk-pilot-placeholder-not-a-real-key")
            deleteOnExit()
        }
        val assist = DeepSeekTextAssist(
            keyStore = IntentAssistKeyStore(keyFile),
            userEnabledForBatch = true,
            transport = transport
        )

        val exported = JSONArray()
        for (i in 0 until samples.length()) {
            val o = samples.getJSONObject(i)
            val linesArray = o.optJSONArray("golden_lines") ?: continue
            val lines = (0 until linesArray.length()).map { linesArray.getString(it) }
            if (lines.isEmpty()) continue
            val snippet = IntentTextSanitizer.sanitizeForAssistant(lines)
            if (snippet.isEmpty || snippet.hadUndecidableLines) continue
            assist.classify(snippet)
            val payload = assist.lastRequestPayload ?: continue
            exported.put(
                JSONObject().apply {
                    put("file", o.getString("file"))
                    put("category", o.getString("category"))
                    put("payload", payload)
                    put("sanitizedLines", JSONArray(snippet.lines))
                }
            )
        }

        val exportedItems = (0 until exported.length()).map { exported.getJSONObject(it) }

        // ---------- 离线审计：请求体里不能有图像/路径/URI ----------
        exportedItems.forEach { item ->
            val payload = item.getString("payload")
            listOf("base64", "data:image", "content://", "/storage/", "DCIM", ".png", ".jpg",
                "缩略图", "image_url", "file://").forEach { forbidden ->
                assertFalse("请求体不应包含 $forbidden", payload.contains(forbidden, ignoreCase = true))
            }
            // 只允许 DeepSeek 兼容接口的两个字段 + messages
            val parsed = JSONObject(payload)
            assertTrue(parsed.has("model"))
            assertTrue(parsed.has("messages"))
            assertEqualsKeys(parsed)
        }

        // ---------- 敏感字段：不得出现在请求里 ----------
        exportedItems.forEach { obj ->
            val golden = findGolden(obj.getString("file"))
            golden.forEach { token ->
                assertFalse(
                    "${obj.getString("file")} 的请求体里出现了敏感片段 $token",
                    obj.getString("payload").contains(token)
                )
            }
        }

        val outDir = File("build/intent-pilot").apply { mkdirs() }
        File(outDir, "payloads.json").writeText(
            JSONObject().put("count", exported.length()).put("payloads", exported).toString(2),
            Charsets.UTF_8
        )
        println("[payload-export] exported=${exported.length()} → ${File(outDir, "payloads.json").absolutePath}")
        assertTrue("至少应导出若干可发送片段", exported.length() >= 5)
    }

    private fun assertEqualsKeys(parsed: JSONObject) {
        val allowed = setOf("model", "temperature", "max_tokens", "messages")
        parsed.keys().forEach { key ->
            assertTrue("请求体出现了非预期字段 $key", key in allowed)
        }
    }

    private fun findGolden(fileName: String): List<String> {
        val root = JSONObject(locateGolden().readText(Charsets.UTF_8))
        val samples = root.getJSONArray("samples")
        for (i in 0 until samples.length()) {
            val o = samples.getJSONObject(i)
            if (o.getString("file") == fileName) {
                val a = o.optJSONArray("sensitive_tokens") ?: return emptyList()
                return (0 until a.length()).map { a.getString(it) }
            }
        }
        return emptyList()
    }
}
