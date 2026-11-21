package org.dancesonic.player.studio

import kotlinx.coroutines.CoroutineScope
import org.dancesonic.player.studio.noise.NoiseKeyPair
import java.io.IOException

/**
 * Reaches Studios through folksonic's relay (#104), on the server the app is connected to. Lists the account's
 * Studios for pairing by code, and opens the handshaked link to one. When there is no server, or it has no relay,
 * [open] returns null so the caller tries the next transport.
 */
class RelayGateway(
    /** The relay of the connected server, or null when the app is not connected to folksonic. */
    private val endpoint: () -> RelayEndpoint?,
    private val store: StudioPairingStore,
    private val scope: CoroutineScope,
    /** The name Studio shows for this phone. */
    private val phoneName: () -> String,
    private val stepTimeoutMillis: Long = RelayStudioOpener.STEP_TIMEOUT_MILLIS,
) {
    /** The Studios on the account. Throws [StudioUnavailable]: `NOT_OFFERED` without a relay, `FAILED` otherwise. */
    @Suppress("ThrowsCount")
    suspend fun studios(): List<RelayStudio> {
        val relay = endpoint() ?: throw StudioUnavailable(StudioUnavailable.Reason.NOT_OFFERED)
        return try {
            relay.studios()
        } catch (e: RelayNotOffered) {
            throw StudioUnavailable(StudioUnavailable.Reason.NOT_OFFERED, e)
        } catch (e: IOException) {
            throw StudioUnavailable(StudioUnavailable.Reason.FAILED, e)
        } catch (e: kotlinx.serialization.SerializationException) {
            throw StudioUnavailable(StudioUnavailable.Reason.FAILED, e)
        }
    }

    /**
     * Opens a link to the Studio with [fingerprint], or returns null when the relay cannot be used. Throws
     * [StudioUnavailable]: `NOT_THIS_STUDIO` when [isStudio] refuses the Studio that answered, `NOT_FOUND` when
     * Studio is not on the relay or does not answer.
     */
    @Suppress("ThrowsCount", "SwallowedException")
    suspend fun open(
        fingerprint: ByteArray,
        phone: NoiseKeyPair,
        isStudio: (tlsSpki: ByteArray, noiseKey: ByteArray) -> Boolean,
    ): StudioLink? {
        val relay = endpoint() ?: return null
        val pipe =
            try {
                val phoneId = store.relayPhoneId()
                relay.publishPhone(phoneId, phoneName())
                relay.open(phoneId, RelayWire.studioDeviceId(fingerprint))
            } catch (e: RelayNotOffered) {
                // An older folksonic, or another server: the next transport is tried.
                return null
            } catch (e: IOException) {
                throw StudioUnavailable(StudioUnavailable.Reason.FAILED, e)
            }
        return try {
            RelayStudioOpener.open(pipe, fingerprint, phone, scope, isStudio, stepTimeoutMillis)
        } catch (e: RelayStudioException) {
            val reason =
                if (e.notThisStudio) StudioUnavailable.Reason.NOT_THIS_STUDIO else StudioUnavailable.Reason.NOT_FOUND
            throw StudioUnavailable(reason, e)
        }
    }
}
