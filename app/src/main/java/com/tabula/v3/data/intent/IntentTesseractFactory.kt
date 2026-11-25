package com.tabula.v3.data.intent

import android.content.Context

/**
 * 进程内共享的本地 OCR 引擎。
 *
 * 为什么要单例：Tesseract 的 `init`（加载 2.4MB 模型、建内部结构）比识别单张图贵一个量级，
 * 每次进页面都重建会让第一批变得很慢。这里只缓存一个实例，进程结束随 GC 释放；
 * 显式 [shutdown] 供测试或将来「换语言包」使用。
 *
 * 抽成工厂而不是直接 `remember { TesseractTextRecognizer(context) }`：
 * 设备测试与真机可用同一个入口，将来换引擎也只改这一个文件。
 */
object IntentTesseractFactory {

    @Volatile
    private var instance: TextRecognizer? = null

    fun get(context: Context): TextRecognizer =
        instance ?: synchronized(this) {
            instance ?: TesseractTextRecognizer(context.applicationContext).also { instance = it }
        }

    /** 仅测试用：替换成假实现，或在不重启进程的前提下换回真实现。 */
    fun override(recognizer: TextRecognizer?) {
        synchronized(this) {
            instance?.close()
            instance = recognizer
        }
    }

    fun shutdown() = override(null)
}
