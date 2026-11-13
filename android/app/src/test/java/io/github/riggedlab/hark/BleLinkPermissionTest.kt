package io.github.riggedlab.hark

import android.Manifest
import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanSettings
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowBluetoothLeScanner
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], shadows = [BleLinkPermissionTest.DeniedScanner::class])
class BleLinkPermissionTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var scanner: BluetoothLeScanner
    private var permissionLosses = 0
    private val states = mutableListOf<LinkState>()

    @Before fun setup() {
        DeniedScanner.throwOnStart = false
        DeniedScanner.throwOnStop = false
        DeniedScanner.startCalls = 0
        shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        val adapter = app.getSystemService(BluetoothManager::class.java).adapter
        shadowOf(adapter).setState(BluetoothAdapter.STATE_ON)
        scanner = adapter.bluetoothLeScanner
    }

    private fun link() = BleLink(app, onAudio = { _, _ -> }, onBattery = {},
        onState = { state, _ -> states += state }, onPermissionLost = { permissionLosses++ })

    @Test fun scanAndCleanupSecurityExceptionsStopWithoutReconnectLoop() {
        DeniedScanner.throwOnStart = true
        DeniedScanner.throwOnStop = true
        val link = link()
        try {
            link.start()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(1))
            assertEquals(1, permissionLosses)
            assertEquals(1, DeniedScanner.startCalls)
            assertTrue(shadowOf(scanner).scanCallbacks.isEmpty())
        } finally { link.stop() }
    }

    @Test fun revokedScanCallbackCancelsScanAndQueuedRetry() {
        val link = link()
        try {
            link.start()
            val callback = shadowOf(scanner).scanCallbacks.single()
            shadowOf(app).denyPermissions(Manifest.permission.BLUETOOTH_CONNECT)
            callback.onScanFailed(1)
            shadowOf(Looper.getMainLooper()).idle()
            val stateCount = states.size
            callback.onScanFailed(1) // 系统中已排队的迟到回调不能再发起扫描。
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(1))
            assertEquals(1, permissionLosses)
            assertEquals(stateCount, states.size)
            assertEquals(1, DeniedScanner.startCalls)
            assertTrue(shadowOf(scanner).scanCallbacks.isEmpty())
        } finally { link.stop() }
    }

    @Implements(BluetoothLeScanner::class)
    class DeniedScanner : ShadowBluetoothLeScanner() {
        @Implementation
        override fun startScan(filters: MutableList<ScanFilter>?, settings: ScanSettings, callback: ScanCallback) {
            startCalls++
            if (throwOnStart) throw SecurityException("scan permission revoked")
            super.startScan(filters, settings, callback)
        }

        @Implementation
        override fun stopScan(callback: ScanCallback) {
            if (throwOnStop) throw SecurityException("stop scan permission revoked")
            super.stopScan(callback)
        }

        companion object {
            var throwOnStart = false
            var throwOnStop = false
            var startCalls = 0
        }
    }
}
