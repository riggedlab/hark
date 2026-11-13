package io.github.riggedlab.hark

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/** 连接录音设备的必需权限；定位（12+）和通知只影响附加功能。 */
internal object BlePermissions {
    const val DENIED_MESSAGE = "未获附近设备权限，无法连接录音设备"
    const val REVOKED_MESSAGE = "权限已撤销，录音已停止"

    fun required(sdk: Int): List<String> = if (sdk >= Build.VERSION_CODES.S) {
        listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        listOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    fun hasRequired(sdk: Int, isGranted: (String) -> Boolean): Boolean =
        required(sdk).all(isGranted)

    fun hasRequired(context: Context): Boolean = hasRequired(Build.VERSION.SDK_INT) {
        context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
    }
}
