package org.dancesonic.player.studio

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import okhttp3.HttpUrl
import org.dancesonic.player.studio.ble.BleGateway
import org.dancesonic.player.studio.ble.BleStudioException
import org.dancesonic.player.studio.noise.NoiseKeyPair
import org.dancesonic.player.subsonic.DeviceKeys
import java.util.UUID

/** Why a Studio could not be paired or reached, for the message the user sees. */
class StudioUnavailable(val reason: Reason, cause: Throwable? = null) : Exception(reason.name, cause) {
    enum class Reason {
        /** The code is not a DanceSonic Studio code, or is for another version. */
        BAD_CODE,

        /** The code has expired. Open "Pair a phone" in Studio again. */
        EXPIRED,

        /** No Studio with this fingerprint answers over Bluetooth or on the local network. */
        NOT_FOUND,

        /** The Studio that answered is not the one paired (its fingerprint or key does not match). */
        NOT_THIS_STUDIO,

        /** Studio refused the pairing, or the user rejected the phone in Studio. */
        REFUSED,

        /** The phone was not accepted in time, or Studio did not answer. */
        TIMEOUT,

        /** Anything else: the connection failed. */
        FAILED,
    }
}

/**
 * An open session with a paired Studio, the record it belongs to, and the transport it runs on: `ble` or `network`,
 * as Studio names it in `welcome`.
 */
class OpenStudio(
    val record: StudioPairing,
    val session: StudioSession,
    val transport: String,
)

/** A link to Studio during pairing, with Studio's Noise key and name as they were found. */
private class PairingLink(
    val link: StudioLink,
    val noiseKey: ByteArray,
    val name: String,
)

/** Captures the Noise key Studio shows during a Bluetooth handshake, when its fingerprint matches. */
private class NoiseCapture {
    var key: ByteArray? = null
}

/**
 * Pairs with a Studio from its QR code, and reconnects to a paired one. Bluetooth is tried first when it is available
 * and permitted; the network is the fallback. Either way the fingerprint is checked before anything is sent. A pairing
 * is stored only after Studio has accepted the phone.
 */
@Suppress("TooManyFunctions")
class StudioRemote
    @Suppress("LongParameterList")
    constructor(
        private val store: StudioPairingStore,
        private val discovery: StudioDiscovery,
        private val connector: StudioConnector,
        private val scope: CoroutineScope,
        private val ble: BleGateway? = null,
        private val noiseKeys: PhoneNoiseKeys = PhoneNoiseKeys(),
        private val clock: () -> Long = { System.currentTimeMillis() / MILLIS_PER_SECOND },
        private val newId: () -> String = { UUID.randomUUID().toString() },
    ) {
        /**
         * Pairs with the Studio that shows [code], as [phoneName], and leaves the session open. Throws [StudioUnavailable]
         * with the reason. When pairing fails the new device key is removed again. A pairing that Studio accepted is
         * kept
         * even if the first connection then fails; it can be connected to later.
         */
        @Suppress("ThrowsCount", "TooGenericExceptionCaught")
        suspend fun pair(
            code: String,
            phoneName: String,
        ): OpenStudio {
            val offer =
                when (val parsed = StudioOffer.parse(code, clock())) {
                    is StudioOffer.ParseResult.Ok -> parsed.offer
                    is StudioOffer.ParseResult.Refused ->
                        throw StudioUnavailable(
                            if (parsed.problem == StudioOffer.Problem.EXPIRED) {
                                StudioUnavailable.Reason.EXPIRED
                            } else {
                                StudioUnavailable.Reason.BAD_CODE
                            },
                        )
                }
            val alias = DeviceKeys.create()
            val signer = KeystoreStudioSigner(alias)
            val phoneKeys = NoiseKeyPair.generate()
            val pairer = StudioPairer(signer, noiseKey = phoneKeys.publicKey)
            val (record, link) =
                try {
                    pairWithStudio(offer, phoneName, alias, pairer, phoneKeys)
                } catch (e: Exception) {
                    DeviceKeys.delete(alias)
                    throw e.asUnavailable()
                }
            val session = sessionOn(link, record, signer)
            val welcome =
                try {
                    session.hello()
                } catch (e: TimeoutCancellationException) {
                    link.close()
                    throw StudioUnavailable(StudioUnavailable.Reason.TIMEOUT, e)
                } catch (e: CancellationException) {
                    link.close()
                    throw e
                } catch (e: Exception) {
                    link.close()
                    throw StudioUnavailable(StudioUnavailable.Reason.FAILED, e)
                }
            return OpenStudio(record, session, welcome.transport)
        }

        /** The pairing itself: finds Studio, checks it, sends the request, and stores the record once accepted. */
        @Suppress("TooGenericExceptionCaught")
        private suspend fun pairWithStudio(
            offer: StudioOffer,
            phoneName: String,
            alias: String,
            pairer: StudioPairer,
            phoneKeys: NoiseKeyPair,
        ): Pair<StudioPairing, StudioLink> {
            val found = openForPairing(offer, phoneKeys)
            try {
                val device = pairer.pair(found.link, offer, phoneName)
                val (iv, sealed) = noiseKeys.seal(phoneKeys)
                val record =
                    StudioPairing(
                        id = newId(),
                        name = found.name.ifBlank { offer.name }.ifBlank { "Studio" },
                        fingerprint = StudioWire.base64Url(offer.fingerprint),
                        studioNoiseKey = StudioWire.base64Url(found.noiseKey),
                        bleService = offer.bleService,
                        deviceId = device.deviceId,
                        keyAlias = alias,
                        phoneNoiseKey = StudioWire.base64Url(phoneKeys.publicKey),
                        phoneNoiseIv = iv,
                        phoneNoiseSealed = sealed,
                    )
                store.add(record)
                return record to found.link
            } catch (e: Exception) {
                found.link.close()
                throw e
            }
        }

        /** A link for pairing: over Bluetooth when Studio is in range, otherwise over the network. */
        private suspend fun openForPairing(
            offer: StudioOffer,
            phoneKeys: NoiseKeyPair,
        ): PairingLink {
            val fromBluetooth = openBluetoothForPairing(offer, phoneKeys)
            if (fromBluetooth != null) return fromBluetooth
            val address = locate(offer.fingerprint)
            val info = info(address)
            if (!info.fingerprint.contentEquals(offer.fingerprint)) {
                throw StudioUnavailable(StudioUnavailable.Reason.NOT_THIS_STUDIO)
            }
            return PairingLink(openChecked(address, info.noiseKey, offer.fingerprint), info.noiseKey, info.name)
        }

        @Suppress("ReturnCount")
        private suspend fun openBluetoothForPairing(
            offer: StudioOffer,
            phoneKeys: NoiseKeyPair,
        ): PairingLink? {
            val gateway = ble ?: return null
            val uuid = runCatching { UUID.fromString(offer.bleService) }.getOrNull() ?: return null
            val capture = NoiseCapture()
            val link =
                try {
                    gateway.open(uuid, phoneKeys) { spki, noise ->
                        val matches = StudioWire.fingerprint(spki, noise).contentEquals(offer.fingerprint)
                        if (matches) capture.key = noise
                        matches
                    }
                } catch (e: BleStudioException) {
                    Log.i(TAG, "Bluetooth pairing not used: ${e.message}")
                    null
                }
            val noise = capture.key
            if (link == null || noise == null) {
                link?.close()
                return null
            }
            return PairingLink(link, noise, offer.name)
        }

        @Suppress("TooGenericExceptionCaught")
        private fun Exception.asUnavailable(): Exception =
            when (this) {
                is StudioUnavailable -> this
                is TimeoutCancellationException -> StudioUnavailable(StudioUnavailable.Reason.TIMEOUT, this)
                is StudioProtocolError ->
                    StudioUnavailable(
                        when (code) {
                            "rejected" -> StudioUnavailable.Reason.REFUSED
                            "expired", "secret_used", "bad_proof" -> StudioUnavailable.Reason.EXPIRED
                            else -> StudioUnavailable.Reason.REFUSED
                        },
                        this,
                    )
                else -> StudioUnavailable(StudioUnavailable.Reason.FAILED, this)
            }

        /** Connects to a paired Studio, over Bluetooth when possible, and says hello. */
        suspend fun connect(record: StudioPairing): OpenStudio = connectOn(record, StudioTransports.initial())

        /**
         * Opens a new session with [record] after its link on [dropped] was lost, over the other transport. The counter
         * carries on from the stored one, so Studio sees no replay. Throws [StudioUnavailable] when that one fails too.
         */
        suspend fun reconnect(
            record: StudioPairing,
            dropped: String,
        ): OpenStudio = connectOn(record, StudioTransports.after(dropped))

        @Suppress("ThrowsCount", "TooGenericExceptionCaught")
        private suspend fun connectOn(
            record: StudioPairing,
            order: List<String>,
        ): OpenStudio {
            val fingerprint =
                StudioWire.decodeBase64Url(record.fingerprint, FINGERPRINT_BYTES)
                    ?: throw StudioUnavailable(StudioUnavailable.Reason.FAILED)
            val noiseKey =
                StudioWire.decodeBase64Url(record.studioNoiseKey, StudioWire.NOISE_KEY_BYTES)
                    ?: throw StudioUnavailable(StudioUnavailable.Reason.FAILED)
            val signer = KeystoreStudioSigner(record.keyAlias)
            var link: StudioLink? = null
            try {
                link = openFor(record, fingerprint, noiseKey, order)
                val session = sessionOn(link, record, signer)
                val welcome = session.hello()
                return OpenStudio(record, session, welcome.transport)
            } catch (e: StudioUnavailable) {
                link?.close()
                throw e
            } catch (e: TimeoutCancellationException) {
                link?.close()
                throw StudioUnavailable(StudioUnavailable.Reason.TIMEOUT, e)
            } catch (e: StudioProtocolError) {
                link?.close()
                throw StudioUnavailable(StudioUnavailable.Reason.REFUSED, e)
            } catch (e: CancellationException) {
                link?.close()
                throw e
            } catch (e: Exception) {
                link?.close()
                throw StudioUnavailable(StudioUnavailable.Reason.FAILED, e)
            }
        }

        /**
         * Tries each transport in [order]; the first link that opens is used. When none does, the last failure is
         * thrown.
         */
        @Suppress("TooGenericExceptionCaught")
        private suspend fun openFor(
            record: StudioPairing,
            fingerprint: ByteArray,
            noiseKey: ByteArray,
            order: List<String>,
        ): StudioLink {
            var failure = StudioUnavailable(StudioUnavailable.Reason.NOT_FOUND)
            for (transport in order) {
                try {
                    return when (transport) {
                        StudioTransports.BLUETOOTH -> openBluetoothForConnect(record, fingerprint, noiseKey) ?: continue
                        else -> openChecked(locate(fingerprint), noiseKey, fingerprint)
                    }
                } catch (e: StudioUnavailable) {
                    failure = e
                }
            }
            throw failure
        }

        /** The Bluetooth link to [record], or null when Bluetooth is off, not permitted, or Studio is not in range. */
        @Suppress("ReturnCount")
        private suspend fun openBluetoothForConnect(
            record: StudioPairing,
            fingerprint: ByteArray,
            noiseKey: ByteArray,
        ): StudioLink? {
            val gateway = ble ?: return null
            val phoneKeys = runCatching { noiseKeys.open(record) }.getOrNull() ?: return null
            val uuid = runCatching { UUID.fromString(record.bleService) }.getOrNull() ?: return null
            return try {
                gateway.open(uuid, phoneKeys) { spki, noise ->
                    StudioWire.fingerprint(spki, noise).contentEquals(fingerprint) &&
                        noise.contentEquals(noiseKey)
                }
            } catch (e: BleStudioException) {
                Log.i(TAG, "Bluetooth not used: ${e.message}")
                null
            }
        }

        /** Forgets a pairing: its record and its device key. Studio lists the phone until it is revoked there. */
        suspend fun forget(record: StudioPairing) {
            store.remove(record.id)
            DeviceKeys.delete(record.keyAlias)
        }

        private suspend fun locate(fingerprint: ByteArray): HttpUrl =
            discovery.find(StudioWire.mdnsTag(fingerprint))
                ?: throw StudioUnavailable(StudioUnavailable.Reason.NOT_FOUND)

        @Suppress("TooGenericExceptionCaught")
        private suspend fun info(address: HttpUrl): StudioInfo =
            try {
                connector.info(address)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                throw StudioUnavailable(StudioUnavailable.Reason.FAILED, e)
            }

        @Suppress("TooGenericExceptionCaught")
        private suspend fun openChecked(
            address: HttpUrl,
            noiseKey: ByteArray,
            fingerprint: ByteArray,
        ): StudioLink =
            try {
                connector.open(address) { spki -> StudioWire.fingerprint(spki, noiseKey).contentEquals(fingerprint) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                throw StudioUnavailable(StudioUnavailable.Reason.NOT_THIS_STUDIO, e)
            }

        private fun sessionOn(
            link: StudioLink,
            record: StudioPairing,
            signer: StudioSigner,
        ): StudioSession =
            StudioSession(
                link = link,
                deviceId = record.deviceId,
                signer = signer,
                counter =
                    object : StudioCounter {
                        override suspend fun advance(): Long =
                            store.advanceCounter(record.id) ?: throw StudioUnavailable(StudioUnavailable.Reason.FAILED)
                    },
                scope = scope,
            )

        private companion object {
            const val TAG = "StudioRemote"
            const val FINGERPRINT_BYTES = 32
            const val MILLIS_PER_SECOND = 1_000L
        }
    }
