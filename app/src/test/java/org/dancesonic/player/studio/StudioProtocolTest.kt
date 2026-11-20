package org.dancesonic.player.studio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StudioProtocolTest {
    @Test
    fun `commands are written as the protocol names them, with the fields in order`() {
        assertEquals("""{"cmd":"play"}""", StudioCommand.Play.body())
        assertEquals("""{"cmd":"seek","position_ms":1234}""", StudioCommand.Seek(1234).body())
        assertEquals("""{"cmd":"set_rate","rate":1.25}""", StudioCommand.SetRate(1.25).body())
        assertEquals(
            """{"cmd":"play_songs","songs":["a","b"],"start":1}""",
            StudioCommand.PlaySongs(listOf("a", "b"), start = 1).body(),
        )
        assertEquals("""{"cmd":"next_of_dance","tag_id":42}""", StudioCommand.NextOfDance(42).body())
    }

    @Test
    fun `a state message gives the playback as the phone shows it`() {
        val text =
            """
            {"type":"state","playing":true,"song":"s1","index":0,"position_ms":500,"length_ms":200000,
             "rate":1.0,"bpm":null,"queue":["s1","s2"],"dance":{"tag_id":3,"name":"Gavotte"}}
            """.trimIndent()

        val state = (StudioMessage.parse(text) as StudioMessage.State).state

        assertEquals(true, state.playing)
        assertEquals("s1", state.song)
        assertEquals(500L, state.positionMs)
        assertEquals(200_000L, state.lengthMs)
        assertEquals(listOf("s1", "s2"), state.queue)
        assertEquals(3L, state.danceTagId)
        assertEquals("Gavotte", state.danceName)
    }

    @Test
    fun `a result with an error carries the code`() {
        val result =
            StudioMessage.parse("""{"type":"result","id":4,"ok":false,"error":"unknown_song"}""")
                as StudioMessage.Result

        assertEquals(4L, result.id)
        assertEquals(false, result.ok)
        assertEquals("unknown_song", result.error)
    }

    @Test
    fun `a welcome names the transport and its features`() {
        val welcome =
            StudioMessage.parse(
                """{"type":"welcome","protocol":1,"name":"Studio",""" +
                    """"transport":"network","features":["playback","cover"]}""",
            ) as StudioMessage.Welcome

        assertEquals(1, welcome.protocol)
        assertEquals("network", welcome.transport)
        assertEquals(listOf("playback", "cover"), welcome.features)
    }

    @Test
    fun `a type this app does not know is kept, not an error`() {
        assertEquals(StudioMessage.Other("future"), StudioMessage.parse("""{"type":"future"}"""))
    }

    @Test
    fun `text that is not a message is no message`() {
        assertNull(StudioMessage.parse("not json"))
        assertNull(StudioMessage.parse("""{"no":"type"}"""))
    }

    @Test
    fun `pair_request carries the device key, Noise key, proof and signature as base64url`() {
        val text =
            StudioOutgoing.pairRequest(
                name = "Phone",
                devicePoint = ByteArray(65),
                noiseKey = ByteArray(32),
                proof = ByteArray(32),
                signature = ByteArray(70),
            )

        assertEquals(
            setOf("type", "v", "name", "device_key", "noise_key", "proof", "sig"),
            kotlinx.serialization.json.Json.parseToJsonElement(
                text,
            ).let { (it as kotlinx.serialization.json.JsonObject).keys },
        )
        assertEquals(true, text.contains("\"v\":1"))
    }
}
