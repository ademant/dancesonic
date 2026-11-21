package org.dancesonic.player.studio

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.dancesonic.player.studio.noise.NoiseKeyPair
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/** Opening Studio links through folksonic's relay, with a fake relay in place of folksonic (#104). */
class RelayGatewayTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val tlsSpki = ByteArray(91) { (it * 5).toByte() }
    private val studioKey = NoiseKeyPair.generate()
    private val fingerprint = StudioWire.fingerprint(tlsSpki, studioKey.publicKey)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    private class FakeEndpoint(private val pipe: () -> RelayPipe) : RelayEndpoint {
        val published = mutableListOf<Pair<String, String>>()
        val opened = mutableListOf<Pair<String, String>>()
        var refuse: Exception? = null
        var studios: List<RelayStudio> = emptyList()

        override suspend fun studios(): List<RelayStudio> {
            refuse?.let { throw it }
            return studios
        }

        override suspend fun publishPhone(
            phoneId: String,
            name: String,
        ) {
            refuse?.let { throw it }
            published += phoneId to name
        }

        override suspend fun open(
            phoneId: String,
            studioId: String,
        ): RelayPipe {
            opened += phoneId to studioId
            return pipe()
        }
    }

    private val store by lazy {
        StudioPairingStore(PreferenceDataStoreFactory.create(produceFile = { File(folder.root, "s.preferences_pb") }))
    }

    private fun gateway(endpoint: RelayEndpoint?) =
        RelayGateway({ endpoint }, store, scope, phoneName = { "Pixel" }, stepTimeoutMillis = 2_000)

    private fun matches(
        spki: ByteArray,
        noise: ByteArray,
    ) = StudioWire.fingerprint(spki, noise).contentEquals(fingerprint)

    @Test
    fun `without folksonic the relay is skipped`() =
        runBlocking<Unit> {
            assertNull(gateway(null).open(fingerprint, NoiseKeyPair.generate(), ::matches))
            val error = assertThrows(StudioUnavailable::class.java) { runBlocking { gateway(null).studios() } }
            assertEquals(StudioUnavailable.Reason.NOT_OFFERED, error.reason)
        }

    @Test
    fun `a server without the relay is skipped, so the next transport is tried`() =
        runBlocking<Unit> {
            val endpoint = FakeEndpoint { error("not opened") }
            endpoint.refuse = RelayNotOffered()

            assertNull(gateway(endpoint).open(fingerprint, NoiseKeyPair.generate(), ::matches))
        }

    @Test
    fun `the phone publishes its record and reaches Studio under the id from its fingerprint`() =
        runBlocking<Unit> {
            val endpoint = FakeEndpoint { FakeRelayStudio(studioKey, tlsSpki, fingerprint) }
            val gateway = gateway(endpoint)

            val link = gateway.open(fingerprint, NoiseKeyPair.generate(), ::matches)
            gateway.open(fingerprint, NoiseKeyPair.generate(), ::matches)?.close()

            assertNotNull(link)
            val phoneId = endpoint.published.first().first
            assertTrue(RelayWire.validDeviceId(phoneId))
            assertEquals("Pixel", endpoint.published.first().second)
            assertEquals(listOf(phoneId to RelayWire.studioDeviceId(fingerprint)), endpoint.opened.distinct())
            assertEquals(2, endpoint.opened.size)
            link?.close()
        }

    @Test
    fun `a Studio that is not the paired one is refused`() =
        runBlocking<Unit> {
            val endpoint = FakeEndpoint { FakeRelayStudio(NoiseKeyPair.generate(), tlsSpki, fingerprint) }

            val error =
                assertThrows(StudioUnavailable::class.java) {
                    runBlocking { gateway(endpoint).open(fingerprint, NoiseKeyPair.generate(), ::matches) }
                }

            assertEquals(StudioUnavailable.Reason.NOT_THIS_STUDIO, error.reason)
        }

    @Test
    fun `a relay that fails is reported, and listing Studios fails the same way`() =
        runBlocking<Unit> {
            val endpoint = FakeEndpoint { error("not opened") }
            endpoint.refuse = IOException("down")

            val opening =
                assertThrows(StudioUnavailable::class.java) {
                    runBlocking { gateway(endpoint).open(fingerprint, NoiseKeyPair.generate(), ::matches) }
                }
            val listing = assertThrows(StudioUnavailable::class.java) { runBlocking { gateway(endpoint).studios() } }

            assertEquals(StudioUnavailable.Reason.FAILED, opening.reason)
            assertEquals(StudioUnavailable.Reason.FAILED, listing.reason)
        }
}
