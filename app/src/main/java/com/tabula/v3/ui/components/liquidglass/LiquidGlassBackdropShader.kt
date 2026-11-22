package com.tabula.v3.ui.components.liquidglass

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import androidx.annotation.RequiresApi

/**
 * 背景采样层的 AGSL 着色器。
 *
 * 输入 `content` 是"只记录背景"的离屏图层（不含玻璃节点自身），因此边缘
 * 折射采到的一定是背景，不会把玻璃自己的高光再折射一遍。
 *
 * 与仓库已有的 `LIQUID_GLASS_SURFACE_SHADER` 的关系：那是"玻璃表面"的
 * 着色器（高光/环带/色散/alpha），本文件只管"边缘把背景掰弯"，两者串联
 * 使用，不互相替代。
 *
 * 圆角矩形 SDF 与仓库现有实现同源的写法；折射带、位移方向和采样夹取为
 * 本项目自写实现，未逐行复制外部仓库代码。
 */
internal const val LIQUID_GLASS_BACKDROP_SHADER: String = """
    uniform shader content;
    uniform float2 uLayerSize;
    uniform float2 uGlassOrigin;
    uniform float2 uGlassSize;
    uniform float uCornerRadius;
    uniform float uRefractionHeight;
    uniform float uRefractionAmount;
    uniform float uDispersion;

    // 圆角矩形 SDF：像素坐标，局部原点为玻璃节点左上角
    float sdRoundedRect(float2 p, float2 halfSize, float radius) {
        float2 q = abs(p) - halfSize + radius;
        return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - radius;
    }

    // 中心差分求 SDF 梯度；除以 2e 得到真实梯度方向
    float sdGradientX(float2 p, float2 halfSize, float radius) {
        float e = 0.75;
        return (sdRoundedRect(p + float2(e, 0.0), halfSize, radius)
              - sdRoundedRect(p - float2(e, 0.0), halfSize, radius)) / (2.0 * e);
    }

    float sdGradientY(float2 p, float2 halfSize, float radius) {
        float e = 0.75;
        return (sdRoundedRect(p + float2(0.0, e), halfSize, radius)
              - sdRoundedRect(p - float2(0.0, e), halfSize, radius)) / (2.0 * e);
    }

    // 采样点夹取在背景图层内：越界时重复边缘像素，避免出现黑边
    float2 clampToLayer(float2 p) {
        return clamp(p, float2(0.5), uLayerSize - float2(0.5));
    }

    half4 main(float2 coord) {
        // coord 是图层坐标；换算到玻璃节点的局部坐标
        float2 local = coord - uGlassOrigin;
        float2 halfSize = uGlassSize * 0.5;
        float2 centered = local - halfSize;
        float radius = min(uCornerRadius, min(halfSize.x, halfSize.y));

        float sd = sdRoundedRect(centered, halfSize, radius);

        if (uRefractionHeight <= 0.0001 || uRefractionAmount <= 0.0001) {
            return content.eval(clampToLayer(coord));
        }

        // 只处理玻璃内部；外部保持原样（图层外扩部分直接透传）
        if (sd > 0.0) {
            return content.eval(clampToLayer(coord));
        }

        // 到边缘的距离，归一化到折射带内
        float distToEdge = -sd;
        if (distToEdge >= uRefractionHeight) {
            return content.eval(clampToLayer(coord));
        }

        float t = clamp(distToEdge / uRefractionHeight, 0.0, 1.0);
        // 越靠边缘位移越大，向内平滑衰减
        float strength = pow(1.0 - t, 2.0);

        // 梯度指向玻璃外侧，取负号把背景"向内掰"
        float2 grad = float2(
            sdGradientX(centered, halfSize, radius),
            sdGradientY(centered, halfSize, radius)
        );
        float gradLen = length(grad);
        float2 dir = gradLen > 0.0001 ? grad / gradLen : float2(0.0, 1.0);

        float2 offset = -dir * strength * uRefractionAmount;

        float d = uDispersion * 0.5;
        float2 offsetR = offset * (1.0 + d);
        float2 offsetG = offset;
        float2 offsetB = offset * (1.0 - d);

        half4 cR = content.eval(clampToLayer(coord + offsetR));
        half4 cG = content.eval(clampToLayer(coord + offsetG));
        half4 cB = content.eval(clampToLayer(coord + offsetB));

        // 只做色散合成，不叠加任何高光/亮度：原有材质仍负责高光与边缘发光
        return half4(half3(cR.r, cG.g, cB.b), half(clamp(cG.a, 0.0, 1.0)));
    }
"""

/**
 * 采样层的效果链：先按尺寸建 RuntimeShader，再串一个模糊。
 *
 * 顺序与仓库 `RealLiquidGlass.kt` 一致（模糊在前、折射在后），因为先模糊
 * 才能让边缘取到的是柔和背景而不是原始细节。
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
internal fun createBackdropRenderEffect(
    plan: BackdropSamplePlan,
    appearance: LiquidGlassBackdropAppearance,
    cornerRadiusPx: Float,
    onError: ((String) -> Unit)? = null
): RenderEffect? {
    if (!plan.useSampledBackdrop) return null
    return try {
        val shader = RuntimeShader(LIQUID_GLASS_BACKDROP_SHADER)
        shader.setFloatUniform(
            "uLayerSize",
            plan.layerWidthPx.toFloat(),
            plan.layerHeightPx.toFloat()
        )
        shader.setFloatUniform(
            "uGlassOrigin",
            plan.paddingPx.toFloat(),
            plan.paddingPx.toFloat()
        )
        shader.setFloatUniform(
            "uGlassSize",
            plan.glassWidthPx.toFloat(),
            plan.glassHeightPx.toFloat()
        )
        val maxRadius = minOf(plan.glassWidthPx, plan.glassHeightPx) / 2f
        shader.setFloatUniform("uCornerRadius", cornerRadiusPx.coerceIn(0f, maxRadius))
        shader.setFloatUniform("uRefractionHeight", appearance.refractionHeightPx)
        shader.setFloatUniform("uRefractionAmount", appearance.refractionAmountPx)
        shader.setFloatUniform("uDispersion", appearance.dispersion)

        val refraction = RenderEffect.createRuntimeShaderEffect(shader, "content")
        if (appearance.blurRadiusPx > 0.01f) {
            val blur = RenderEffect.createBlurEffect(
                appearance.blurRadiusPx,
                appearance.blurRadiusPx,
                Shader.TileMode.CLAMP
            )
            RenderEffect.createChainEffect(refraction, blur)
        } else {
            refraction
        }
    } catch (e: IllegalArgumentException) {
        // 着色器编译失败：回到原有材质，但把原因报出去，避免被误判成"已生效"
        onError?.invoke("${e.javaClass.simpleName}: ${e.message}")
        null
    } catch (e: RuntimeException) {
        onError?.invoke("${e.javaClass.simpleName}: ${e.message}")
        null
    }
}
