package org.dancesonic.player.studio.ble

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/**
 * The runtime permissions Bluetooth remote control needs. From Android 12 they are the scan and connect permissions,
 * and scanning is declared as not deriving location. Android 10 and 11 need location to scan at all, and this app's
 * minimum is Android 10, so the fine-location permission is requested there.
 */
object BlePermissions {
    /** The permissions to ask for on this Android version. */
    fun required(sdk: Int = Build.VERSION.SDK_INT): Array<String> =
        if (sdk >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    /** True when every permission [required] lists is granted. */
    fun granted(context: Context): Boolean =
        required().all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
}
