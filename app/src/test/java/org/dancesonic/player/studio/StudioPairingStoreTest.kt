package org.dancesonic.player.studio

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

internal fun pairing(
    id: String,
    name: String = "Studio $id",
    sealed: String? = "sealed",
    bleService: String = "6d0f6c1a-6b0e-4a5e-9a52-6a1d3b6f0c11",
) = StudioPairing(
    id = id,
    name = name,
    fingerprint = "f-$id",
    studioNoiseKey = "s-$id",
    bleService = bleService,
    deviceId = "d-$id",
    keyAlias = "a-$id",
    phoneNoiseKey = "k-$id",
    phoneNoiseIv = sealed?.let { "iv" },
    phoneNoiseSealed = sealed,
)

/** Several paired Studios: their names, when they were reached, and which one was last played on (#88). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class StudioPairingStoreTest {
    private val store =
        StudioPairingStore(
            PreferenceDataStoreFactory.create(
                produceFile = {
                    File(
                        ApplicationProvider.getApplicationContext<android.content.Context>().cacheDir,
                        "studio-${UUID.randomUUID()}.preferences_pb",
                    )
                },
            ),
        )

    @Test
    fun `a pairing offers Bluetooth and the relay only with its Noise key`() {
        assertEquals(listOf("ble", "relay", "network"), pairing("a").transports())
        assertEquals(listOf("network"), pairing("b", sealed = null).transports())
        assertEquals(listOf("relay", "network"), pairing("c", bleService = "not-a-uuid").transports())
    }

    @Test
    fun `a session records when and how the Studio was reached`() =
        runBlocking {
            store.add(pairing("a"))
            store.add(pairing("b"))

            store.markConnected("a", "relay", atMs = 1_700_000_000_000L)

            val a = store.find("a")
            assertEquals(1_700_000_000_000L, a?.lastConnectedAtMs)
            assertEquals("relay", a?.lastTransport)
            assertNull(store.find("b")?.lastConnectedAtMs)
        }

    @Test
    fun `renaming changes only that pairing`() =
        runBlocking {
            store.add(pairing("a"))
            store.add(pairing("b"))

            store.rename("b", "Venue laptop")

            assertEquals(listOf("Studio a", "Venue laptop"), store.list().map { it.name })
        }

    @Test
    fun `the Studio last played on is remembered, and forgotten with its pairing`() =
        runBlocking {
            store.add(pairing("a"))
            store.add(pairing("b"))

            store.setLastActive("a")
            assertEquals("a", store.lastActive.first())

            store.remove("b")
            assertEquals("a", store.lastActive.first())
            store.remove("a")
            assertNull(store.lastActive.first())
        }

    @Test
    fun `choosing the phone forgets the last Studio`() =
        runBlocking {
            store.setLastActive("a")
            store.setLastActive(null)

            assertNull(store.lastActive.first())
        }
}
