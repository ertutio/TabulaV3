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
 * 失败语义（2026-11-15 收口第二轮 §二 加固）：**清单漂移必须显式失败**——
 * 应用存在但目标类加载失败/`instance` 字段缺失/置空抛错时抛
 * IllegalStateException（吞掉=隔离假象：陈旧单例继续存活而测试全绿）。
 * 唯一合法 no-op = `getApplication()==null`（首个测试方法，尚无可清对象）。
 * shadow @Resetter 路线在 4.12.2 对自定义 app-class shadow 不触发（实验
 * 否定，勿走回头路）。清单完整性另有守卫测试钉住
 * （ResetAppSingletonsPluginGuardTest——字段改名/类移动会在那里以普通测试
 * 失败形式暴露）。
 */
class ResetAppSingletonsPlugin : TestEnvironmentLifecyclePlugin {
    override fun onSetupApplicationState() {
        val app = RuntimeEnvironment.getApplication() ?: return
        val loader = app.javaClass.classLoader
            ?: throw IllegalStateException("ResetAppSingletonsPlugin: sandbox classLoader 不可用（隔离缺口未修复）")
        for (name in SINGLETON_CLASSES) {
            val c = try {
                loader.loadClass(name)
            } catch (e: Throwable) {
                throw IllegalStateException(
                    "ResetAppSingletonsPlugin: 单例类加载失败 $name（类被移动/删除未同步 SINGLETON_CLASSES 清单）", e
                )
            }
            val f = try {
                c.getDeclaredField("instance")
            } catch (e: Throwable) {
                throw IllegalStateException(
                    "ResetAppSingletonsPlugin: 单例字段缺失 $name.instance（字段改名/移动未同步清单——陈旧单例将跨方法存活）", e
                )
            }
            f.isAccessible = true
            try {
                f.set(null, null)
            } catch (e: Throwable) {
                throw IllegalStateException("ResetAppSingletonsPlugin: 置空失败 $name.instance", e)
            }
        }
    }

    companion object {
        /** 应用层单例清单（外层类持有 `private static volatile instance`）。 */
        val SINGLETON_CLASSES = listOf(
            "com.tabula.v3.data.preferences.AppPreferences",
            "com.tabula.v3.data.cache.ImageHashCache",
            "com.tabula.v3.data.repository.AlbumCleanupEngine",
            "com.tabula.v3.data.repository.AlbumManager",
            "com.tabula.v3.data.repository.AlbumUsageTracker",
            "com.tabula.v3.data.repository.RecommendationEngine",
            "com.tabula.v3.data.repository.RecycleBinManager"
        )
    }
}
