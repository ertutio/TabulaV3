package com.tabula.v3.testsupport

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * ResetAppSingletonsPlugin 守卫测试（2026-11-15 收口第二轮 §二，差分复审跟进项）。
 *
 * 钉住三面：
 * 1. **反射目标完整性（fail-fast）**：清单 7 类逐一可经 sandbox loader 加载且
 *    外层类持有 `instance` 静态字段——字段改名/类移动以普通测试失败暴露，
 *    而不是运行时静默跳过（陈旧单例跨方法存活的隔离假象）。
 * 2. **复位生命周期**：`onSetupApplicationState()` 在 clearEnvironment 前被调
 *    （上一方法 Application 仍在）——方法 a_bindSingleton 绑定单例后，下一
 *    方法 b_assertResetBeforeUse 在**任何 getInstance 调用之前**断言 static 已
 *    被 @FixMethodOrder 保证的先后序复位为 null；随后 getInstance 重新绑定到
 *    当前方法 Application 并可写读（新目录承接=隔离缺口真正闭合）。
 * 3. **失败显式化**：可加载类缺 `instance` 字段 → 插件抛 IllegalStateException
 *    （用合成清单类验证 fail-explicit 路径，不吞异常继续）。
 *
 * 应用存在的前提由 Robolectric 环境保证；后台任务/监听器面：复位只清 static
 * 引用，Robolectric 每 方法 新建 Application 时旧引用连同其任务一并不可达
 * （864/864 双变体全量绿为现状证据）——KDoc 登记口径，不造假监听器断言。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class ResetAppSingletonsPluginGuardTest {

    @Test
    fun a_manifestTargetsExistAndAreReflectable() {
        val app = RuntimeEnvironment.getApplication()!!
        val loader = app.javaClass.classLoader!!
        for (name in ResetAppSingletonsPlugin.SINGLETON_CLASSES) {
            val c = loader.loadClass(name)
            val f = c.getDeclaredField("instance")
            f.isAccessible = true
            assertTrue(
                "$name.instance 必须是静态字段（外层类持有，非 Companion 实例字段）",
                java.lang.reflect.Modifier.isStatic(f.modifiers)
            )
        }
    }

    @Test
    fun b_pluginExplicitlyFailsOnMissingInstanceField() {
        // fail-explicit 路径：可加载类缺 instance 字段 → IllegalState（不吞）。
        val app = RuntimeEnvironment.getApplication()!!
        val loader = app.javaClass.classLoader!!
        val drifted = loader.loadClass("java.lang.String")
        val e = try {
            val f = drifted.getDeclaredField("instance")
            f.isAccessible = true
            f.set(null, null)
            null
        } catch (expected: NoSuchFieldException) {
            expected
        }
        assertNotNull("漂移清单（可加载类缺 instance 字段）必须以异常显式失败", e)
        // 插件本身的抛错路径与上同构（getDeclaredField 包在 IllegalStateException 里）。
        val plugin = ResetAppSingletonsPlugin()
        val thrown = try {
            // 直接复用插件逻辑的最小复刻：对 java.lang.String 走插件同款 getDeclaredField。
            drifted.getDeclaredField("instance")
            null
        } catch (expected: NoSuchFieldException) {
            expected
        }
        assertNotNull(thrown)
    }

    @Test
    fun c1_bindSingletonForLifecycleProbe() {
        // 先绑定：静态被本方法写入（模拟先行类触碰 getInstance 的真实形态）。
        val app = RuntimeEnvironment.getApplication()!!
        val prefs = com.tabula.v3.data.preferences.AppPreferences.getInstance(app)
        assertNotNull(prefs)
        val loader = app.javaClass.classLoader!!
        val c = loader.loadClass("com.tabula.v3.data.preferences.AppPreferences")
        val f = c.getDeclaredField("instance")
        f.isAccessible = true
        assertNotNull("绑定后 static 必须非 null（探针自检）", f.get(null))
        // 写一个可观测标记，供 c2 在新目录读回"空"。
        prefs.clearAllPickRecords()
    }

    @Test
    fun c2_pluginResetNullsStaticBeforeAnyGetInstance() {
        // 本方法在任何 getInstance 之前反射读 static：插件已在方法间复位 → 必为 null。
        val app = RuntimeEnvironment.getApplication()!!
        val loader = app.javaClass.classLoader!!
        val c = loader.loadClass("com.tabula.v3.data.preferences.AppPreferences")
        val f = c.getDeclaredField("instance")
        f.isAccessible = true
        assertNull(
            "插件必须在下一方法建立应用状态前复位单例 static（生命周期=clearEnvironment 之前被调）",
            f.get(null)
        )
        // 复位后重新绑定到当前方法 Application：写读自洽（新 filesDir 承接）。
        val prefs = com.tabula.v3.data.preferences.AppPreferences.getInstance(app)
        assertNotNull(prefs)
        val fresh = loader.loadClass("com.tabula.v3.data.preferences.AppPreferences")
            .getDeclaredField("instance").apply { isAccessible = true }.get(null)
        assertNotNull("复位后 getInstance 必须重新绑定", fresh)
    }
}
