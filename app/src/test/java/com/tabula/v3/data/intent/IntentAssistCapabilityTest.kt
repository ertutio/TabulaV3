package com.tabula.v3.data.intent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 能力边界测试（#95，本轮只做预留）。
 *
 * 目的是把「本轮只有文字辅助」这件事钉成**可执行的断言**，而不是只写在文档里：
 * 将来有人加了图片通道却没改这里的断言，测试会先失败。
 */
class IntentAssistCapabilityTest {

    @Test
    fun onlyTextAssistIsAvailableThisRound() {
        assertEquals(
            "本轮可用能力集合必须只有文字辅助",
            setOf(IntentAssistCapability.Kind.TEXT_ONLY),
            IntentAssistCapability.available
        )
        assertFalse("图片辅助本轮必须不可用", IntentAssistCapability.imageAssistAvailable)
    }

    @Test
    fun imageGrantContractIsNotImplemented() {
        // 没有实现 → 任何调用方拿到的都是 null，只能按「不可用」处理
        assertNull("本轮不得提供图片授权实现", IntentAssistCapability.imageGrant)
    }

    @Test
    fun textClientHasNoSlotForImagesOrPaths() {
        // 文字客户端的入参类型结构上没有图片/路径槽位：这是编译期保证，
        // 这里用「字段名清单」把契约固定下来，防止有人后来往里加。
        val fieldNames = IntentTextSanitizer.SanitizedSnippet::class.java.declaredFields
            .map { it.name }
            .toSet()
        listOf(
            "bitmap", "image", "bytes", "uri", "path", "file", "thumbnail",
            "base64", "encoding", "photo"
        ).forEach { forbidden ->
            assertFalse(
                "SanitizedSnippet 不得有 $forbidden 槽位（实际字段：$fieldNames）",
                fieldNames.any { it.equals(forbidden, ignoreCase = true) ||
                    it.contains(forbidden, ignoreCase = true) }
            )
        }
        assertTrue("应有 lines 字段", "lines" in fieldNames)
    }

    @Test
    fun snippetExposesASendabilityFlag() {
        // 含字段的图不可发；干净的图可发。调用方必须读 sendable 而不是只看 lines。
        val dirty = IntentTextSanitizer.sanitizeForAssistant(listOf("验证码 4471", "8899"))
        val clean = IntentTextSanitizer.sanitizeForAssistant(
            listOf("满199减30", "活动已于 2026-11-10 23:59 结束")
        )
        assertFalse(dirty.sendable)
        assertTrue(clean.sendable)
    }
}
