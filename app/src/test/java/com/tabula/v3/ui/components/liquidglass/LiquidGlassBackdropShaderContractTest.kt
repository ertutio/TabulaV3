package com.tabula.v3.ui.components.liquidglass

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 采样层 shader 的契约测试。
 *
 * 着色器是字符串，改错 uniform 名字不会在编译期报错，只会在真机上静默失效。
 * 这里把"着色器声明的 uniform"与"Kotlin 侧 setFloatUniform 的键"锁在一起。
 */
class LiquidGlassBackdropShaderContractTest {

    private val declaredUniforms = listOf(
        "uniform shader content;",
        "uniform float2 uLayerSize;",
        "uniform float2 uGlassOrigin;",
        "uniform float2 uGlassSize;",
        "uniform float uCornerRadius;",
        "uniform float uRefractionHeight;",
        "uniform float uRefractionAmount;",
        "uniform float uDispersion;"
    )

    @Test
    fun `shader declares every uniform the kotlin side sets`() {
        declaredUniforms.forEach { decl ->
            assertTrue("缺少声明: $decl", LIQUID_GLASS_BACKDROP_SHADER.contains(decl))
        }
    }

    @Test
    fun `shader samples content through the clamp helper`() {
        // 所有取样都必须经过夹取，越界时重复边缘像素而不是引入黑边
        val evalCalls = Regex("content\\.eval\\(").findAll(LIQUID_GLASS_BACKDROP_SHADER).count()
        val clampedCalls = Regex("content\\.eval\\(clampToLayer\\(")
            .findAll(LIQUID_GLASS_BACKDROP_SHADER).count()
        assertTrue("应至少有一处取样", evalCalls >= 4)
        assertTrue("取样必须全部夹取到图层内", evalCalls == clampedCalls)
    }

    @Test
    fun `shader keeps the background outside the glass untouched`() {
        assertTrue(
            "玻璃外部必须原样透传背景",
            LIQUID_GLASS_BACKDROP_SHADER.contains("if (sd > 0.0)")
        )
        assertTrue(
            "折射带之外必须原样透传背景",
            LIQUID_GLASS_BACKDROP_SHADER.contains("if (distToEdge >= uRefractionHeight)")
        )
    }

    @Test
    fun `shader does not fabricate extra lighting on top of the existing materials`() {
        // 原有高光/边缘/内阴影/噪点仍由 PhysicalLiquidGlass 负责，这里不重复叠加
        assertFalse(LIQUID_GLASS_BACKDROP_SHADER.contains("specular"))
        assertFalse(LIQUID_GLASS_BACKDROP_SHADER.contains("noise"))
        assertFalse(LIQUID_GLASS_BACKDROP_SHADER.contains("rim"))
    }

    @Test
    fun `shader normalizes the sdf gradient so displacement does not depend on step size`() {
        assertTrue(LIQUID_GLASS_BACKDROP_SHADER.contains("/ (2.0 * e)"))
        assertTrue(LIQUID_GLASS_BACKDROP_SHADER.contains("grad / gradLen"))
    }
}
