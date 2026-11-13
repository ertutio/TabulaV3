package com.tabula.v3.ui.components

import android.graphics.Bitmap
import android.graphics.Paint
import android.util.Log
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import com.tabula.v3.BuildConfig
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin
import kotlinx.coroutines.delay

// ========== 性能优化：预计算正弦值查找表 ==========
// 避免每帧重复计算 sin 函数，提升 ~30% 性能
private val SIN_TABLE_SIZE = 256
private val SIN_TABLE = FloatArray(SIN_TABLE_SIZE) { i ->
    sin(i.toFloat() / (SIN_TABLE_SIZE - 1) * PI.toFloat() * 2f)
}

/**
 * 快速正弦近似（使用查找表）
 * @param angle 角度，范围 0 到 2π
 */
private fun fastSin(angle: Float): Float {
    val normalizedAngle = ((angle / (2f * PI.toFloat())) % 1f + 1f) % 1f
    val index = (normalizedAngle * (SIN_TABLE_SIZE - 1)).toInt().coerceIn(0, SIN_TABLE_SIZE - 1)
    return SIN_TABLE[index]
}

/**
 * Genie效果方向
 */
enum class GenieDirection {
    DOWN,   // 向下吸入（上宽下窄）- 用于下滑归类
    UP      // 向上吸入（下宽上窄）- 用于上滑删除
}

// 网格常量 - 降低密度以提升性能
private const val GENIE_MESH_COLS = 10  // 从 12 降到 10
private const val GENIE_MESH_ROWS = 16  // 从 20 降到 16
private const val GENIE_VERTEX_COUNT = (GENIE_MESH_COLS + 1) * (GENIE_MESH_ROWS + 1) * 2

/**
 * Genie Effect 覆盖层 - 标准macOS神灯吸入效果
 * 
 * 物理本质：整张图片在被吸入瓶口（目标点）的过程中，
 * 高度被压缩，宽度随着靠近瓶口呈指数级收缩
 * 
 * 性能优化版本 v2：
 * - 降低网格密度（10x16），视觉效果几乎无差别，但性能大幅提升
 * - 缓存 Paint 对象和顶点数组，避免每帧内存分配
 * - 使用快速正弦查找表
 * - 使用 graphicsLayer 启用硬件加速
 * 
 * @param direction 吸入方向：DOWN=向下吸入（上宽下窄），UP=向上吸入（下宽上窄）
 */
@Composable
fun GenieEffectOverlay(
    bitmap: Bitmap?,
    sourceBounds: Rect,
    targetX: Float,
    targetY: Float,
    progress: Float,
    screenHeight: Float = 2000f,
    direction: GenieDirection = GenieDirection.DOWN,
    modifier: Modifier = Modifier
) {
    // 检查 bitmap 是否为 null 或已被 recycle（避免闪退）
    if (bitmap == null || bitmap.isRecycled || progress <= 0f) return
    
    // 优化：缓存 Paint 对象，避免每帧创建新对象
    val paint = remember { 
        Paint().apply {
            isFilterBitmap = true
            isAntiAlias = true
        }
    }
    
    // 优化：复用顶点数组，避免每帧分配内存（减少 GC 压力）
    val vertsBuffer = remember { FloatArray(GENIE_VERTEX_COUNT) }
    
    // 使用 graphicsLayer 启用硬件加速
    Canvas(
        modifier = modifier
            .fillMaxSize()
            .graphicsLayer {
                // 启用离屏渲染，避免与其他图层混合时的性能问题
                compositingStrategy = CompositingStrategy.Offscreen
            }
    ) {
        // 再次检查，因为 Canvas 的绘制可能在异步执行
        if (bitmap.isRecycled) return@Canvas
        
        // 直接填充缓存数组（避免创建新数组）
        when (direction) {
            GenieDirection.DOWN -> fillGenieVerticesOptimized(
                verts = vertsBuffer,
                sourceLeft = sourceBounds.left,
                sourceTop = sourceBounds.top,
                sourceWidth = sourceBounds.width,
                sourceHeight = sourceBounds.height,
                destX = targetX,
                destY = targetY,
                progress = progress.coerceIn(0f, 1f),
                meshCols = GENIE_MESH_COLS,
                meshRows = GENIE_MESH_ROWS,
                screenHeight = screenHeight
            )
            GenieDirection.UP -> fillGenieVerticesUpwardOptimized(
                verts = vertsBuffer,
                sourceLeft = sourceBounds.left,
                sourceTop = sourceBounds.top,
                sourceWidth = sourceBounds.width,
                sourceHeight = sourceBounds.height,
                destX = targetX,
                destY = targetY,
                progress = progress.coerceIn(0f, 1f),
                meshCols = GENIE_MESH_COLS,
                meshRows = GENIE_MESH_ROWS,
                screenHeight = screenHeight
            )
        }
        
        // 透明度：最后15%开始淡出（更平滑的结束）
        val alpha = if (progress > 0.85f) {
            1f - (progress - 0.85f) / 0.15f
        } else {
            1f
        }
        
        // 更新 Paint 透明度
        paint.alpha = (alpha * 255).toInt()
        
        drawIntoCanvas { canvas ->
            // 最后一道防线：绘制前再检查一次
            if (!bitmap.isRecycled) {
                canvas.nativeCanvas.drawBitmapMesh(
                    bitmap,
                    GENIE_MESH_COLS,
                    GENIE_MESH_ROWS,
                    vertsBuffer,
                    0,
                    null,
                    0,
                    paint
                )
            }
        }
    }
}

/**
 * 填充 Genie 顶点数组（零内存分配版本 - 极致丝滑）
 * 
 * 直接修改传入的数组，避免每帧创建新数组
 * 使用 smootherstep 获得更丝滑的过渡
 */
private fun fillGenieVerticesOptimized(
    verts: FloatArray,
    sourceLeft: Float,
    sourceTop: Float,
    sourceWidth: Float,
    sourceHeight: Float,
    destX: Float,
    destY: Float,
    progress: Float,
    meshCols: Int,
    meshRows: Int,
    screenHeight: Float
) {
    val originalCenterX = sourceLeft + sourceWidth / 2
    
    // 使用更平滑的阶段过渡
    // curveRatio 降到 0.35，让收窄和平移更同步
    val curveRatio = 0.35f
    val rawCurveProgress = (progress / curveRatio).coerceIn(0f, 1f)
    val curveProgress = smootherstepFast(rawCurveProgress)  // 使用更丝滑的曲线
    
    // 平移从 15% 就开始，与收窄有更多重叠，感觉更自然
    val rawTranslationProgress = ((progress - curveRatio * 0.4f) / (1f - curveRatio * 0.4f)).coerceIn(0f, 1f)
    val translationProgress = smootherstepFast(rawTranslationProgress)
    
    val targetOffsetX = (destX - originalCenterX) / sourceWidth
    val clampedOffset = targetOffsetX.coerceIn(-0.48f, 0.48f)
    val finalCenterNorm = 0.5f + clampedOffset
    
    val totalTranslation = destY - sourceTop - sourceHeight
    
    // 最终收缩阶段提前到 80%，给更多时间完成收缩
    val isInFinalPhase = progress > 0.80f
    val finalSqueeze = if (isInFinalPhase) smootherstepFast((progress - 0.80f) / 0.20f) else 0f
    val finalSqueezePos = finalSqueeze * finalSqueeze
    val finalSqueezeY = finalSqueeze * (1f + finalSqueeze * 0.15f)
    val isComplete = progress >= 0.995f
    
    val leftMax = curveProgress * finalCenterNorm
    val leftD = leftMax / 2f
    val leftA = leftD
    val rightMin = 1f - curveProgress * (1f - finalCenterNorm)
    val rightD = (rightMin + 1f) / 2f
    val rightA = 1f - rightD
    
    var index = 0
    val rowsFloat = meshRows.toFloat()
    val colsFloat = meshCols.toFloat()
    
    for (row in 0..meshRows) {
        val rowRatio = row / rowsFloat
        val y = 1f - rowRatio
        
        val angleLeft = PI.toFloat() * y + PI.toFloat() / 2f
        val angleRight = PI.toFloat() * y - PI.toFloat() / 2f
        val sinValueLeft = fastSin(angleLeft)
        val sinValueRight = fastSin(angleRight)
        
        val leftNorm = leftA * sinValueLeft + leftD
        val rightNorm = rightA * sinValueRight + rightD
        
        var leftX = sourceLeft + leftNorm * sourceWidth
        var rightX = sourceLeft + rightNorm * sourceWidth
        
        val originalY = sourceTop + sourceHeight * rowRatio
        // 底部移动稍快一点，让吸入感更强
        val rowTranslationFactor = 1f + rowRatio * 0.35f
        val effectiveTranslation = translationProgress * totalTranslation * rowTranslationFactor
        var meshY = originalY + effectiveTranslation.coerceAtMost(destY - originalY)
        
        if (meshY >= destY - 1f) {
            meshY = destY
            val absorptionProgress = ((meshY - (originalY + effectiveTranslation * 0.8f)) / (destY - originalY)).coerceIn(0f, 1f)
            val shrinkFactor = 1f - smootherstepFast(absorptionProgress)
            val centerX = (leftX + rightX) * 0.5f
            leftX = centerX + (leftX - centerX) * shrinkFactor
            rightX = centerX + (rightX - centerX) * shrinkFactor
        }
        
        if (isInFinalPhase) {
            val centerX = (leftX + rightX) * 0.5f
            val halfWidth = (rightX - leftX) * 0.5f * (1f - finalSqueeze)
            leftX = lerp(centerX - halfWidth, destX, finalSqueezePos)
            rightX = lerp(centerX + halfWidth, destX, finalSqueezePos)
            meshY = lerp(meshY, destY, finalSqueezeY)
        }
        
        if (isComplete) {
            for (col in 0..meshCols) {
                verts[index++] = destX
                verts[index++] = destY
            }
        } else {
            for (col in 0..meshCols) {
                val colRatio = col / colsFloat
                verts[index++] = leftX + (rightX - leftX) * colRatio
                verts[index++] = meshY
            }
        }
    }
}

/**
 * 填充向上 Genie 顶点数组（零内存分配版本 - 极致丝滑）
 * 
 * 用于上滑删除动画
 */
private fun fillGenieVerticesUpwardOptimized(
    verts: FloatArray,
    sourceLeft: Float,
    sourceTop: Float,
    sourceWidth: Float,
    sourceHeight: Float,
    destX: Float,
    destY: Float,
    progress: Float,
    meshCols: Int,
    meshRows: Int,
    screenHeight: Float
) {
    val originalCenterX = sourceLeft + sourceWidth / 2
    
    // 使用更平滑的阶段过渡
    val curveRatio = 0.35f
    val rawCurveProgress = (progress / curveRatio).coerceIn(0f, 1f)
    val curveProgress = smootherstepFast(rawCurveProgress)
    
    val rawTranslationProgress = ((progress - curveRatio * 0.4f) / (1f - curveRatio * 0.4f)).coerceIn(0f, 1f)
    val translationProgress = smootherstepFast(rawTranslationProgress)
    
    val targetOffsetX = (destX - originalCenterX) / sourceWidth
    val clampedOffset = targetOffsetX.coerceIn(-0.48f, 0.48f)
    val finalCenterNorm = 0.5f + clampedOffset
    
    val totalTranslation = destY - sourceTop
    val isInFinalPhase = progress > 0.80f
    val finalSqueeze = if (isInFinalPhase) smootherstepFast((progress - 0.80f) / 0.20f) else 0f
    val finalSqueezePos = finalSqueeze * finalSqueeze
    val finalSqueezeY = finalSqueeze * (1f + finalSqueeze * 0.15f)
    val isComplete = progress >= 0.995f
    
    val leftMax = curveProgress * finalCenterNorm
    val leftD = leftMax / 2f
    val leftA = leftD
    val rightMin = 1f - curveProgress * (1f - finalCenterNorm)
    val rightD = (rightMin + 1f) / 2f
    val rightA = 1f - rightD
    
    var index = 0
    val rowsFloat = meshRows.toFloat()
    val colsFloat = meshCols.toFloat()
    
    for (row in 0..meshRows) {
        val rowRatio = row / rowsFloat
        val y = rowRatio
        
        val angleLeft = PI.toFloat() * y + PI.toFloat() / 2f
        val angleRight = PI.toFloat() * y - PI.toFloat() / 2f
        val sinValueLeft = fastSin(angleLeft)
        val sinValueRight = fastSin(angleRight)
        
        val leftNorm = leftA * sinValueLeft + leftD
        val rightNorm = rightA * sinValueRight + rightD
        
        var leftX = sourceLeft + leftNorm * sourceWidth
        var rightX = sourceLeft + rightNorm * sourceWidth
        
        val originalY = sourceTop + sourceHeight * rowRatio
        // 顶部移动稍快，让吸入感更强
        val rowTranslationFactor = 1f + (1f - rowRatio) * 0.35f
        val effectiveTranslation = translationProgress * totalTranslation * rowTranslationFactor
        var meshY = originalY + effectiveTranslation.coerceAtLeast(destY - originalY)
        
        if (meshY <= destY + 1f) {
            meshY = destY
            val absorptionProgress = ((originalY + effectiveTranslation * 0.8f - meshY) / (originalY - destY)).coerceIn(0f, 1f)
            val shrinkFactor = 1f - smootherstepFast(absorptionProgress)
            val centerX = (leftX + rightX) * 0.5f
            leftX = centerX + (leftX - centerX) * shrinkFactor
            rightX = centerX + (rightX - centerX) * shrinkFactor
        }
        
        if (isInFinalPhase) {
            val centerX = (leftX + rightX) * 0.5f
            val halfWidth = (rightX - leftX) * 0.5f * (1f - finalSqueeze)
            leftX = lerp(centerX - halfWidth, destX, finalSqueezePos)
            rightX = lerp(centerX + halfWidth, destX, finalSqueezePos)
            meshY = lerp(meshY, destY, finalSqueezeY)
        }
        
        if (isComplete) {
            for (col in 0..meshCols) {
                verts[index++] = destX
                verts[index++] = destY
            }
        } else {
            for (col in 0..meshCols) {
                val colRatio = col / colsFloat
                verts[index++] = leftX + (rightX - leftX) * colRatio
                verts[index++] = meshY
            }
        }
    }
}

/**
 * 计算 macOS 风格神奇效果的网格顶点 - 性能优化版本
 * 
 * 基于 https://daniate.github.io/2021/07/27/细说如何完美实现macOS中的神奇效果/
 * 
 * 优化策略：
 * 1. 使用快速正弦查找表替代 sin() 函数调用
 * 2. 减少 pow() 调用，使用简单乘法近似
 * 3. 预计算不变的值，减少循环内计算量
 * 4. 简化分支逻辑
 */
private fun calculateGenieVerticesOptimized(
    sourceLeft: Float,
    sourceTop: Float,
    sourceWidth: Float,
    sourceHeight: Float,
    destX: Float,
    destY: Float,
    progress: Float,
    meshCols: Int,
    meshRows: Int,
    screenHeight: Float
): FloatArray {
    val vertexCount = (meshCols + 1) * (meshRows + 1)
    val verts = FloatArray(vertexCount * 2)
    
    val originalCenterX = sourceLeft + sourceWidth / 2
    
    // ========== 预计算阶段进度（避免循环内重复计算）==========
    val curveRatio = 0.4f
    val rawCurveProgress = (progress / curveRatio).coerceIn(0f, 1f)
    val curveProgress = smoothstepFast(rawCurveProgress)
    
    val rawTranslationProgress = ((progress - curveRatio * 0.5f) / (1f - curveRatio * 0.5f)).coerceIn(0f, 1f)
    val translationProgress = smoothstepFast(rawTranslationProgress)
    
    // ========== 预计算收窄参数 ==========
    val targetOffsetX = (destX - originalCenterX) / sourceWidth
    val clampedOffset = targetOffsetX.coerceIn(-0.48f, 0.48f)
    val finalCenterNorm = 0.5f + clampedOffset
    
    // 预计算一些常用值
    val totalTranslation = destY - sourceTop - sourceHeight
    val isInFinalPhase = progress > 0.85f
    val finalSqueeze = if (isInFinalPhase) smoothstepFast((progress - 0.85f) / 0.15f) else 0f
    val finalSqueezePos = finalSqueeze * finalSqueeze  // 近似 pow(1.5f)
    val finalSqueezeY = finalSqueeze * (1f + finalSqueeze * 0.2f)  // 近似 pow(1.2f)
    val isComplete = progress >= 0.995f
    
    // 预计算左右边界的收窄参数
    val leftMax = curveProgress * finalCenterNorm
    val leftD = leftMax / 2f
    val leftA = leftD
    val rightMin = 1f - curveProgress * (1f - finalCenterNorm)
    val rightD = (rightMin + 1f) / 2f
    val rightA = 1f - rightD
    
    var index = 0
    val rowsFloat = meshRows.toFloat()
    val colsFloat = meshCols.toFloat()
    
    for (row in 0..meshRows) {
        val rowRatio = row / rowsFloat
        val y = 1f - rowRatio
        
        // ========== 使用快速正弦近似 ==========
        val angleLeft = PI.toFloat() * y + PI.toFloat() / 2f
        val angleRight = PI.toFloat() * y - PI.toFloat() / 2f
        val sinValueLeft = fastSin(angleLeft)
        val sinValueRight = fastSin(angleRight)
        
        val leftNorm = leftA * sinValueLeft + leftD
        val rightNorm = rightA * sinValueRight + rightD
        
        var leftX = sourceLeft + leftNorm * sourceWidth
        var rightX = sourceLeft + rightNorm * sourceWidth
        
        // ========== 向下吸收 ==========
        val originalY = sourceTop + sourceHeight * rowRatio
        val rowTranslationFactor = 1f + rowRatio * 0.3f
        val effectiveTranslation = translationProgress * totalTranslation * rowTranslationFactor
        var meshY = originalY + effectiveTranslation.coerceAtMost(destY - originalY)
        
        // 收缩已到达目标的行
        if (meshY >= destY - 1f) {
            meshY = destY
            val absorptionProgress = ((meshY - (originalY + effectiveTranslation * 0.8f)) / (destY - originalY)).coerceIn(0f, 1f)
            val shrinkFactor = 1f - smoothstepFast(absorptionProgress)
            val centerX = (leftX + rightX) * 0.5f
            leftX = centerX + (leftX - centerX) * shrinkFactor
            rightX = centerX + (rightX - centerX) * shrinkFactor
        }
        
        // ========== 最终收缩阶段 ==========
        if (isInFinalPhase) {
            val centerX = (leftX + rightX) * 0.5f
            val halfWidth = (rightX - leftX) * 0.5f * (1f - finalSqueeze)
            leftX = lerp(centerX - halfWidth, destX, finalSqueezePos)
            rightX = lerp(centerX + halfWidth, destX, finalSqueezePos)
            meshY = lerp(meshY, destY, finalSqueezeY)
        }
        
        // ========== 生成顶点 ==========
        if (isComplete) {
            for (col in 0..meshCols) {
                verts[index++] = destX
                verts[index++] = destY
            }
        } else {
            for (col in 0..meshCols) {
                val colRatio = col / colsFloat
                verts[index++] = leftX + (rightX - leftX) * colRatio
                verts[index++] = meshY
            }
        }
    }
    
    return verts
}

/**
 * 原版 calculateGenieVertices（保留作为参考）
 */
private fun calculateGenieVertices(
    sourceLeft: Float,
    sourceTop: Float,
    sourceWidth: Float,
    sourceHeight: Float,
    destX: Float,
    destY: Float,
    progress: Float,
    meshCols: Int,
    meshRows: Int,
    screenHeight: Float
): FloatArray {
    val vertexCount = (meshCols + 1) * (meshRows + 1)
    val verts = FloatArray(vertexCount * 2)
    
    val originalCenterX = sourceLeft + sourceWidth / 2
    
    // ========== 平滑的两阶段动画 ==========
    // 使用 smoothstep 让两阶段之间无缝过渡
    val curveRatio = 0.4f
    
    // 曲线收窄进度（使用 smoothstep 缓动）
    val rawCurveProgress = (progress / curveRatio).coerceIn(0f, 1f)
    val curveProgress = smoothstep(rawCurveProgress)
    
    // 向下平移进度（平滑启动）
    val rawTranslationProgress = ((progress - curveRatio * 0.5f) / (1f - curveRatio * 0.5f)).coerceIn(0f, 1f)
    val translationProgress = smoothstep(rawTranslationProgress)
    
    // ========== 收窄参数 ==========
    val targetOffsetX = (destX - originalCenterX) / sourceWidth
    val clampedOffset = targetOffsetX.coerceIn(-0.48f, 0.48f)
    val finalCenterNorm = 0.5f + clampedOffset
    
    var index = 0
    
    for (row in 0..meshRows) {
        val rowRatio = row.toFloat() / meshRows
        val y = 1f - rowRatio  // 反转坐标系
        
        // ========== 1. 优化的正弦曲线 ==========
        // 使用更平滑的曲线系数
        val leftMax = curveProgress * finalCenterNorm
        val leftD = leftMax / 2f
        val leftA = leftD
        // 使用 smoothed sin 让曲线更自然
        val sinValueLeft = sin(PI.toFloat() * y + PI.toFloat() / 2f)
        val leftNorm = leftA * sinValueLeft + leftD
        
        val rightMin = 1f - curveProgress * (1f - finalCenterNorm)
        val rightD = (rightMin + 1f) / 2f
        val rightA = 1f - rightD
        val sinValueRight = sin(PI.toFloat() * y - PI.toFloat() / 2f)
        val rightNorm = rightA * sinValueRight + rightD
        
        var leftX = sourceLeft + leftNorm * sourceWidth
        var rightX = sourceLeft + rightNorm * sourceWidth
        
        // ========== 2. 平滑的向下吸收 ==========
        val originalY = sourceTop + sourceHeight * rowRatio
        val totalTranslation = destY - sourceTop - sourceHeight
        
        // 使用非线性的平移，底部先到达
        val rowTranslationFactor = 1f + rowRatio * 0.3f  // 底部移动更快
        val effectiveTranslation = translationProgress * totalTranslation * rowTranslationFactor
        var meshY = originalY + effectiveTranslation.coerceAtMost(destY - originalY)
        
        // 平滑地收缩已到达目标的行
        if (meshY >= destY - 1f) {
            meshY = destY
            // 已吸收的行宽度收缩到0
            val absorptionProgress = ((meshY - (originalY + effectiveTranslation * 0.8f)) / (destY - originalY)).coerceIn(0f, 1f)
            val shrinkFactor = 1f - smoothstep(absorptionProgress)
            val centerX = (leftX + rightX) / 2f
            leftX = lerp(centerX, leftX, shrinkFactor)
            rightX = lerp(centerX, rightX, shrinkFactor)
        }
        
        // ========== 3. 极致平滑的最终收缩 ==========
        if (progress > 0.85f) {
            val squeeze = smoothstep((progress - 0.85f) / 0.15f)
            val centerX = (leftX + rightX) / 2f
            val currentWidth = rightX - leftX
            val targetWidth = currentWidth * (1f - squeeze)
            leftX = lerp(centerX - targetWidth / 2f, destX, squeeze.pow(1.5f))
            rightX = lerp(centerX + targetWidth / 2f, destX, squeeze.pow(1.5f))
            meshY = lerp(meshY, destY, squeeze.pow(1.2f))
        }
        
        // ========== 4. 生成顶点 ==========
        for (col in 0..meshCols) {
            val colRatio = col.toFloat() / meshCols
            val x = if (progress >= 0.995f) destX else lerp(leftX, rightX, colRatio)
            val finalY = if (progress >= 0.995f) destY else meshY
            
            verts[index++] = x
            verts[index++] = finalY
        }
    }
    
    return verts
}

/**
 * 计算向上吸入效果的网格顶点（下宽上窄）- 性能优化版本
 * 
 * 用于上滑删除动画，图片被吸向上方的回收站按钮
 * 与向下版本相反：底部保持原样，顶部收缩到目标点
 * 
 * 优化策略同 calculateGenieVerticesOptimized
 */
private fun calculateGenieVerticesUpwardOptimized(
    sourceLeft: Float,
    sourceTop: Float,
    sourceWidth: Float,
    sourceHeight: Float,
    destX: Float,
    destY: Float,
    progress: Float,
    meshCols: Int,
    meshRows: Int,
    screenHeight: Float
): FloatArray {
    val vertexCount = (meshCols + 1) * (meshRows + 1)
    val verts = FloatArray(vertexCount * 2)
    
    val originalCenterX = sourceLeft + sourceWidth / 2
    
    // ========== 预计算阶段进度 ==========
    val curveRatio = 0.4f
    val rawCurveProgress = (progress / curveRatio).coerceIn(0f, 1f)
    val curveProgress = smoothstepFast(rawCurveProgress)
    
    val rawTranslationProgress = ((progress - curveRatio * 0.5f) / (1f - curveRatio * 0.5f)).coerceIn(0f, 1f)
    val translationProgress = smoothstepFast(rawTranslationProgress)
    
    // ========== 预计算收窄参数 ==========
    val targetOffsetX = (destX - originalCenterX) / sourceWidth
    val clampedOffset = targetOffsetX.coerceIn(-0.48f, 0.48f)
    val finalCenterNorm = 0.5f + clampedOffset
    
    // 预计算一些常用值
    val totalTranslation = destY - sourceTop
    val isInFinalPhase = progress > 0.85f
    val finalSqueeze = if (isInFinalPhase) smoothstepFast((progress - 0.85f) / 0.15f) else 0f
    val finalSqueezePos = finalSqueeze * finalSqueeze
    val finalSqueezeY = finalSqueeze * (1f + finalSqueeze * 0.2f)
    val isComplete = progress >= 0.995f
    
    // 预计算左右边界的收窄参数
    val leftMax = curveProgress * finalCenterNorm
    val leftD = leftMax / 2f
    val leftA = leftD
    val rightMin = 1f - curveProgress * (1f - finalCenterNorm)
    val rightD = (rightMin + 1f) / 2f
    val rightA = 1f - rightD
    
    var index = 0
    val rowsFloat = meshRows.toFloat()
    val colsFloat = meshCols.toFloat()
    
    for (row in 0..meshRows) {
        val rowRatio = row / rowsFloat
        val y = rowRatio  // 不反转，顶部先收窄
        
        // ========== 使用快速正弦近似 ==========
        val angleLeft = PI.toFloat() * y + PI.toFloat() / 2f
        val angleRight = PI.toFloat() * y - PI.toFloat() / 2f
        val sinValueLeft = fastSin(angleLeft)
        val sinValueRight = fastSin(angleRight)
        
        val leftNorm = leftA * sinValueLeft + leftD
        val rightNorm = rightA * sinValueRight + rightD
        
        var leftX = sourceLeft + leftNorm * sourceWidth
        var rightX = sourceLeft + rightNorm * sourceWidth
        
        // ========== 向上吸收 ==========
        val originalY = sourceTop + sourceHeight * rowRatio
        val rowTranslationFactor = 1f + (1f - rowRatio) * 0.3f
        val effectiveTranslation = translationProgress * totalTranslation * rowTranslationFactor
        var meshY = originalY + effectiveTranslation.coerceAtLeast(destY - originalY)
        
        // 收缩已到达目标的行
        if (meshY <= destY + 1f) {
            meshY = destY
            val absorptionProgress = ((originalY + effectiveTranslation * 0.8f - meshY) / (originalY - destY)).coerceIn(0f, 1f)
            val shrinkFactor = 1f - smoothstepFast(absorptionProgress)
            val centerX = (leftX + rightX) * 0.5f
            leftX = centerX + (leftX - centerX) * shrinkFactor
            rightX = centerX + (rightX - centerX) * shrinkFactor
        }
        
        // ========== 最终收缩阶段 ==========
        if (isInFinalPhase) {
            val centerX = (leftX + rightX) * 0.5f
            val halfWidth = (rightX - leftX) * 0.5f * (1f - finalSqueeze)
            leftX = lerp(centerX - halfWidth, destX, finalSqueezePos)
            rightX = lerp(centerX + halfWidth, destX, finalSqueezePos)
            meshY = lerp(meshY, destY, finalSqueezeY)
        }
        
        // ========== 生成顶点 ==========
        if (isComplete) {
            for (col in 0..meshCols) {
                verts[index++] = destX
                verts[index++] = destY
            }
        } else {
            for (col in 0..meshCols) {
                val colRatio = col / colsFloat
                verts[index++] = leftX + (rightX - leftX) * colRatio
                verts[index++] = meshY
            }
        }
    }
    
    return verts
}

/**
 * 原版 calculateGenieVerticesUpward（保留作为参考）
 */
private fun calculateGenieVerticesUpward(
    sourceLeft: Float,
    sourceTop: Float,
    sourceWidth: Float,
    sourceHeight: Float,
    destX: Float,
    destY: Float,
    progress: Float,
    meshCols: Int,
    meshRows: Int,
    screenHeight: Float
): FloatArray {
    val vertexCount = (meshCols + 1) * (meshRows + 1)
    val verts = FloatArray(vertexCount * 2)
    
    val originalCenterX = sourceLeft + sourceWidth / 2
    
    // ========== 平滑的两阶段动画 ==========
    val curveRatio = 0.4f
    
    // 曲线收窄进度
    val rawCurveProgress = (progress / curveRatio).coerceIn(0f, 1f)
    val curveProgress = smoothstep(rawCurveProgress)
    
    // 向上平移进度
    val rawTranslationProgress = ((progress - curveRatio * 0.5f) / (1f - curveRatio * 0.5f)).coerceIn(0f, 1f)
    val translationProgress = smoothstep(rawTranslationProgress)
    
    // ========== 收窄参数 ==========
    val targetOffsetX = (destX - originalCenterX) / sourceWidth
    val clampedOffset = targetOffsetX.coerceIn(-0.48f, 0.48f)
    val finalCenterNorm = 0.5f + clampedOffset
    
    var index = 0
    
    for (row in 0..meshRows) {
        val rowRatio = row.toFloat() / meshRows  // 0=顶部, 1=底部
        
        // ========== 1. 向上版本的正弦曲线（顶部收窄，底部保持）==========
        // 使用 rowRatio 直接作为 y（不反转），让顶部（row=0）先收窄
        val y = rowRatio
        
        val leftMax = curveProgress * finalCenterNorm
        val leftD = leftMax / 2f
        val leftA = leftD
        val sinValueLeft = sin(PI.toFloat() * y + PI.toFloat() / 2f)
        val leftNorm = leftA * sinValueLeft + leftD
        
        val rightMin = 1f - curveProgress * (1f - finalCenterNorm)
        val rightD = (rightMin + 1f) / 2f
        val rightA = 1f - rightD
        val sinValueRight = sin(PI.toFloat() * y - PI.toFloat() / 2f)
        val rightNorm = rightA * sinValueRight + rightD
        
        var leftX = sourceLeft + leftNorm * sourceWidth
        var rightX = sourceLeft + rightNorm * sourceWidth
        
        // ========== 2. 向上吸收（顶部先到达目标）==========
        val originalY = sourceTop + sourceHeight * rowRatio
        val totalTranslation = destY - sourceTop  // 向上移动（destY < sourceTop）
        
        // 顶部移动更快（rowRatio 越小，移动越快）
        val rowTranslationFactor = 1f + (1f - rowRatio) * 0.3f
        val effectiveTranslation = translationProgress * totalTranslation * rowTranslationFactor
        var meshY = originalY + effectiveTranslation.coerceAtLeast(destY - originalY)
        
        // 平滑地收缩已到达目标的行
        if (meshY <= destY + 1f) {
            meshY = destY
            val absorptionProgress = ((originalY + effectiveTranslation * 0.8f - meshY) / (originalY - destY)).coerceIn(0f, 1f)
            val shrinkFactor = 1f - smoothstep(absorptionProgress)
            val centerX = (leftX + rightX) / 2f
            leftX = lerp(centerX, leftX, shrinkFactor)
            rightX = lerp(centerX, rightX, shrinkFactor)
        }
        
        // ========== 3. 极致平滑的最终收缩 ==========
        if (progress > 0.85f) {
            val squeeze = smoothstep((progress - 0.85f) / 0.15f)
            val centerX = (leftX + rightX) / 2f
            val currentWidth = rightX - leftX
            val targetWidth = currentWidth * (1f - squeeze)
            leftX = lerp(centerX - targetWidth / 2f, destX, squeeze.pow(1.5f))
            rightX = lerp(centerX + targetWidth / 2f, destX, squeeze.pow(1.5f))
            meshY = lerp(meshY, destY, squeeze.pow(1.2f))
        }
        
        // ========== 4. 生成顶点 ==========
        for (col in 0..meshCols) {
            val colRatio = col.toFloat() / meshCols
            val x = if (progress >= 0.995f) destX else lerp(leftX, rightX, colRatio)
            val finalY = if (progress >= 0.995f) destY else meshY
            
            verts[index++] = x
            verts[index++] = finalY
        }
    }
    
    return verts
}

/**
 * Smoothstep 缓动函数 - 极致平滑的 S 形曲线
 * 比 easeInOutQuad 更平滑，没有突变
 */
private fun smoothstep(t: Float): Float {
    val x = t.coerceIn(0f, 1f)
    return x * x * (3f - 2f * x)
}

/**
 * 快速 Smoothstep（内联优化版）
 * 假设输入已经在 0-1 范围内，跳过 coerceIn 检查
 */
@Suppress("NOTHING_TO_INLINE")
private inline fun smoothstepFast(t: Float): Float {
    return t * t * (3f - 2f * t)
}

/**
 * 快速 Smootherstep（Ken Perlin 改进版 - 内联优化）
 * 比 smoothstep 更丝滑，加速和减速都更柔和
 */
@Suppress("NOTHING_TO_INLINE")
private inline fun smootherstepFast(t: Float): Float {
    return t * t * t * (t * (t * 6f - 15f) + 10f)
}

/**
 * 更平滑的 Smootherstep（Ken Perlin 改进版）
 */
private fun smootherstep(t: Float): Float {
    val x = t.coerceIn(0f, 1f)
    return x * x * x * (x * (x * 6f - 15f) + 10f)
}

/**
 * 线性插值
 */
private fun lerp(start: Float, end: Float, fraction: Float): Float {
    return start + (end - start) * fraction
}

/**
 * 缓动函数 - 加速（先慢后快）
 */
private fun easeInQuad(t: Float): Float = t * t

/**
 * 缓动函数 - 先加速后减速（平滑过渡）
 */
private fun easeInOutQuad(t: Float): Float {
    return if (t < 0.5f) {
        2f * t * t
    } else {
        1f - (-2f * t + 2f).pow(2f) / 2f
    }
}

/**
 * T06 剩余场景（2026-11-14）：设备测试专用 genie 删除动画控制钩子（仅 DEBUG 生效）。
 *
 * 目的：T06RemainingDeviceTest 场景 c（DRAW 删除动画中 BACK 退出）需要确定性注入
 * 窗口——真实手势时序无法保证 BACK 稳定落在 440ms genie 动画窗内（宿主负载抖动，
 * 后验日志只能证 dispose.ts >= dragEnd.ts）。本钩子使删除动画在**已启动（isAnimating
 * =true、progress=0）且未完成**处挂起，测试在其后任意时刻注入 BACK 均落于该窗口。
 *
 * 语义与生产取消契约等价：挂起点是 startAnimation 内 onComplete 之前的 suspension
 * point——组合销毁取消协程时 delay 抛 CancellationException，onComplete 不执行
 * （删除不提交），与生产 BACK 取消（取消于 animateTo 期间）的可观测契约一致。
 *
 * 消费即一次性：arm 绑定照片 id；DRAW executeDeleteAnimation 命中同一 id 时置位；
 * 下一次 startAnimation（即同一次删除的 genie 启动，同线程紧随）消费置位并挂起，
 * 消费同时解除 id 绑定（重启后的后续删除动画实例不受影响）；挂起无释放 API——
 * 仅组合销毁的协程取消可结束，杜绝"释放竞态完成动画"。
 *
 * Release 构建：armForPhoto/onTarget/takeHold 三入口全部 BuildConfig.DEBUG 门，
 * Release 直接返回 false/null，生产行为零变化。
 */
object GenieDeleteAnimTestHook {
    @Volatile private var armedImgId: Long? = null
    @Volatile internal var holdNextStart: Boolean = false

    /** 测试入口：绑定目标照片 id（下一次 DRAW 删除动画命中该 id 即挂起）。 */
    fun armForPhoto(imgId: Long) {
        if (!BuildConfig.DEBUG) return
        armedImgId = imgId
        holdNextStart = false
    }

    /** DRAW executeDeleteAnimation 调用（调用点已 DEBUG 门）：命中绑定 id 则置位挂起。 */
    internal fun onTarget(imgId: Long) {
        if (!BuildConfig.DEBUG) return
        if (armedImgId == imgId) holdNextStart = true
    }

    /** startAnimation 调用（consume-once）：返回是否挂起本次动画启动；消费即解除绑定。 */
    internal fun takeHold(): Boolean {
        if (!BuildConfig.DEBUG) return false
        val armed = holdNextStart
        holdNextStart = false
        if (armed) armedImgId = null
        return armed
    }
}

/**
 * Genie动画控制器 - 极致丝滑版本
 *
 * 优化策略：
 * 1. 使用 Apple 风格的 ease-out 贝塞尔曲线
 * 2. 每帧更新进度，保证 60fps 流畅渲染
 * 3. 适当的动画时长（不要太快也不要太慢）
 */
class GenieAnimationController {
    var isAnimating by mutableStateOf(false)
        private set
    
    var progress by mutableFloatStateOf(0f)
        private set
    
    var targetX by mutableFloatStateOf(0f)
        private set
    
    var targetY by mutableFloatStateOf(0f)
        private set
    
    var bitmap by mutableStateOf<Bitmap?>(null)
        private set
    
    var sourceBounds by mutableStateOf(Rect.Zero)
        private set
    
    var screenHeight by mutableFloatStateOf(2000f)
        private set
    
    var direction by mutableStateOf(GenieDirection.DOWN)
        private set
    
    private val animatable = Animatable(0f)
    
    suspend fun startAnimation(
        bitmap: Bitmap,
        sourceBounds: Rect,
        targetX: Float,
        targetY: Float,
        screenHeight: Float = 2000f,
        direction: GenieDirection = GenieDirection.DOWN,
        durationMs: Int = 320,  // 320ms - 快速但不急促
        onComplete: () -> Unit = {}
    ) {
        this.bitmap = bitmap
        this.sourceBounds = sourceBounds
        this.targetX = targetX
        this.targetY = targetY
        this.screenHeight = screenHeight
        this.direction = direction
        this.isAnimating = true
        this.progress = 0f

        animatable.snapTo(0f)
        // T06 剩余场景（2026-11-14）：DEBUG 门测试挂起（GenieDeleteAnimTestHook，KDoc
        // 见上）——动画已启动（isAnimating=true）后、animateTo 前挂起，仅组合销毁的
        // 协程取消可结束（onComplete 因此不执行）；Release 构建 takeHold() 恒 false。
        if (GenieDeleteAnimTestHook.takeHold()) {
            Log.d("CardStackDebug", "deleteAnimHold genie=${System.identityHashCode(this)}")
            while (true) delay(20)
        }
        animatable.animateTo(
            targetValue = 1f,
            animationSpec = tween(
                durationMillis = durationMs,
                // 极致丝滑的 ease-out 曲线（优化版）
                // 灵感来自 iOS 17 的弹性动画，开始快、结束缓慢平滑
                // 控制点 (0.22, 1.0, 0.36, 1.0) 比原来更柔和
                easing = CubicBezierEasing(0.2f, 0.9f, 0.25f, 1f)
            )
        ) {
            // 每帧更新进度，保证动画流畅
            progress = value
        }
        
        onComplete()
        reset()
    }
    
    fun reset() {
        isAnimating = false
        progress = 0f
        direction = GenieDirection.DOWN
        // 先保存引用并清空，再 recycle，避免 Compose 重组时访问已 recycle 的 bitmap
        val oldBitmap = bitmap
        bitmap = null
        oldBitmap?.recycle()
    }
}

@Composable
fun rememberGenieAnimationController(): GenieAnimationController {
    return remember { GenieAnimationController() }
}

/**
 * 从ImageFile创建用于Genie效果的缩略图Bitmap
 * 使用较小的目标尺寸以提高加载速度
 */
suspend fun createGenieBitmap(
    context: android.content.Context,
    imageUri: android.net.Uri,
    width: Int,
    height: Int
): Bitmap? {
    return try {
        // 限制最大尺寸为 400px，提高加载速度
        val maxSize = 400
        val scale = minOf(maxSize.toFloat() / width, maxSize.toFloat() / height, 1f)
        val targetWidth = (width * scale).toInt().coerceAtLeast(100)
        val targetHeight = (height * scale).toInt().coerceAtLeast(100)
        
        val options = android.graphics.BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        
        context.contentResolver.openInputStream(imageUri)?.use { input ->
            android.graphics.BitmapFactory.decodeStream(input, null, options)
        }
        
        // 使用更激进的采样以提高速度
        val sampleSize = calculateInSampleSize(options, targetWidth, targetHeight)
        
        val decodeOptions = android.graphics.BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.RGB_565  // 使用更小的颜色深度
        }
        
        context.contentResolver.openInputStream(imageUri)?.use { input ->
            android.graphics.BitmapFactory.decodeStream(input, null, decodeOptions)
        }?.let { bitmap ->
            // 缩放到目标尺寸
            Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true).also {
                if (it != bitmap) bitmap.recycle()
            }
        }
    } catch (e: Exception) {
        null
    }
}

/**
 * 快速创建 Genie Bitmap - 专为避免 Motion Photo 视频解码设计
 * 
 * 性能优化：
 * 1. 直接使用 BitmapFactory 解码，绕过 Coil 的 Motion Photo 处理
 * 2. 使用激进的采样率，快速解码大尺寸 HDR 图片
 * 3. 使用 RGB_565 格式减少内存和解码时间
 * 4. 限制最大尺寸，Genie 动画不需要高分辨率
 */
fun createGenieBitmapFast(
    context: android.content.Context,
    imageUri: android.net.Uri,
    width: Int,
    height: Int
): Bitmap? {
    return try {
        // Genie 动画目标尺寸限制为 250px，足够显示效果且加载快速
        val maxSize = 250
        val targetWidth = width.coerceAtMost(maxSize)
        val targetHeight = height.coerceAtMost(maxSize)
        
        // 第一步：只读取图片尺寸，不解码
        val boundsOptions = android.graphics.BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        
        context.contentResolver.openInputStream(imageUri)?.use { input ->
            android.graphics.BitmapFactory.decodeStream(input, null, boundsOptions)
        }
        
        // 如果无法读取尺寸，返回 null
        if (boundsOptions.outWidth <= 0 || boundsOptions.outHeight <= 0) {
            return null
        }
        
        // 计算采样率 - 对于大图片使用更激进的采样
        val sampleSize = calculateInSampleSizeFast(
            boundsOptions.outWidth, 
            boundsOptions.outHeight, 
            targetWidth, 
            targetHeight
        )
        
        // 第二步：使用采样率解码
        val decodeOptions = android.graphics.BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.RGB_565  // 16位色深，节省内存和解码时间
            inDither = false  // 禁用抖动，加快解码
            inPurgeable = false
            inInputShareable = false
        }
        
        val bitmap = context.contentResolver.openInputStream(imageUri)?.use { input ->
            android.graphics.BitmapFactory.decodeStream(input, null, decodeOptions)
        }
        
        // 如果解码后尺寸仍然过大，进行缩放
        bitmap?.let { bmp ->
            if (bmp.width > targetWidth * 1.5 || bmp.height > targetHeight * 1.5) {
                val scaled = Bitmap.createScaledBitmap(bmp, targetWidth, targetHeight, false)
                if (scaled != bmp) bmp.recycle()
                scaled
            } else {
                bmp
            }
        }
    } catch (e: Exception) {
        null
    }
}

/**
 * 快速计算采样率 - 针对大图片优化
 * 
 * 对于超大图片（如 HDR 4K+ 照片），使用更激进的采样率
 */
private fun calculateInSampleSizeFast(
    srcWidth: Int,
    srcHeight: Int,
    reqWidth: Int,
    reqHeight: Int
): Int {
    // 计算源图片的最大边
    val srcMaxDimension = maxOf(srcWidth, srcHeight)
    
    // 计算宽高比
    val widthRatio = srcWidth / reqWidth
    val heightRatio = srcHeight / reqHeight
    
    // 取较大的比例，确保结果不超过目标尺寸
    var ratio = maxOf(widthRatio, heightRatio)
    
    // 对于超大图片（如 4K HDR），额外增加采样率以加速解码
    // 4K = 3840x2160，8K = 7680x4320
    if (srcMaxDimension > 4000) {
        ratio = maxOf(ratio, 8)  // 至少 8 倍采样
    } else if (srcMaxDimension > 2000) {
        ratio = maxOf(ratio, 4)  // 至少 4 倍采样
    }
    
    // 转换为 2 的幂次（BitmapFactory 优化）
    return when {
        ratio >= 32 -> 32
        ratio >= 16 -> 16
        ratio >= 8 -> 8
        ratio >= 4 -> 4
        ratio >= 2 -> 2
        else -> 1
    }
}

private fun calculateInSampleSize(
    options: android.graphics.BitmapFactory.Options,
    reqWidth: Int,
    reqHeight: Int
): Int {
    val (height: Int, width: Int) = options.run { outHeight to outWidth }
    var inSampleSize = 1

    if (height > reqHeight || width > reqWidth) {
        val halfHeight: Int = height / 2
        val halfWidth: Int = width / 2

        while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
            inSampleSize *= 2
        }
    }

    return inSampleSize
}
