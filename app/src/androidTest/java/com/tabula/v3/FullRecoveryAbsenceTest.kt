package com.tabula.v3

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tabula.v3.permission.MediaPermissionPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * FULL 恢复两阶段验证——缺席阶段断言（宿主已 pm revoke，应用被系统强停后
 * 由本 instrumentation 重启进程）。
 *
 * 断言权限缺席期间的应用门控：级别 = NONE、无媒体读取权限；这是缺席期间
 * 可执行并核对的的真实行为（若不执行本缺席断言，则只能登记为"授权恢复与
 * 进程重建覆盖"）。
 */
@RunWith(AndroidJUnit4::class)
class FullRecoveryAbsenceTest {
    private val ins get() = InstrumentationRegistry.getInstrumentation()
    private val ctx get() = ins.targetContext

    @Test
    fun permissionAbsenceGatesMediaAccess() {
        // 宿主已 revoke——此处不得自行恢复权限
        assertEquals(
            "缺席阶段前置：READ_MEDIA_IMAGES 必须处于被撤销状态",
            android.content.pm.PackageManager.PERMISSION_DENIED,
            ctx.checkSelfPermission(android.Manifest.permission.READ_MEDIA_IMAGES)
        )
        val level = MediaPermissionPolicy.mediaAccessLevel(Build.VERSION.SDK_INT) { permission ->
            ctx.checkSelfPermission(permission) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        assertEquals(MediaPermissionPolicy.MediaAccessLevel.NONE, level)
        assertTrue(
            "缺席期间应用必须被门控（无媒体读取权限）",
            !MediaPermissionPolicy.hasMediaReadPermission(Build.VERSION.SDK_INT) { permission ->
                ctx.checkSelfPermission(permission) == android.content.pm.PackageManager.PERMISSION_GRANTED
            }
        )
    }
}
