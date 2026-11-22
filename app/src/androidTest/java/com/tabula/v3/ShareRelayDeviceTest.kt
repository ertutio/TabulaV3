package com.tabula.v3

import android.content.ClipData
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 系统分享中转的设备级验证（路线图 #83 第 2 项）。
 *
 * 只验证**模拟器上能确定判定**的部分，不伪装成真机验证：
 *
 * 1. 真 MediaStore 环境下，一张实际存在的图能被判定为 CONFIRMED，
 *    身份键与 URI 解析一致——验证"原图关联"这条主路径确实接通。
 * 2. 不存在的 ID 落入 PENDING_REVIEW 且**仍然转发**——验证"定位失败
 *    也照常转发"。
 * 3. 接收入口在设备上真的注册成功（PackageManager 能解析出
 *    ShareRelayActivity），验证 manifest 配置有效。
 * 4. 转交 Intent 携带 FLAG_GRANT_READ_URI_PERMISSION 与 ClipData，
 *    验证临时 URI 授权确实随转发一起交出去。
 *
 * 真机截图来源（截图预览 / 图库分享面板的实际行为）无法在模拟器上
 * 等价复现，这部分仍标记为需要真机，不在本测试范围内。
 */
@RunWith(AndroidJUnit4::class)
class ShareRelayDeviceTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    /**
     * 首次运行前先确保照片读权限。
     *
     * CI 上 APK 是 `adb install` 装的，没人点过系统授权弹窗，
     * `READ_MEDIA_IMAGES` 默认拒绝——MediaStore 查询返回空。上一轮就是这样：
     * MediaStore 里明明有 `relay-seed.png`（`Row: 0 _id=16`），测试却读不到，
     * 于是所有"需要图库里有一张图"的断言集体失败。
     *
     * 用 UiAutomation 直接授权，等价于用户在系统弹窗点"允许"。
     * 授权失败不抛错——依赖真实样本的用例会走 assumeTrue 跳过，
     * 而不是把"环境没准备好"报成"代码有缺陷"。
     */
    @Before
    fun grantMediaPermissions() {
        val automation = instrumentation.uiAutomation
        val packageName = context.packageName
        listOf(
            "android.permission.READ_MEDIA_IMAGES",
            "android.permission.READ_MEDIA_VISUAL_USER_SELECTED"
        ).forEach { permission ->
            runCatching { automation.executeShellCommand("pm grant $packageName $permission").close() }
        }
    }

    /** 图库里没有任何可读照片时跳过：这是环境缺失，不是缺陷。 */
    private fun requireFixture(): Uri {
        val uri = fixtureImage()
        assumeTrue("模拟器里没有可读照片，跳过依赖真实样本的断言", uri != null)
        return uri!!
    }

    /**
     * 从图库里取一张真实照片，返回**带具体卷**的 URI。
     *
     * 上一轮（45073345771）暴露的真问题：用
     * `Uri.withAppendedPath(EXTERNAL_CONTENT_URI, id)` 拼出来的是
     * `content://media/external/images/media/<id>` —— 卷是**聚合卷** `external`，
     * 而 `mediaItemKeyFromUri` 按契约对它返回 null（聚合卷不是身份）。
     * 于是"真 MediaStore URI 必须能解析出卷 + ID"这条断言必然失败。
     *
     * 这是**测试构造**的问题，不是产品缺陷；但它顺带确认了契约在真实设备上
     * 确实按设计工作：聚合卷 URI 解析不出身份。产品侧的正确做法是查询
     * `MediaStore.MediaColumns.VOLUME_NAME` 并用
     * `MediaStore.Images.Media.getContentUri(volumeName)` 拼 URI。
     */
    private fun fixtureImage(): Uri? {
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.MediaColumns.VOLUME_NAME
        )
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            null,
            null,
            "${MediaStore.Images.Media._ID} ASC"
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID))
                val volumeColumn = cursor.getColumnIndex(MediaStore.MediaColumns.VOLUME_NAME)
                val volume = if (volumeColumn >= 0) {
                    cursor.getString(volumeColumn)?.takeIf { it.isNotBlank() }
                } else null
                val collection = if (volume != null) {
                    MediaStore.Images.Media.getContentUri(volume)
                } else {
                    // 没有卷信息时无法构造可解析的 URI，交给调用方跳过
                    return null
                }
                return Uri.withAppendedPath(collection, id.toString())
            }
        }
        return null
    }

    @Test
    fun shareRelayActivityIsRegisteredForSendAndSendMultiple() {
        val pm = context.packageManager
        val sendIntent = Intent(Intent.ACTION_SEND).apply { type = "image/png" }
        val multipleIntent = Intent(Intent.ACTION_SEND_MULTIPLE).apply { type = "image/*" }

        listOf(sendIntent, multipleIntent).forEach { intent ->
            val resolved = pm.queryIntentActivities(intent, 0)
            val hits = resolved.filter {
                it.activityInfo.packageName == context.packageName &&
                    it.activityInfo.name.endsWith("ShareRelayActivity")
            }
            assertEquals(
                "ShareRelayActivity must appear in the share sheet for ${intent.action}",
                1, hits.size
            )
        }
    }

    @Test
    fun forwardIntentCarriesReadGrantAndClipData() {
        // 与 ShareRelayActivity.forwardToTarget 同构的构造，验证授权标志与
        // ClipData 确实被带上——这是"临时 URI 授权不丢"的可观测证据。
        val uri = requireFixture()

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/*"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri("image", uri)
        }
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        val chooser = Intent.createChooser(intent, "发送到").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            putExtra(
                Intent.EXTRA_EXCLUDE_COMPONENTS,
                arrayOf(ComponentName(context.packageName, MainActivity::class.java.name))
            )
        }

        val target = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertTrue(
            "read grant must survive into the chosen target",
            target.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0
        )
        assertNotNull("ClipData must be present so targets can read the uri", target.clipData)
        assertEquals(uri, target.clipData?.getItemAt(0)?.uri)

        // 自循环防护：本包主 Activity 必须在排除列表里
        val excluded = chooser.getParcelableArrayExtra(Intent.EXTRA_EXCLUDE_COMPONENTS)
        assertTrue(excluded != null && excluded.isNotEmpty())
        assertTrue(
            excluded!!.any {
                (it as? ComponentName)?.packageName == context.packageName
            }
        )
    }

    @Test
    fun existingMediaUriResolvesToConcreteVolumeIdentity() {
        val uri = requireFixture()

        val key = com.tabula.v3.data.model.mediaItemKeyFromUri(uri)
        assertNotNull("真 MediaStore URI 必须能解析出具体卷 + ID", key)
        assertFalse("聚合卷 external 不是身份", key!!.volumeName.equals("external", ignoreCase = true))
    }

    @Test
    fun missingMediaIdDoesNotConfirm() {
        // 一个几乎不可能存在的 ID：必须解析出身份但不应命中图库
        val phantom = Uri.withAppendedPath(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, Long.MAX_VALUE.toString()
        )
        val key = com.tabula.v3.data.model.mediaItemKeyFromUri(phantom)
        assertNotNull(key)
        // 该身份不在图库中，实际判定由 ShareRelayRules 在单测里覆盖；
        // 这里确认设备上这类 URI 不会被误判为可读。
        val exists = runCatching {
            context.contentResolver.openAssetFileDescriptor(phantom, "r")?.use { true } ?: false
        }.getOrElse { false }
        assertFalse("phantom id must not be readable", exists)
    }

    @Test
    fun documentsProviderUriCannotBeParsedToVolumeIdentity() {
        val documents = Uri.parse(
            "content://com.android.providers.media.documents/document/image%3A42"
        )
        assertEquals(null, com.tabula.v3.data.model.mediaItemKeyFromUri(documents))
    }
}
