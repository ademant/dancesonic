package org.dancesonic.player.subsonic

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/**
 * The pin must equal folksonic's: the SHA-256 of the public key info, not of the whole certificate. The expected value
 * was computed with openssl from this certificate:
 *   openssl x509 -in cert.pem -pubkey -noout | openssl pkey -pubin -outform DER | openssl dgst -sha256 -binary | base64
 * The certificate is a throwaway test fixture; its private key is not stored.
 */
class LanPinFormatTest {
    @Test
    fun `the pin is folksonic's sha256 of the public key info`() {
        assertEquals("sha256/AIi+HIhAaC/H6hu+vF2gpQ/97rDOTGrt52bobf0fjdM=", LanTls.fingerprint(certificate()))
    }

    private fun certificate(): X509Certificate =
        CertificateFactory.getInstance(
            "X.509",
        ).generateCertificate(ByteArrayInputStream(PEM.toByteArray())) as X509Certificate

    private companion object {
        val PEM =
            """
            -----BEGIN CERTIFICATE-----
            MIIBkzCCATqgAwIBAgIUZLPPR/f46p8KdjR/FxqlYaotNrUwCgYIKoZIzj0EAwIw
            FzEVMBMGA1UEAwwMMTkyLjE2OC4xLjEwMB4XDTI2MTExMjEyNDMzMloXDTM2MTEw
            OTEyNDMzMlowFzEVMBMGA1UEAwwMMTkyLjE2OC4xLjEwMFkwEwYHKoZIzj0CAQYI
            KoZIzj0DAQcDQgAEOY+VJOtJx//MH+X4YXrLV1jajFmdKckyYwVPFL1tsoarXeJ2
            qE3quYnENk7hKspTLDJI6ojEuCPykUgkUlDY3aNkMGIwHQYDVR0OBBYEFEb7toHu
            LPsXX1jxTzKIfBMDEs8PMB8GA1UdIwQYMBaAFEb7toHuLPsXX1jxTzKIfBMDEs8P
            MA8GA1UdEwEB/wQFMAMBAf8wDwYDVR0RBAgwBocEwKgBCjAKBggqhkjOPQQDAgNH
            ADBEAiBJH4Vk2UAYAl0sZb6PV3jsHDAMhRBc0jclUgN6Vbb0KQIgTJTFXR+1+618
            1v6I6UEnxygoEVYjcKR7AyalWT8olb0=
            -----END CERTIFICATE-----
            """.trimIndent()
    }
}
