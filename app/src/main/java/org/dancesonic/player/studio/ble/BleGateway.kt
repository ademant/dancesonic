package org.dancesonic.player.studio.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.dancesonic.player.studio.StudioLink
import org.dancesonic.player.studio.noise.NoiseKeyPair
import java.util.UUID
import kotlin.coroutines.resume

/**
 * Finds a paired Studio over Bluetooth by its service UUID, and opens the handshaked link. Returns null when Bluetooth
 * is off, not permitted, or Studio is not in range; the caller then uses the network.
 */
class BleGateway(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    /** Opens a link to the Studio advertising [serviceUuid]; null when it cannot be reached over Bluetooth. */
    @Suppress("ReturnCount")
    suspend fun open(
        serviceUuid: UUID,
        phone: NoiseKeyPair,
        isStudio: (tlsSpki: ByteArray, noiseKey: ByteArray) -> Boolean,
    ): StudioLink? {
        if (!BlePermissions.granted(context)) return null
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return null
        if (!adapter.isEnabled) return null
        val device = findDevice(adapter, serviceUuid, SCAN_TIMEOUT_MILLIS) ?: return null
        val transport = AndroidBleTransport.connect(context, device, serviceUuid)
        return BleStudioOpener.open(transport, serviceUuid, phone, scope, isStudio)
    }

    @SuppressLint("MissingPermission")
    private suspend fun findDevice(
        adapter: BluetoothAdapter,
        serviceUuid: UUID,
        timeoutMillis: Long,
    ): android.bluetooth.BluetoothDevice? {
        val scanner = adapter.bluetoothLeScanner ?: return null
        val filters = listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(serviceUuid)).build())
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        return withTimeoutOrNull(timeoutMillis) {
            suspendCancellableCoroutine { cont ->
                val callback =
                    object : ScanCallback() {
                        override fun onScanResult(
                            callbackType: Int,
                            result: ScanResult,
                        ) {
                            if (cont.isActive) {
                                scanner.stopScan(this)
                                cont.resume(result.device)
                            }
                        }

                        override fun onScanFailed(errorCode: Int) {
                            if (cont.isActive) cont.resume(null)
                        }
                    }
                scanner.startScan(filters, settings, callback)
                cont.invokeOnCancellation { scanner.stopScan(callback) }
            }
        }
    }

    private companion object {
        const val SCAN_TIMEOUT_MILLIS = 6_000L
    }
}
