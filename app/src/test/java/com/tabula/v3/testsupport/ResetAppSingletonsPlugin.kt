package com.tabula.v3.testsupport

import org.robolectric.RuntimeEnvironment
import org.robolectric.pluginapi.TestEnvironmentLifecyclePlugin

/**
 * 测试基建：每个测试方法建立应用状态前复位应用层单例 static。
 *
 * 根因（PR80 轮 1/2 CI 红的独立咨询定案，2026-11-15）：Robolectric 4.12.2 为
 * 每个测试方法新建 filesDir（TempDirectory 名含 Class_method），但 sandbox
 * classloader 按 (sdk,config) 缓存复用——应用类 static 跨方法、跨类存活。
 * `AppPreferences.getInstance` 等单例把首个调用者的 context 固化进 static，
 * 后续方法全部拿到指向陈旧 filesDir 的实例：先跑的类若经任意路径触碰
 * getInstance（包括被测生产入口的构造器内部，如 AlbumCleanupEngine.kt:133-134），
 * 后跑类的"陈旧单例写 + 当前目录读"即分裂（AlbumCleanupForegroundRefreshTest
 * .directRecycleClear… :177 实证）。生产进程单实例 application 终身绑定，不受
 * 影响——本插件只修复测试环境的隔离缺口。
 *
 * 机制（字节码序实证）：`onSetupApplicationState()` 在 clearEnvironment 之前
 * 被调——`RuntimeEnvironment.getApplication()` 返回**上一个测试方法**的
 * Application，其 classLoader 即 sandbox classloader；经它 loadClass 定位持有
 * static 的类并置空 `instance`。Kotlin `companion object { @Volatile var instance }`
 * 编译为外层类静态字段（javap 实证；对 Companion 反射会找不到）。
 *
 * 纪律：本清单需随新增应用层单例维护；首个测试方法时 application 尚不存在，
 * 整体 try/catch 兜底为 no-op（无东西可清）。shadow @Resetter 路线在 4.12.2
 * 对自定义 app-class shadow 不触发（实验否定，勿走回头路）。
 */
class ResetAppSingletonsPlugin : TestEnvironmentLifecyclePlugin {
    override fun onSetupApplicationState() {
        try {
            val app = RuntimeEnvironment.getApplication() ?: return
            val loader = app.javaClass.classLoader ?: return
            for (name in listOf(
                "com.tabula.v3.data.preferences.AppPreferences",
                "com.tabula.v3.data.cache.ImageHashCache",
                "com.tabula.v3.data.repository.AlbumCleanupEngine",
                "com.tabula.v3.data.repository.AlbumManager",
                "com.tabula.v3.data.repository.AlbumUsageTracker",
                "com.tabula.v3.data.repository.RecommendationEngine",
                "com.tabula.v3.data.repository.RecycleBinManager"
            )) {
                try {
                    val c = loader.loadClass(name)
                    val f = c.getDeclaredField("instance")
                    f.isAccessible = true
                    f.set(null, null)
                } catch (_: Throwable) {
                }
            }
        } catch (_: Throwable) {
        }
    }
}
