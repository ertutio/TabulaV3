package com.tabula.v3.ui.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.tabula.v3.data.model.ImageFeatures
import com.tabula.v3.data.model.ImageFile
import com.tabula.v3.data.repository.ImageFeatureDetector

@Composable
fun rememberImageFeatures(
    image: ImageFile,
    enableHdr: Boolean,
    enableMotion: Boolean
): ImageFeatures? {
    val context = LocalContext.current
    
    // T11a：缓存键含内容版本（DATE_MODIFIED 毫秒）与开关组合；不可信阴性不入缓存。
    val cacheKey = remember(image.id, image.dateModified, enableHdr, enableMotion) {
        ImageFeatureCacheKey(image.id, image.dateModified, enableHdr, enableMotion)
    }

    // 先从缓存获取，避免初始值为 null 导致闪烁
    val cachedFeatures = remember(cacheKey) {
        ImageFeatureCacheStore.get(cacheKey)
    }
    
    var features by remember(cacheKey) {
        mutableStateOf(cachedFeatures)
    }

    LaunchedEffect(cacheKey) {
        if (!enableHdr && !enableMotion) {
            features = null
            return@LaunchedEffect
        }

        // T11b single-flight：同键并发只跑一次检测，等待者直接共享该轮结果
        // （含不可信阴性，PR60 修复一）；不可信阴性（快速模式/信号量繁忙/失败）
        // 不入可靠缓存，留待重试。
        features = ImageFeatureCacheStore.getOrDetect(cacheKey) {
            ImageFeatureDetector.detect(
                context = context,
                uri = image.uri,
                checkHdr = enableHdr,
                checkMotion = enableMotion
            )
        }
    }

    return features
}

/**
 * 预加载图片特征（用于提前检测即将显示的图片）
 */
suspend fun preloadImageFeatures(
    context: android.content.Context,
    image: ImageFile,
    enableHdr: Boolean,
    enableMotion: Boolean
) {
    if (!enableHdr && !enableMotion) return

    val cacheKey = ImageFeatureCacheKey(image.id, image.dateModified, enableHdr, enableMotion)
    // T11b single-flight：已缓存/在途则共享（等待者直接拿到该轮结果），不再重复检测
    ImageFeatureCacheStore.getOrDetect(cacheKey) {
        ImageFeatureDetector.detect(
            context = context,
            uri = image.uri,
            checkHdr = enableHdr,
            checkMotion = enableMotion
        )
    }
}
