package org.dancesonic.player.studio

import kotlinx.coroutines.withTimeout
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** The pairing Studio accepted: the device id it gave the phone. */
data class PairedDevice(val deviceId: String)

/**
 * Pairs the phone with the Studio on [link], using the one-time secret from the QR code. Studio shows the phone's name
 * and waits for the user; this returns only when Studio has accepted, and throws when it refuses or the code is no
 * longer valid. The connection stays open afterwards, and the caller goes on with `hello`.
 */
class StudioPairer(
    private val signer: StudioSigner,
    /**
     * The phone's Noise key, which Studio registers for the Bluetooth transport. Network pairing never uses it, but
     * the request must carry a 32-byte key. Until Bluetooth is added this is random, so a Bluetooth pairing needs a
     * real X25519 key and a new pairing.
     */
    val noiseKey: ByteArray = randomNoiseKey(),
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
        return withTimeout(decisionTimeoutMillis) { awaitDecision(link) }
    }

    @Suppress("ThrowsCount") // each throw is a distinct outcome Studio can give: refusal, error, or a dead link
    private suspend fun awaitDecision(link: StudioLink): PairedDevice {
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

    companion object {
        /** How long the user has to accept the phone in Studio. */
        const val DECISION_TIMEOUT_MILLIS = 3 * 60 * 1000L

        private const val NOISE_KEY_BYTES = 32

        /** A random 32-byte key; see [StudioPairer.noiseKey]. */
        fun randomNoiseKey(): ByteArray = ByteArray(NOISE_KEY_BYTES).also { SecureRandom().nextBytes(it) }

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
