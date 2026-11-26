package org.dancesonic.player.studio

import kotlinx.coroutines.withTimeout
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** The pairing Studio accepted: the device id it gave the phone. */
data class PairedDevice(
    val deviceId: String,
)

/**
 * Pairs the phone with the Studio on [link], using the one-time secret from the QR code. Studio shows the phone's name
 * and waits for the user; this returns only when Studio has accepted, and throws when it refuses or the code is no
 * longer valid. The connection stays open afterwards, and the caller goes on with `hello`.
 */
class StudioPairer(
    private val signer: StudioSigner,
    /**
     * The public half of the phone's X25519 Noise key for this Studio, which Studio registers for Bluetooth and the
     * relay. Always a real key (#104); the random placeholder of the first version could not open either.
     */
    val noiseKey: ByteArray,
    private val decisionTimeoutMillis: Long = DECISION_TIMEOUT_MILLIS,
) {
    /** Sends the pairing request and waits for Studio's answer. */
    suspend fun pair(
        link: StudioLink,
        offer: StudioOffer,
        phoneName: String,
    ): PairedDevice {
        val devicePoint = signer.devicePoint()
        val transcript = StudioWire.pairTranscript(offer.fingerprint, devicePoint, noiseKey, link.binding)
        link.send(
            StudioOutgoing.pairRequest(
                name = phoneName,
                devicePoint = devicePoint,
                noiseKey = noiseKey,
                proof = hmac(offer.secret, transcript),
                signature = signer.sign(transcript),
            ),
        )
        return withTimeout(decisionTimeoutMillis) { awaitPairDecision(link) }
    }

    companion object {
        /** How long the user has to accept the phone in Studio. */
        const val DECISION_TIMEOUT_MILLIS = 3 * 60 * 1000L

        /** HMAC-SHA256 of [message] with [key]: the pairing proof. */
        internal fun hmac(
            key: ByteArray,
            message: ByteArray,
        ): ByteArray =
            Mac.getInstance("HmacSHA256").run {
                init(SecretKeySpec(key, "HmacSHA256"))
                doFinal(message)
            }
    }
}

/**
 * Pairs through folksonic's relay by comparing codes (#104): there is no QR secret, so the request is only signed, and
 * the user allows the phone in Studio when both screens show the same [RelayWire.comparisonCode] of the link. Studio
 * refuses this request on any other transport.
 */
class StudioCodePairer(
    private val signer: StudioSigner,
    private val decisionTimeoutMillis: Long = StudioPairer.DECISION_TIMEOUT_MILLIS,
) {
    /**
     * Sends `pair_code_request` to the Studio with [fingerprint] and waits for its answer. [noiseKey] must be the
     * phone's Noise static key of this very session, which Studio checks.
     */
    suspend fun pair(
        link: StudioLink,
        fingerprint: ByteArray,
        noiseKey: ByteArray,
        phoneName: String,
    ): PairedDevice {
        val devicePoint = signer.devicePoint()
        val transcript = RelayWire.pairCodeTranscript(fingerprint, devicePoint, noiseKey, link.binding)
        link.send(StudioOutgoing.pairCodeRequest(phoneName, devicePoint, noiseKey, signer.sign(transcript)))
        return withTimeout(decisionTimeoutMillis) { awaitPairDecision(link) }
    }
}

/** Waits for Studio's decision on a pairing request: accepted, rejected, refused, or a dropped link. */
@Suppress("ThrowsCount") // each throw is a distinct outcome Studio can give: refusal, error, or a dead link
internal suspend fun awaitPairDecision(link: StudioLink): PairedDevice {
    for (text in link.incoming) {
        when (val message = StudioMessage.parse(text) ?: continue) {
            StudioMessage.PairPending -> Unit
            is StudioMessage.PairAccepted -> return PairedDevice(message.device)
            is StudioMessage.PairRejected -> throw StudioProtocolError("rejected")
            is StudioMessage.Error -> throw StudioProtocolError(message.code)
            is StudioMessage.Bye -> throw StudioProtocolError(message.reason)
            else -> Unit
        }
    }
    throw StudioProtocolError("closed")
}
