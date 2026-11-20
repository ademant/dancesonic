package org.dancesonic.player.studio

import org.dancesonic.player.security.KeystoreCipher
import org.dancesonic.player.security.SecretCipher
import org.dancesonic.player.studio.noise.NoiseKeyPair
import java.util.Base64

/**
 * The phone's Noise key for one paired Studio. The public half is registered with Studio at pairing; the private half
 * is kept only encrypted, with a key in the Android Keystore, and is needed for every Bluetooth connection.
 */
class PhoneNoiseKeys internal constructor(private val cipher: SecretCipher = KeystoreCipher(ALIAS)) {
    /** Returns the IV and the ciphertext of the private key, both Base64, for the pairing record. */
    fun seal(keys: NoiseKeyPair): Pair<String, String> =
        cipher.encrypt(Base64.getEncoder().encodeToString(keys.privateKey))

    /** The key pair of [pairing], or null when none was stored (a pairing made before Bluetooth existed). */
    fun open(pairing: StudioPairing): NoiseKeyPair? {
        val iv = pairing.phoneNoiseIv ?: return null
        val sealed = pairing.phoneNoiseSealed ?: return null
        val private = Base64.getDecoder().decode(cipher.decrypt(iv, sealed))
        return NoiseKeyPair.fromPrivate(private)
    }

    private companion object {
        const val ALIAS = "dancesonic_studio_noise"
    }
}
