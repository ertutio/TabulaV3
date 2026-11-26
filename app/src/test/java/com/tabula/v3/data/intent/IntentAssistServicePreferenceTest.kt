package com.tabula.v3.data.intent

import android.content.Context
import com.tabula.v3.data.preferences.AppPreferences
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 服务选择的两处**落盘/落文件**契约：
 *
 * 1. `AppPreferences.intentAssistService` 跨进程重启仍在（存枚举 name，不存序号）；
 * 2. `IntentAssistKeyStore(context, service)` 真的把 Key 写进该服务自己的文件，
 *    文件名与 #95 的 `intent-assist.key` 兼容，不会把老用户的 Key 弄丢。
 *
 * 这两条都是「静默出错」型风险：写错位置或读错文件时界面仍然工作，
 * 只有真发请求那一刻才会暴露。因此单独用 Robolectric 的 Context 断言真实路径。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class IntentAssistServicePreferenceTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Test
    fun serviceChoiceDefaultsToDeepSeekAndSurvivesRestart() {
        val prefs = AppPreferences(context)
        // 首次安装：必须是 DeepSeek —— 与 #95 完全一致，老用户升级后行为不变
        assertEquals(IntentAssistService.DEEPSEEK, prefs.intentAssistService)

        prefs.intentAssistService = IntentAssistService.OPENCODE_GO
        // 新建一个实例 = 模拟重启：值必须还在
        assertEquals(IntentAssistService.OPENCODE_GO, AppPreferences(context).intentAssistService)

        prefs.intentAssistService = IntentAssistService.DEEPSEEK
        assertEquals(IntentAssistService.DEEPSEEK, AppPreferences(context).intentAssistService)
    }

    @Test
    fun unknownStoredNameFallsBackToDefault() {
        // 手工塞一个未知值（降级安装、旧版本残留）：读出来必须是默认，而不是抛出
        context.getSharedPreferences("tabula_prefs", Context.MODE_PRIVATE)
            .edit()
            .putString("intent_assist_service", "SOME_FUTURE_SERVICE")
            .commit()
        assertEquals(IntentAssistService.DEEPSEEK, AppPreferences(context).intentAssistService)
    }

    @Test
    fun keyFilesLiveSideBySideInNoBackupDir() {
        val deepSeek = IntentAssistKeyStore(context, IntentAssistService.DEEPSEEK)
        val go = IntentAssistKeyStore(context, IntentAssistService.OPENCODE_GO)
        deepSeek.delete()
        go.delete()

        assertTrue(deepSeek.write("sk-synthetic-deepseek"))
        assertTrue(go.write("oc_sk_synthetic_go"))

        val deepSeekFile = File(context.noBackupFilesDir, "intent-assist.key")
        val goFile = File(context.noBackupFilesDir, "intent-assist.opencode-go.key")
        assertTrue("DeepSeek 的 Key 必须仍在 #95 的文件名上", deepSeekFile.exists())
        assertTrue("OpenCode Go 用独立文件", goFile.exists())
        assertTrue(
            "两个 Key 必须落在 noBackupFilesDir（不参与备份/迁移）",
            deepSeekFile.parentFile == context.noBackupFilesDir &&
                goFile.parentFile == context.noBackupFilesDir
        )

        // 内容互不串台
        assertEquals("sk-synthetic-deepseek", deepSeekFile.readText())
        assertEquals("oc_sk_synthetic_go", goFile.readText())

        // 删一个不碰另一个
        assertTrue(go.delete())
        assertFalse(goFile.exists())
        assertTrue("删 Go 的 Key 不得影响 DeepSeek 的文件", deepSeekFile.exists())
        assertTrue(deepSeek.hasKey())

        deepSeek.delete()
    }

    @Test
    fun defaultConstructorStillReadsTheLegacyFile() {
        // 关键回归：`IntentAssistKeyStore(context)` 不传服务时，读写的必须是 #95 的文件
        val legacyStyle = IntentAssistKeyStore(context)
        legacyStyle.delete()
        assertTrue(legacyStyle.write("sk-legacy-style"))
        val file = File(context.noBackupFilesDir, IntentAssistKeyStore.FILE_NAME)
        assertTrue("不传服务的构造必须仍写 intent-assist.key", file.exists())
        assertEquals(IntentAssistService.DEEPSEEK, legacyStyle.service)
        assertEquals(
            "同一份文件的两个入口必须看到同一个 Key",
            "sk-legacy-style",
            IntentAssistKeyStore(context, IntentAssistService.DEEPSEEK).read()
        )
        legacyStyle.delete()
    }

    @Test
    fun goKeyShapeIsNotTheContradictionOfDeepSeek() {
        // Key 形态只是输入框 placeholder，不参与校验：两种前缀都必须能存
        val go = IntentAssistKeyStore(context, IntentAssistService.OPENCODE_GO)
        go.delete()
        assertTrue("oc_sk_… 必须能存", go.write("oc_sk_example"))
        assertEquals("oc_sk_example", go.read())
        assertTrue("其它形态也不该被拦（便于用户粘贴错后自行发现）", go.write("oc_other_shape"))
        assertEquals("oc_other_shape", go.read())
        go.delete()
    }
}
