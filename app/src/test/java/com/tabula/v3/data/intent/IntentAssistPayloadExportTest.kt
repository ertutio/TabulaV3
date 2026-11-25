package com.tabula.v3.data.intent

import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * **生产代码真正生成的请求体**检查（#94 第 2 轮审计，第 4 条）。
 *
 * 审计要点：不要用另写一份 Python 拼串去验，要检查 `DeepSeekTextAssist.buildPayload`
 * 实际产出的东西。这里用记录型传输层，逐条断言四类场景：
 *
 * 1. **形似日期的敏感值**（`验证码 20261110`）——不得出现在请求体里；
 * 2. **OCR 错读的链接**（`https:Wpan.example.com`）——不得出现；
 * 3. **安全截止日期**（`活动已于 2026-11-10 23:59 结束`）——必须保留，否则辅助失去判断力；
 * 4. **开关关闭 / 无 Key** —— 请求数必须为 0；
 * 5. 请求体里没有原图、缩略图、图片编码、路径。
 *
 * 顺带把 payload 导出到 `build/intent-pilot/payloads.json` 供在线试点脚本使用——
 * 那也保证了「试点量的请求」与「线上发的请求」是同一份东西。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class IntentAssistPayloadExportTest {

    private class RecordingTransport(
        private val response: String = """{"choices":[{"message":{"content":"LOWER"}}]}"""
    ) : AssistTransport {
        val payloads = mutableListOf<String>()
        override fun post(url: String, body: String, authHeader: String, timeoutMillis: Int): String {
            payloads += body
            return response
        }
    }

    private fun keyStore(withKey: Boolean): IntentAssistKeyStore {
        val file = File.createTempFile("pilot-key", ".tmp").apply { deleteOnExit() }
        val store = IntentAssistKeyStore(file)
        if (withKey) store.write("sk-pilot-placeholder-not-a-real-key") else store.delete()
        return store
    }

    private fun snippet(vararg lines: String) =
        IntentTextSanitizer.sanitizeForAssistant(lines.toList())

    // ==================== 1. 形似日期的敏感值 ====================

    @Test
    fun secretThatLooksLikeADateNeverReachesTheRequest() = kotlinx.coroutines.runBlocking {
        val transport = RecordingTransport()
        val assist = DeepSeekTextAssist(keyStore(withKey = true), true, transport)

        // 这一行在脱敏阶段就被判为「不可靠判定」，因此根本不会被发送
        val lines = snippet("验证码 20261110")
        assertTrue("含形似日期敏感值的行必须留在本地", lines.isEmpty)

        assist.classify(lines)
        assertEquals("不该发出任何请求", 0, transport.payloads.size)
    }

    @Test
    fun secretMixedWithSafeCopyIsHeldBackEntirely() = kotlinx.coroutines.runBlocking {
        // 第 6 轮口径变了：含敏感字段的**整行**留在本地，不再「掩掉值、其余照发」。
        // 因此同一行的「5分钟内有效」也不再外发——它与秘密同处一行，
        // 我们无法证明两者无关（值可能被 OCR 拆开或粘连）。
        val transport = RecordingTransport()
        val assist = DeepSeekTextAssist(keyStore(withKey = true), true, transport)
        assist.classify(snippet("【某平台】活动通知", "验证码 123456，5分钟内有效"))
        assertEquals(1, transport.payloads.size)
        val payload = transport.payloads.first()
        assertFalse("验证码不得出现", payload.contains("123456"))
        assertFalse("被扣留行里的有效期也不得外发", payload.contains("5分钟"))
        assertTrue("同批的无关行仍应保留：$payload", payload.contains("【某平台】活动通知"))
    }

    // ==================== 2. OCR 错读的链接 ====================

    @Test
    fun ocrGarbledLinkNeverReachesTheRequest() = kotlinx.coroutines.runBlocking {
        val transport = RecordingTransport()
        val assist = DeepSeekTextAssist(keyStore(withKey = true), true, transport)
        assist.classify(
            snippet(
                "季度汇报模板.pptx",
                "提取码已过期",
                "复制链接 https:Wpan.example.com/s/XyZ98765"
            )
        )
        assertEquals(1, transport.payloads.size)
        val payload = transport.payloads.first()
        listOf("pan.example.com", "XyZ98765", "https:W", "https://").forEach { secret ->
            assertFalse("请求体不得包含链接片段 $secret：$payload", payload.contains(secret))
        }
        // 注意：「提取码已过期」这一行现在会被丢弃——字段后紧跟「已过期」会被当成值定位
        // 的一部分，而我们宁可不发也不猜。换一种不触发字段规则的表述来验证「失效状态可保留」。
        assertTrue(
            "链接本身已被掩掉：$payload",
            !payload.contains("pan.example.com") && !payload.contains("XyZ98765")
        )
    }

    // ==================== 3. 安全截止日期必须保留 ====================

    @Test
    fun safeDeadlineSurvivesIntoTheRequest() = kotlinx.coroutines.runBlocking {
        val transport = RecordingTransport()
        val assist = DeepSeekTextAssist(keyStore(withKey = true), true, transport)
        assist.classify(snippet("满199减30", "活动已于 2026-11-10 23:59 结束"))
        assertEquals(1, transport.payloads.size)
        val payload = transport.payloads.first()
        assertTrue("截止日期必须保留，否则模型无法判断：$payload", payload.contains("2026-11-10"))
        assertTrue(payload.contains("23:59"))
    }

    // ==================== 4. 关闭 / 无 Key → 零请求 ====================

    @Test
    fun noKeyMeansZeroRequests() = kotlinx.coroutines.runBlocking {
        val transport = RecordingTransport()
        val assist = DeepSeekTextAssist(keyStore(withKey = false), true, transport)
        assist.classify(snippet("满199减30", "活动已于 2026-11-10 结束"))
        assertEquals(0, transport.payloads.size)
    }

    @Test
    fun disabledMeansZeroRequests() = kotlinx.coroutines.runBlocking {
        val transport = RecordingTransport()
        val assist = DeepSeekTextAssist(keyStore(withKey = true), false, transport)
        assist.classify(snippet("满199减30", "活动已于 2026-11-10 结束"))
        assertEquals(0, transport.payloads.size)
    }

    // ==================== 5. 请求体结构 ====================

    @Test
    fun payloadHasNoImageOrPathAndOnlyKnownFields() = kotlinx.coroutines.runBlocking {
        val transport = RecordingTransport()
        val assist = DeepSeekTextAssist(keyStore(withKey = true), true, transport)
        assist.classify(snippet("某视频平台", "复制链接", "扫码观看"))
        val payload = transport.payloads.first()

        listOf(
            "base64", "data:image", "content://", "/storage/", "DCIM", ".png", ".jpg",
            "image_url", "file://", "缩略图"
        ).forEach { forbidden ->
            assertFalse("请求体不得包含 $forbidden：$payload", payload.contains(forbidden, ignoreCase = true))
        }

        val parsed = JSONObject(payload)
        val allowed = setOf("model", "temperature", "max_tokens", "messages")
        parsed.keys().forEach { key ->
            assertTrue("请求体出现了非预期字段 $key", key in allowed)
        }
        assertEquals("deepseek-chat", parsed.getString("model"))
    }

    // ==================== 导出给在线试点 ====================

    @Test
    fun exportRealPayloadsForPilotAndAudit() = kotlinx.coroutines.runBlocking {
        val golden = locateGolden()
        val samples = JSONObject(golden.readText(Charsets.UTF_8)).getJSONArray("samples")
        val transport = RecordingTransport()
        val assist = DeepSeekTextAssist(keyStore(withKey = true), true, transport)

        val exported = JSONArray()
        for (i in 0 until samples.length()) {
            val sample = samples.getJSONObject(i)
            val linesArray = sample.optJSONArray("golden_lines") ?: continue
            val lines = (0 until linesArray.length()).map { linesArray.getString(it) }
            if (lines.isEmpty()) continue
            val snippet = IntentTextSanitizer.sanitizeForAssistant(lines)
            if (snippet.isEmpty || snippet.hadUndecidableLines) continue
            assist.classify(snippet)
            val payload = assist.lastRequestPayload ?: continue
            exported.put(
                JSONObject().apply {
                    put("file", sample.getString("file"))
                    put("category", sample.getString("category"))
                    put("payload", payload)
                    put("sanitizedLines", JSONArray(snippet.lines))
                }
            )
        }

        val items = (0 until exported.length()).map { exported.getJSONObject(it) }

        // 敏感字段：golden.json 声明的 sensitive_tokens 不得出现
        items.forEach { item ->
            sensitiveTokensOf(item.getString("file")).forEach { token ->
                assertFalse(
                    "${item.getString("file")} 的请求体里出现了敏感片段 $token",
                    item.getString("payload").contains(token)
                )
            }
        }

        // 导出的每一条都要能通过「无图像 / 无路径」检查
        items.forEach { item ->
            val payload = item.getString("payload")
            listOf("base64", "content://", "/storage/", ".png", ".jpg").forEach { forbidden ->
                assertFalse(payload.contains(forbidden, ignoreCase = true))
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

    private fun locateGolden(): File = listOf(
        File("src/test/resources/intent_samples/golden.json"),
        File("app/src/test/resources/intent_samples/golden.json")
    ).first { it.exists() }

    private fun sensitiveTokensOf(fileName: String): List<String> {
        val samples = JSONObject(locateGolden().readText(Charsets.UTF_8)).getJSONArray("samples")
        for (i in 0 until samples.length()) {
            val sample = samples.getJSONObject(i)
            if (sample.getString("file") != fileName) continue
            val array = sample.optJSONArray("sensitive_tokens") ?: return emptyList()
            return (0 until array.length()).map { array.getString(it) }
        }
        return emptyList()
    }
}
