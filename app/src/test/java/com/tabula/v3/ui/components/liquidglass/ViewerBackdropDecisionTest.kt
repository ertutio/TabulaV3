package com.tabula.v3.ui.components.liquidglass

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 查看器的渲染决策必须是**单一来源**：同一个 [ViewerBackdropDecision] 同时
 * 决定 UI 分支与审计行，二者不可能分叉。
 *
 * ## 为什么需要这组测试
 *
 * 旧实现里顶栏只收到一个 `sampled: Boolean`，而审计行另外去读
 * `LiquidGlassBackdropState.lastPlan`。回退时玻璃节点根本不渲染，`lastPlan`
 * 就停在上一轮采样结果上——审计行写 `sampled=true reason=NONE`，屏幕却已经是
 * 普通按钮。设备测试读那一行会把"正在回退"读成"仍在采样"。
 *
 * 这些用例把"决策 → 路径 → 原因"三者锁死，任何一处改动导致分叉都会在这里红，
 * 而不是等 15 分钟模拟器。
 */
class ViewerBackdropDecisionTest {

    private fun decision(
        enabled: Boolean = true,
        sdkInt: Int = 35,
        hasPhoto: Boolean = true,
        sync: ViewerBackdropSync? = ViewerBackdropSync.Settled
    ) = ViewerBackdropDecision.of(enabled, sdkInt, hasPhoto, sync)

    // ==================== 第 1 层：接不接背景 ====================

    @Test
    fun `lab switch off reports DISABLED and fallback path`() {
        val d = decision(enabled = false)
        assertFalse(d.sampling)
        assertEquals(BackdropFallbackReason.DISABLED, d.reason)
        assertEquals("fallback", d.path)
    }

    @Test
    fun `sdk below sampling floor reports UNSUPPORTED_API`() {
        listOf(31, 32).forEach { sdk ->
            val d = decision(sdkInt = sdk)
            assertFalse("sdk=$sdk 不应采样", d.sampling)
            assertEquals("sdk=$sdk", BackdropFallbackReason.UNSUPPORTED_API, d.reason)
        }
    }

    @Test
    fun `missing photo reports NO_BACKDROP and can self heal`() {
        val d = decision(hasPhoto = false)
        assertFalse(d.sampling)
        assertEquals(BackdropFallbackReason.NO_BACKDROP, d.reason)
        // 照片解码出来后同一个调用点会变成可采样——这就是"能自愈"的意思
        assertTrue(decision(hasPhoto = true).sampling)
    }

    @Test
    fun `reason gate agrees with the boolean gate`() {
        // 不变量：reason==NONE 必须与 shouldAttachPhotoBackdrop 完全一致。
        listOf(false, true).forEach { enabled ->
            listOf(31, 32, 33, 35, 36).forEach { sdk ->
                listOf(false, true).forEach { hasPhoto ->
                    val reason = photoBackdropAttachReason(enabled, sdk, hasPhoto)
                    val attach = shouldAttachPhotoBackdrop(enabled, sdk, hasPhoto)
                    assertEquals(
                        "enabled=$enabled sdk=$sdk photo=$hasPhoto",
                        attach,
                        reason == BackdropFallbackReason.NONE
                    )
                }
            }
        }
    }

    // ==================== 第 2 层：此刻能不能采 ====================

    @Test
    fun `pager scrolling reports VIEWER_SCROLLING`() {
        val d = decision(sync = ViewerBackdropSync(pagerSettled = false, scale = 1f, panX = 0f, panY = 0f))
        assertFalse(d.sampling)
        assertEquals(BackdropFallbackReason.VIEWER_SCROLLING, d.reason)
        assertEquals("fallback", d.path)
    }

    @Test
    fun `zoom or pan reports VIEWER_TRANSFORMED`() {
        listOf(
            ViewerBackdropSync(pagerSettled = true, scale = 3f, panX = 0f, panY = 0f),
            ViewerBackdropSync(pagerSettled = true, scale = 1f, panX = 40f, panY = 0f),
            ViewerBackdropSync(pagerSettled = true, scale = 1f, panX = 0f, panY = -40f)
        ).forEach { sync ->
            val d = decision(sync = sync)
            assertFalse("$sync 不应采样", d.sampling)
            assertEquals("$sync", BackdropFallbackReason.VIEWER_TRANSFORMED, d.reason)
        }
    }

    @Test
    fun `settled viewer samples and reports the glass path`() {
        val d = decision()
        assertTrue(d.sampling)
        assertEquals(BackdropFallbackReason.NONE, d.reason)
        assertEquals("glass", d.path)
    }

    // ==================== 两层叠加时的优先级 ====================

    @Test
    fun `attach gate outranks viewer sync`() {
        // 门控关着时，即使查看器说"可以采"，结论仍然是不采样，
        // 且原因必须是门控的原因（没有玻璃节点可谈"翻页回退"）。
        val d = decision(
            enabled = false,
            sync = ViewerBackdropSync(pagerSettled = false, scale = 1f, panX = 0f, panY = 0f)
        )
        assertFalse(d.sampling)
        assertEquals(BackdropFallbackReason.DISABLED, d.reason)
    }

    @Test
    fun `non viewer callers opt out of the sync layer`() {
        // viewerSync = null（面板、预览等静态场景）：只看第 1 层
        val d = decision(sync = null)
        assertTrue(d.sampling)
        assertEquals(BackdropFallbackReason.NONE, d.reason)
    }

    @Test
    fun `path and sampling never disagree`() {
        // 审计行里的 path 就是 sampling 的字符串投影；两者必须一一对应。
        listOf(
            decision(),
            decision(enabled = false),
            decision(sdkInt = 32),
            decision(hasPhoto = false),
            decision(sync = ViewerBackdropSync(false, 1f, 0f, 0f)),
            decision(sync = ViewerBackdropSync(true, 3f, 0f, 0f))
        ).forEach { d ->
            assertEquals("sampling=${d.sampling}", if (d.sampling) "glass" else "fallback", d.path)
        }
    }
}
