package io.github.riggedlab.hark

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.provider.Settings
import android.view.View
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MainActivityPermissionTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Before fun reset() {
        AppState.update { Status() }
        shadowOf(rule.activity).clearStartedServices()
        shadowOf(rule.activity).denyPermissions(Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.POST_NOTIFICATIONS)
    }

    @Suppress("DEPRECATION") // 模拟系统将权限结果交回 Activity，而非直接调用业务方法。
    private fun answerPermissionRequest(granted: Set<String>, updateActualGrants: Boolean = true) {
        rule.onNodeWithContentDescription("打开设备状态").performClick()
        rule.onNodeWithText("开始录音").performClick()
        val request = shadowOf(rule.activity).lastRequestedPermission
        if (updateActualGrants) shadowOf(rule.activity).grantPermissions(*granted.toTypedArray())
        rule.runOnUiThread {
            rule.activity.onRequestPermissionsResult(request.requestCode, request.requestedPermissions,
                request.requestedPermissions.map {
                    if (it in granted) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED
                }.toIntArray())
        }
        rule.waitForIdle()
    }

    @Test fun denialShowsRetryWithoutStartingService() {
        BlePermissions.required(34).forEach {
            shadowOf(rule.activity.packageManager).setShouldShowRequestPermissionRationale(it, true)
        }
        answerPermissionRequest(emptySet())
        rule.onNodeWithText(BlePermissions.DENIED_MESSAGE).assertIsDisplayed()
        rule.onNodeWithText("重试").assertIsDisplayed()
        assertNull(shadowOf(rule.activity).nextStartedService)
        // 与现有 UI walkthrough 一样直接绘制 Window；Robolectric 不支持 Dialog 的 PixelCopy。
        val wm = Class.forName("android.view.WindowManagerGlobal").getMethod("getInstance").invoke(null)
        val view = ReflectionHelpers.getField<List<View>>(wm, "mViews").last { it.isShown }
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        rule.runOnUiThread { view.draw(Canvas(bitmap)) }
        val out = File("build/reports/ble-permission-ui/denied.png").apply { parentFile!!.mkdirs() }
        out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun permanentDenialOpensApplicationDetails() {
        answerPermissionRequest(emptySet())
        rule.onNodeWithText("去设置").performClick()
        val intent = shadowOf(rule.activity).nextStartedActivity
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, intent.action)
        assertEquals("package:${rule.activity.packageName}", intent.dataString)
        assertNull(shadowOf(rule.activity).nextStartedService)
    }

    @Test fun optionalLocationAndNotificationDenialsStillStartRecording() {
        answerPermissionRequest(BlePermissions.required(34).toSet())
        val intent = shadowOf(rule.activity).nextStartedService
        assertEquals(RecorderService::class.java.name, intent.component!!.className)
        assertNull(AppState.status.value.message)
    }

    @Test fun permissionRevokedBetweenCallbackAndStartIsRejectedAgain() {
        answerPermissionRequest(BlePermissions.required(34).toSet(), updateActualGrants = false)
        rule.onNodeWithText(BlePermissions.DENIED_MESSAGE).assertIsDisplayed()
        assertNull(shadowOf(rule.activity).nextStartedService)
    }

    @Test fun runtimeRevocationIsVisibleOutsideHomePage() {
        rule.onNodeWithContentDescription("设置").performClick()
        rule.runOnUiThread { AppState.update { it.copy(message = BlePermissions.REVOKED_MESSAGE) } }
        rule.onNodeWithText(BlePermissions.REVOKED_MESSAGE).assertIsDisplayed()
        rule.onNodeWithText("去设置").assertIsDisplayed()
    }
}
