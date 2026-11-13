package io.github.riggedlab.hark

import android.Manifest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class BlePermissionsTest(
    private val sdk: Int,
    private val granted: Set<String>,
    private val allowed: Boolean,
) {
    @Test fun onlyRequiredDevicePermissionsGateRecording() {
        assertEquals(allowed, BlePermissions.hasRequired(sdk) { it in granted })
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "sdk={0}, granted={1}")
        fun cases(): List<Array<Any>> {
            val permissions = listOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.POST_NOTIFICATIONS,
            )
            return (29..35).flatMap { sdk ->
                (0 until (1 shl permissions.size)).map { mask ->
                    val granted = permissions.filterIndexed { i, _ -> mask and (1 shl i) != 0 }.toSet()
                    val allowed = if (sdk >= 31) mask and 3 == 3 else mask and 4 != 0
                    arrayOf<Any>(sdk, granted, allowed)
                }
            }
        }
    }
}
