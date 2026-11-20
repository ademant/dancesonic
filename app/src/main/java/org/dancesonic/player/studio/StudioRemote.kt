package org.dancesonic.player.studio

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import okhttp3.HttpUrl
import org.dancesonic.player.subsonic.DeviceKeys
import java.util.UUID

/** Why a Studio could not be paired or reached, for the message the user sees. */
class StudioUnavailable(val reason: Reason, cause: Throwable? = null) : Exception(reason.name, cause) {
    enum class Reason {
        /** The code is not a DanceSonic Studio code, or is for another version. */
        BAD_CODE,

        /** The code has expired. Open "Pair a phone" in Studio again. */
        EXPIRED,

        /** No Studio with this fingerprint answers on the local network. */
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

/** An open session with a paired Studio, and the record it belongs to. */
class OpenStudio(val record: StudioPairing, val session: StudioSession)

/**
 * Pairs with a Studio from its QR code, and reconnects to a paired one. It joins discovery, the fingerprint checks,
 * the pairing and the session. A pairing is stored only after Studio has accepted the phone.
 */
class StudioRemote(
    private val store: StudioPairingStore,
    private val discovery: StudioDiscovery,
    private val connector: StudioConnector,
    private val scope: CoroutineScope,
    private val clock: () -> Long = { System.currentTimeMillis() / MILLIS_PER_SECOND },
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    /**
     * Pairs with the Studio that shows [code], as [phoneName], and leaves the session open. Throws [StudioUnavailable]
     * with the reason. When pairing fails the new device key is removed again. A pairing that Studio accepted is kept
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
        val pairer = StudioPairer(signer)
        val (record, link) =
            try {
                pairWithStudio(offer, phoneName, alias, pairer)
            } catch (e: Exception) {
                DeviceKeys.delete(alias)
                throw e.asUnavailable()
            }
        val session = sessionOn(link, record, signer)
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
        return OpenStudio(record, session)
    }

    /** The pairing itself: finds Studio, checks it, sends the request, and stores the record once accepted. */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun pairWithStudio(
        offer: StudioOffer,
        phoneName: String,
        alias: String,
        pairer: StudioPairer,
    ): Pair<StudioPairing, StudioLink> {
        val address = locate(offer.fingerprint)
        val info = info(address)
        if (!info.fingerprint.contentEquals(offer.fingerprint)) {
            throw StudioUnavailable(StudioUnavailable.Reason.NOT_THIS_STUDIO)
        }
        val link = openChecked(address, info.noiseKey, offer.fingerprint)
        try {
            val device = pairer.pair(link, offer, phoneName)
            val record =
                StudioPairing(
                    id = newId(),
                    name = info.name.ifBlank { offer.name }.ifBlank { "Studio" },
                    fingerprint = StudioWire.base64Url(offer.fingerprint),
                    studioNoiseKey = StudioWire.base64Url(info.noiseKey),
                    bleService = offer.bleService,
                    deviceId = device.deviceId,
                    keyAlias = alias,
                    phoneNoiseKey = StudioWire.base64Url(pairer.noiseKey),
                )
            store.add(record)
            return record to link
        } catch (e: Exception) {
            link.close()
            throw e
        }
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

    /** Connects to a paired Studio and says hello. Throws [StudioUnavailable] when it cannot be reached or refuses. */
    @Suppress("ThrowsCount", "TooGenericExceptionCaught")
    suspend fun connect(record: StudioPairing): OpenStudio {
        val fingerprint =
            StudioWire.decodeBase64Url(record.fingerprint, FINGERPRINT_BYTES)
                ?: throw StudioUnavailable(StudioUnavailable.Reason.FAILED)
        val noiseKey =
            StudioWire.decodeBase64Url(record.studioNoiseKey, StudioWire.NOISE_KEY_BYTES)
                ?: throw StudioUnavailable(StudioUnavailable.Reason.FAILED)
        val signer = KeystoreStudioSigner(record.keyAlias)
        var link: StudioLink? = null
        try {
            val address = locate(fingerprint)
            link = openChecked(address, noiseKey, fingerprint)
            val session = sessionOn(link, record, signer)
            session.hello()
            return OpenStudio(record, session)
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

    /** Forgets a pairing: its record and its device key. Studio keeps the phone listed until it is revoked there. */
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
        const val FINGERPRINT_BYTES = 32
        const val MILLIS_PER_SECOND = 1_000L
    }
}
