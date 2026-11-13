package io.github.riggedlab.hark

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.PowerManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowService
import org.robolectric.util.ReflectionHelpers
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], shadows = [RecorderPermissionFailureTest.FailingForeground::class])
class RecorderPermissionFailureTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    @Before fun reset() {
        AppState.update { Status() }
        FailingForeground.types.clear()
        FailingForeground.failure = SecurityException("permission revoked during promotion")
        shadowOf(app).denyPermissions(Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION)
    }

    @Test fun missingPermissionsSkipInitializationAndStickyRestart() {
        val controller = Robolectric.buildService(RecorderService::class.java).create()
        val service = controller.get()
        try {
            assertEquals(Service.START_NOT_STICKY, service.onStartCommand(null, 0, 1))
            assertTrue(shadowOf(service).isStoppedBySelf)
            assertTrue(FailingForeground.types.isEmpty())
            assertEquals(BlePermissions.DENIED_MESSAGE, AppState.status.value.message)
        } finally { controller.destroy() }
        assertEquals(BlePermissions.DENIED_MESSAGE, AppState.status.value.message)
    }

    @Test fun locationPromotionAndBluetoothFallbackAreBothCaught() {
        grantBluetooth()
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        // 不调用 onCreate，隔离前台服务失败路径，避免启动音频/模型工作线程。
        val controller = Robolectric.buildService(RecorderService::class.java)
        val service = controller.get()
        try {
            assertEquals(Service.START_NOT_STICKY, service.onStartCommand(null, 0, 1))
            val base = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            assertEquals(listOf(base or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION, base), FailingForeground.types)
            assertTrue(shadowOf(service).isStoppedBySelf)
        } finally { controller.destroy() }
    }

    @Test fun nonSecurityForegroundFailureAlsoStopsWithoutRetryingBaseType() {
        grantBluetooth()
        FailingForeground.failure = IllegalArgumentException("unsupported foreground type")
        val controller = Robolectric.buildService(RecorderService::class.java)
        val service = controller.get()
        try {
            assertEquals(Service.START_NOT_STICKY, service.onStartCommand(null, 0, 1))
            assertEquals(listOf(ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE), FailingForeground.types)
            assertTrue(shadowOf(service).isStoppedBySelf)
        } finally { controller.destroy() }
    }

    @Test fun revocationFinalizesQueuedAudioAndReleasesWakeLock() {
        val controller = Robolectric.buildService(RecorderService::class.java)
        val service = controller.get()
        val dir = RecorderService.chunkDir(service).apply { mkdirs() }
        val chunks = ChunkRecorder(dir)
        val io = ReflectionHelpers.getField<ScheduledExecutorService>(service, "io")
        val lock = service.getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "test:recorder").apply { acquire() }
        ReflectionHelpers.setField(service, "chunks", chunks)
        ReflectionHelpers.setField(service, "wakeLock", lock)
        ReflectionHelpers.setField(service, "started", true)
        io.execute { chunks.onPacket(0, byteArrayOf(0x48, 1, 2, 3)) }
        try {
            assertEquals(Service.START_NOT_STICKY, service.onStartCommand(null, 0, 1))
        } finally { controller.destroy() }
        assertTrue(io.awaitTermination(5, TimeUnit.SECONDS))
        assertFalse(lock.isHeld)
        assertFalse(dir.listFiles()!!.any { it.name.endsWith(".part") })
        assertTrue(dir.listFiles()!!.any { it.name.endsWith(".opus") })
        assertEquals(LinkState.STOPPED, AppState.status.value.link)
        assertEquals(BlePermissions.REVOKED_MESSAGE, AppState.status.value.message)
    }

    private fun grantBluetooth() = shadowOf(app).grantPermissions(
        Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)

    @Implements(Service::class)
    class FailingForeground : ShadowService() {
        @Implementation
        override fun startForeground(id: Int, notification: Notification, type: Int) {
            types += type
            throw failure
        }

        companion object {
            val types = mutableListOf<Int>()
            var failure: RuntimeException = SecurityException()
        }
    }
}
