package org.dancesonic.player.studio.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.util.UUID

/**
 * The Android side of [BleTransport]: one GATT connection to Studio's service. Notifications on the tx characteristic
 * arrive as frames; writes go to the rx characteristic and wait for the stack's acknowledgement.
 *
 * Callers must have the Bluetooth permissions (see [BlePermissions]); without them the calls throw SecurityException,
 * which the gateway reports as an unavailable link.
 */
@SuppressLint("MissingPermission")
class AndroidBleTransport private constructor(
    private val gatt: BluetoothGatt,
    private val rx: BluetoothGattCharacteristic,
    override val mtu: Int,
    override val notifications: Channel<ByteArray>,
    private val control: Channel<Control>,
) : BleTransport {
    private val writeLock = Mutex()

    override suspend fun write(frame: ByteArray) {
        writeLock.withLock {
            val started =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    gatt.writeCharacteristic(rx, frame, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) ==
                        BluetoothGatt.GATT_SUCCESS
                } else {
                    @Suppress("DEPRECATION")
                    run {
                        rx.value = frame
                        rx.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                        gatt.writeCharacteristic(rx)
                    }
                }
            if (!started) throw BleStudioException("the Bluetooth write did not start")
            val status = awaitControl<Control.Written>(WRITE_TIMEOUT_MILLIS).status
            if (status != BluetoothGatt.GATT_SUCCESS) throw BleStudioException("a Bluetooth write failed ($status)")
        }
    }

    override fun close() {
        notifications.close()
        gatt.disconnect()
        gatt.close()
    }

    private suspend inline fun <reified T : Control> awaitControl(timeoutMillis: Long): T =
        withTimeout(timeoutMillis) {
            when (val event = control.receive()) {
                is T -> event
                is Control.Lost -> throw BleStudioException("the Bluetooth connection was lost")
                else -> throw BleStudioException("unexpected Bluetooth event: $event")
            }
        }

    /** What the GATT callback reports to the code that is waiting for it. */
    sealed interface Control {
        data class Connected(
            val status: Int,
        ) : Control

        data class Mtu(
            val mtu: Int,
            val status: Int,
        ) : Control

        data class Services(
            val status: Int,
        ) : Control

        data class Subscribed(
            val status: Int,
        ) : Control

        data class Written(
            val status: Int,
        ) : Control

        data object Lost : Control
    }

    companion object {
        private const val REQUESTED_MTU = 247
        private const val STEP_TIMEOUT_MILLIS = 10_000L
        private const val WRITE_TIMEOUT_MILLIS = 5_000L
        private val RX = UUID.fromString("7c3b0001-5b9e-4c2c-9c6a-2d1f5e0a4b10")
        private val TX = UUID.fromString("7c3b0002-5b9e-4c2c-9c6a-2d1f5e0a4b10")
        private val CLIENT_CONFIG = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        /**
         * Connects to [device], negotiates the MTU, finds Studio's characteristics and turns notifications on. Throws
         * [BleStudioException] when any step fails; the GATT connection is then closed.
         */
        @Suppress("ThrowsCount", "TooGenericExceptionCaught")
        suspend fun connect(
            context: Context,
            device: BluetoothDevice,
            serviceUuid: UUID,
        ): AndroidBleTransport {
            val control = Channel<Control>(Channel.UNLIMITED)
            val notifications = Channel<ByteArray>(Channel.UNLIMITED)
            val callback = Callback(control, notifications)
            val gatt =
                device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
                    ?: throw BleStudioException("Bluetooth connection could not start")
            try {
                val connected = awaitFrom(control) { it as? Control.Connected }
                if (connected.status != BluetoothGatt.GATT_SUCCESS) throw BleStudioException("Bluetooth connect failed")
                gatt.requestMtu(REQUESTED_MTU)
                val mtu = awaitFrom(control) { it as? Control.Mtu }
                val service = discover(gatt, control, serviceUuid)
                val rx = service.getCharacteristic(RX) ?: throw BleStudioException("Studio has no rx characteristic")
                val tx = service.getCharacteristic(TX) ?: throw BleStudioException("Studio has no tx characteristic")
                subscribe(gatt, control, tx)
                return AndroidBleTransport(gatt, rx, mtu.mtu, notifications, control)
            } catch (e: CancellationException) {
                gatt.close()
                throw e
            } catch (e: BleStudioException) {
                gatt.close()
                throw e
            } catch (e: Exception) {
                gatt.close()
                throw BleStudioException("Bluetooth setup failed", e)
            }
        }

        private suspend fun discover(
            gatt: BluetoothGatt,
            control: Channel<Control>,
            serviceUuid: UUID,
        ): android.bluetooth.BluetoothGattService {
            gatt.discoverServices()
            awaitFrom(control) { it as? Control.Services }
            return gatt.getService(serviceUuid) ?: throw BleStudioException("Studio's service is not on this device")
        }

        private suspend fun subscribe(
            gatt: BluetoothGatt,
            control: Channel<Control>,
            tx: BluetoothGattCharacteristic,
        ) {
            gatt.setCharacteristicNotification(tx, true)
            val config = tx.getDescriptor(CLIENT_CONFIG) ?: throw BleStudioException("Studio's tx has no config")
            writeConfig(gatt, config)
            awaitFrom(control) { it as? Control.Subscribed }
        }

        @Suppress("DEPRECATION")
        private fun writeConfig(
            gatt: BluetoothGatt,
            config: BluetoothGattDescriptor,
        ) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeDescriptor(config, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            } else {
                config.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                gatt.writeDescriptor(config)
            }
        }

        private suspend fun <T> awaitFrom(
            control: Channel<Control>,
            pick: (Control) -> T?,
        ): T =
            withTimeout(STEP_TIMEOUT_MILLIS) {
                var picked: T? = null
                while (picked == null) {
                    val event = control.receive()
                    if (event is Control.Lost) throw BleStudioException("the Bluetooth connection was lost")
                    picked = pick(event)
                }
                picked
            }

        private class Callback(
            private val control: Channel<Control>,
            private val notifications: Channel<ByteArray>,
        ) : BluetoothGattCallback() {
            override fun onConnectionStateChange(
                gatt: BluetoothGatt,
                status: Int,
                newState: Int,
            ) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    control.trySend(Control.Connected(status))
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    control.trySend(Control.Lost)
                    notifications.close()
                }
            }

            override fun onMtuChanged(
                gatt: BluetoothGatt,
                mtu: Int,
                status: Int,
            ) {
                control.trySend(Control.Mtu(mtu, status))
            }

            override fun onServicesDiscovered(
                gatt: BluetoothGatt,
                status: Int,
            ) {
                control.trySend(Control.Services(status))
            }

            override fun onDescriptorWrite(
                gatt: BluetoothGatt,
                descriptor: BluetoothGattDescriptor,
                status: Int,
            ) {
                control.trySend(Control.Subscribed(status))
            }

            override fun onCharacteristicWrite(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int,
            ) {
                control.trySend(Control.Written(status))
            }

            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray,
            ) {
                notifications.trySend(value)
            }

            @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
            ) {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                    notifications.trySend(characteristic.value ?: return)
                }
            }
        }
    }
}
