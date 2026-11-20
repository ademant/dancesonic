package org.dancesonic.player.studio

import org.dancesonic.player.security.SecretCipher
import org.dancesonic.player.studio.noise.NoiseKeyPair
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PhoneNoiseKeysTest {
    /** Stands in for the Keystore: reversible, so the round trip shows what is stored and read back. */
    private class ReversibleCipher : SecretCipher {
        override fun encrypt(plain: String): Pair<String, String> = "iv" to plain.reversed()

        override fun decrypt(
            ivB64: String,
            ciphertextB64: String,
        ): String = ciphertextB64.reversed()
    }

    private fun pairing(
        iv: String?,
        sealed: String?,
    ) = StudioPairing(
        id = "p",
        name = "Studio",
        fingerprint = "f",
        studioNoiseKey = "s",
        bleService = "b",
        deviceId = "d",
        keyAlias = "a",
        phoneNoiseKey = "k",
        phoneNoiseIv = iv,
        phoneNoiseSealed = sealed,
    )

    @Test
    fun `the phone's Noise key pair is stored and read back as the same key`() {
        val keys = PhoneNoiseKeys(ReversibleCipher())
        val keyPair = NoiseKeyPair.generate()
        val (iv, sealed) = keys.seal(keyPair)

        val opened = keys.open(pairing(iv, sealed))

        assertArrayEquals(keyPair.privateKey, opened?.privateKey)
        assertArrayEquals(keyPair.publicKey, opened?.publicKey)
    }

    @Test
    fun `a pairing made before Bluetooth has no key and gives none`() {
        assertNull(PhoneNoiseKeys(ReversibleCipher()).open(pairing(iv = null, sealed = null)))
    }
}
