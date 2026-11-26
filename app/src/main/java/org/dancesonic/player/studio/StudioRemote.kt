package org.dancesonic.player.studio

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import okhttp3.HttpUrl
import org.dancesonic.player.studio.ble.BleGateway
import org.dancesonic.player.studio.ble.BleStudioException
import org.dancesonic.player.studio.noise.NoiseKeyPair
import org.dancesonic.player.subsonic.DeviceKeys
import org.dancesonic.player.subsonic.LocalNetworkAccess
import java.util.UUID

/** Why a Studio could not be paired or reached, for the message the user sees. */
class StudioUnavailable(val reason: Reason, cause: Throwable? = null) : Exception(reason.name, cause) {
    enum class Reason {
        /** The code is not a DanceSonic Studio code, or is for another version. */
        BAD_CODE,

        /** The code has expired. Open "Pair a phone" in Studio again. */
        EXPIRED,

        /** No Studio with this fingerprint answers over Bluetooth, the relay or the local network. */
        NOT_FOUND,

        /** The server has no relay, or the app is not connected to folksonic (#104). */
        NOT_OFFERED,

        /** The Studio that answered is not the one paired (its fingerprint or key does not match). */
        NOT_THIS_STUDIO,

        /** Studio refused the pairing, or the user rejected the phone in Studio. */
        REFUSED,

        /** The phone was not accepted in time, or Studio did not answer. */
        TIMEOUT,

        /** The local network is needed and the app may not use it: Android 17's permission is missing (#124). */
        LOCAL_NETWORK_BLOCKED,

        /** Anything else: the connection failed. */
        FAILED,
    }
}

/**
 * An open session with a paired Studio, the record it belongs to, and the transport it runs on: `ble`, `relay` or
 * `network`, as Studio names it in `welcome`.
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
 * Pairs with a Studio from its QR code or through folksonic, and reconnects to a paired one. The transports are tried
 * in Studio's order: Bluetooth when it is available and permitted, folksonic's relay when the app is connected to
 * folksonic, then the local network. Each time the fingerprint is checked before anything is sent. A pairing is
 * stored only after Studio has accepted the phone.
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
        private val relay: RelayGateway? = null,
        private val noiseKeys: PhoneNoiseKeys = PhoneNoiseKeys(),
        private val clock: () -> Long = { System.currentTimeMillis() / MILLIS_PER_SECOND },
        private val newId: () -> String = { UUID.randomUUID().toString() },
    ) {
        /**
         * Pairs with the Studio that shows [code], as [phoneName], and leaves the session open. Throws
         * [StudioUnavailable] with the reason. When pairing fails the new device key is removed again. A pairing that
         * Studio accepted is kept even if the first connection then fails; it can be connected to later.
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
            return helloAfterPairing(link, record, signer)
        }

        /**
         * Pairs with [studio], found on the account's relay, by comparing codes (#104): [onCode] gets the 6-digit code
         * as soon as the link is open, for the user to compare with the one Studio shows, and allow the phone there.
         * Throws [StudioUnavailable] with the reason; the new device key is removed again when pairing fails.
         */
        @Suppress("ThrowsCount", "TooGenericExceptionCaught")
        suspend fun pairThroughFolksonic(
            studio: RelayStudio,
            phoneName: String,
            onCode: (String) -> Unit,
        ): OpenStudio {
            val gateway = relay ?: throw StudioUnavailable(StudioUnavailable.Reason.NOT_OFFERED)
            val claimed = studio.record
            val fingerprint = claimed.fingerprint
            // Studio's relay id comes from its fingerprint, so a record under another id is not Studio's own.
            if (RelayWire.studioDeviceId(fingerprint) != studio.id) {
                throw StudioUnavailable(StudioUnavailable.Reason.NOT_THIS_STUDIO)
            }
            val alias = DeviceKeys.create()
            val signer = KeystoreStudioSigner(alias)
            val phoneKeys = NoiseKeyPair.generate()
            val (record, link) =
                try {
                    val opened =
                        gateway.open(fingerprint, phoneKeys) { spki, noise ->
                            StudioWire.fingerprint(spki, noise).contentEquals(fingerprint) &&
                                noise.contentEquals(claimed.noisePublic)
                        } ?: throw StudioUnavailable(StudioUnavailable.Reason.NOT_OFFERED)
                    try {
                        onCode(RelayWire.comparisonCode(opened.binding))
                        val pairer = StudioCodePairer(signer)
                        val device = pairer.pair(opened, fingerprint, phoneKeys.publicKey, phoneName)
                        val found = PairingLink(opened, claimed.noisePublic, studio.name)
                        val ble = claimed.bleService.toString()
                        storeRecord(found, fingerprint, ble, device, alias, phoneKeys) to opened
                    } catch (e: Exception) {
                        opened.close()
                        throw e
                    }
                } catch (e: Exception) {
                    DeviceKeys.delete(alias)
                    throw e.asUnavailable()
                }
            return helloAfterPairing(link, record, signer)
        }

        /** The first `hello` on the link a pairing was made on. The link is closed when it fails. */
        @Suppress("TooGenericExceptionCaught")
        private suspend fun helloAfterPairing(
            link: StudioLink,
            record: StudioPairing,
            signer: StudioSigner,
        ): OpenStudio {
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
                val named = PairingLink(found.link, found.noiseKey, found.name.ifBlank { offer.name })
                return storeRecord(named, offer.fingerprint, offer.bleService, device, alias, phoneKeys) to found.link
            } catch (e: Exception) {
                found.link.close()
                throw e
            }
        }

        /** Stores the pairing Studio accepted, with the phone's Noise key sealed by the Keystore. */
        @Suppress("LongParameterList")
        private suspend fun storeRecord(
            found: PairingLink,
            fingerprint: ByteArray,
            bleService: String,
            device: PairedDevice,
            alias: String,
            phoneKeys: NoiseKeyPair,
        ): StudioPairing {
            val (iv, sealed) = noiseKeys.seal(phoneKeys)
            val record =
                StudioPairing(
                    id = newId(),
                    name = found.name.ifBlank { "Studio" },
                    fingerprint = StudioWire.base64Url(fingerprint),
                    studioNoiseKey = StudioWire.base64Url(found.noiseKey),
                    bleService = bleService,
                    deviceId = device.deviceId,
                    keyAlias = alias,
                    phoneNoiseKey = StudioWire.base64Url(phoneKeys.publicKey),
                    phoneNoiseIv = iv,
                    phoneNoiseSealed = sealed,
                )
            store.add(record)
            return record
        }

        /** The Studios on the account's relay, for pairing through folksonic. Throws [StudioUnavailable]. */
        suspend fun relayStudios(): List<RelayStudio> =
            relay?.studios() ?: throw StudioUnavailable(StudioUnavailable.Reason.NOT_OFFERED)

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
                    // Bluetooth and the relay give null when they cannot be used; the next transport is tried then.
                    val link =
                        when (transport) {
                            StudioTransports.BLUETOOTH -> openBluetoothForConnect(record, fingerprint, noiseKey)
                            StudioTransports.RELAY -> openRelayForConnect(record, fingerprint, noiseKey)
                            else -> openNetwork(record, fingerprint, noiseKey)
                        }
                    if (link != null) return link
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

        /**
         * The relay link to [record], or null when the app is not connected to folksonic, the server has no relay, or
         * the pairing has no Noise key (made before Bluetooth and the relay existed; pair again to use them).
         */
        @Suppress("ReturnCount")
        private suspend fun openRelayForConnect(
            record: StudioPairing,
            fingerprint: ByteArray,
            noiseKey: ByteArray,
        ): StudioLink? {
            val gateway = relay ?: return null
            val phoneKeys = runCatching { noiseKeys.open(record) }.getOrNull() ?: return null
            return gateway.open(fingerprint, phoneKeys) { spki, noise ->
                StudioWire.fingerprint(spki, noise).contentEquals(fingerprint) && noise.contentEquals(noiseKey)
            }
        }

        /**
         * Forgets a pairing: its record and its device key. When folksonic is connected, the Studio's record is also
         * removed from the account, which ends its relay connection. Returns whether the account was cleared too; when
         * it was not, the Studio stays listed on the account until it is removed there.
         */
        suspend fun forget(record: StudioPairing): Boolean {
            val fingerprint = StudioWire.decodeBase64Url(record.fingerprint, FINGERPRINT_BYTES)
            val removedFromAccount = fingerprint != null && relay?.remove(fingerprint) == true
            store.remove(record.id)
            DeviceKeys.delete(record.keyAlias)
            return removedFromAccount
        }

        /** The address mDNS finds for [fingerprint]. Pairing has no hint yet, so only mDNS is used there. */
        private suspend fun locate(fingerprint: ByteArray): HttpUrl =
            findOnNetwork(fingerprint) ?: throw StudioUnavailable(StudioUnavailable.Reason.NOT_FOUND)

        /**
         * The address mDNS finds for [fingerprint], or null. Refused at once while the app may not use the local
         * network, rather than waiting for a discovery that Android blocks (#124).
         */
        private suspend fun findOnNetwork(fingerprint: ByteArray): HttpUrl? {
            if (!LocalNetworkAccess.allowed()) throw StudioUnavailable(StudioUnavailable.Reason.LOCAL_NETWORK_BLOCKED)
            return discovery.find(StudioWire.mdnsTag(fingerprint))
        }

        /**
         * The network link to [record]: the address mDNS finds first, then the addresses Studio reported over
         * Bluetooth. Each one must pass the fingerprint and key check, so a wrong address fails and the next is tried.
         */
        private suspend fun openNetwork(
            record: StudioPairing,
            fingerprint: ByteArray,
            noiseKey: ByteArray,
        ): StudioLink {
            val found = findOnNetwork(fingerprint)
            // The record passed in may be older than the hint: a Bluetooth session saves its hint as it arrives.
            val current = store.find(record.id) ?: record
            var failure = StudioUnavailable(StudioUnavailable.Reason.NOT_FOUND)
            for (address in StudioHint.candidates(found, current.hintAddresses, current.hintPort)) {
                try {
                    return openChecked(address, noiseKey, fingerprint)
                } catch (e: StudioUnavailable) {
                    failure = e
                }
            }
            throw failure
        }

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
                onHint = { hint ->
                    scope.launch { store.saveHint(record.id, hint.addresses, hint.port) }
                },
                counter =
                    object : StudioCounter {
                        override suspend fun advance(): Long =
                            store.advanceCounter(record.id) ?: throw StudioUnavailable(StudioUnavailable.Reason.FAILED)
                    },
                scope = scope,
                // Studio ends a relay session after 90 silent seconds; Bluetooth and the network need no keepalive.
                keepAliveMillis = if (link is RelayStudioLink) RELAY_KEEPALIVE_MILLIS else 0L,
            )

        private companion object {
            const val TAG = "StudioRemote"
            const val FINGERPRINT_BYTES = 32
            const val MILLIS_PER_SECOND = 1_000L

            /** A `get_state` this often keeps a relay session open: the protocol asks for one every 30 s at most. */
            const val RELAY_KEEPALIVE_MILLIS = 25_000L
        }
    }
