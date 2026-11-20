package org.dancesonic.player.studio

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** A command the phone sends (the `body` of a `command` message). Names and fields are those of the protocol. */
sealed interface StudioCommand {
    data object Play : StudioCommand

    data object Pause : StudioCommand

    data object Toggle : StudioCommand

    data object Next : StudioCommand

    data object Previous : StudioCommand

    data object GetState : StudioCommand

    data class Seek(val positionMs: Long) : StudioCommand

    data class SetRate(val rate: Double) : StudioCommand

    data class SetBpm(val bpm: Double) : StudioCommand

    data class PlaySongs(val songs: List<String>, val start: Int = 0) : StudioCommand

    data class Enqueue(val songs: List<String>) : StudioCommand

    data class PlayDance(val tagId: Long) : StudioCommand

    data class NextOfDance(val tagId: Long) : StudioCommand

    /** The JSON text of the body. Its exact UTF-8 bytes are what the signature covers. */
    fun body(): String =
        when (this) {
            Play -> cmd("play")
            Pause -> cmd("pause")
            Toggle -> cmd("toggle")
            Next -> cmd("next")
            Previous -> cmd("previous")
            GetState -> cmd("get_state")
            is Seek -> cmdWith("seek") { put("position_ms", positionMs) }
            is SetRate -> cmdWith("set_rate") { put("rate", rate) }
            is SetBpm -> cmdWith("set_bpm") { put("bpm", bpm) }
            is PlaySongs ->
                cmdWith("play_songs") {
                    put("songs", songs.toJsonArray())
                    put("start", start)
                }
            is Enqueue -> cmdWith("enqueue") { put("songs", songs.toJsonArray()) }
            is PlayDance -> cmdWith("play_dance") { put("tag_id", tagId) }
            is NextOfDance -> cmdWith("next_of_dance") { put("tag_id", tagId) }
        }

    private fun cmd(name: String): String = cmdWith(name) {}

    private fun cmdWith(
        name: String,
        fields: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit,
    ): String =
        buildJsonObject {
            put("cmd", name)
            fields()
        }.toString()

    private fun List<String>.toJsonArray(): JsonArray = buildJsonArray { forEach { add(JsonPrimitive(it)) } }
}

/** Playback as Studio reports it (`state`). Song and dance references are IDs; names come from the phone's cache. */
data class StudioPlayState(
    val playing: Boolean,
    val song: String?,
    val index: Int?,
    val positionMs: Long,
    val lengthMs: Long?,
    val rate: Double,
    val bpm: Double?,
    val queue: List<String>,
    val danceTagId: Long?,
    val danceName: String?,
)

/** A message from Studio. Unknown types are kept as [Other] so a newer Studio does not break the session. */
sealed interface StudioMessage {
    data class Identity(val tlsSpki: String) : StudioMessage

    data object PairPending : StudioMessage

    data class PairAccepted(val device: String) : StudioMessage

    data class PairRejected(val reason: String) : StudioMessage

    data class Welcome(
        val protocol: Int,
        val name: String,
        val transport: String,
        val features: List<String>,
    ) : StudioMessage

    data class Result(val id: Long, val ok: Boolean, val error: String?, val data: JsonElement?) : StudioMessage

    data class State(val state: StudioPlayState) : StudioMessage

    data class NetworkHint(val addresses: List<String>, val port: Int) : StudioMessage

    data class Error(val code: String) : StudioMessage

    data class Bye(val reason: String) : StudioMessage

    data class Other(val type: String) : StudioMessage

    companion object {
        private fun JsonObject.strings(key: String): List<String> =
            this[key]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()

        /** Reads one message. Returns null when the text is not a JSON object with a `type`. */
        @Suppress("ReturnCount", "CyclomaticComplexMethod") // one short branch per message type
        fun parse(text: String): StudioMessage? {
            val obj =
                runCatching { kotlinx.serialization.json.Json.parseToJsonElement(text).jsonObject }.getOrNull()
                    ?: return null
            val type = obj.str("type") ?: return null
            return when (type) {
                "identity" -> Identity(obj.str("tls_spki") ?: return null)
                "pair_pending" -> PairPending
                "pair_accepted" -> PairAccepted(obj.str("device") ?: return null)
                "pair_rejected" -> PairRejected(obj.str("reason") ?: "")
                "welcome" ->
                    Welcome(
                        protocol = obj.int("protocol") ?: return null,
                        name = obj.str("name") ?: "",
                        transport = obj.str("transport") ?: return null,
                        features = obj.strings("features"),
                    )
                "result" ->
                    Result(
                        id = obj.long("id") ?: return null,
                        ok = obj["ok"]?.jsonPrimitive?.booleanOrNull ?: return null,
                        error = obj.str("error"),
                        data = obj["data"]?.takeUnless { it is JsonNull },
                    )
                "state" -> State(parseState(obj))
                "network_hint" ->
                    NetworkHint(
                        addresses = obj.strings("addresses"),
                        port = obj.int("port") ?: return null,
                    )
                "error" -> Error(obj.str("code") ?: "failed")
                "bye" -> Bye(obj.str("reason") ?: "")
                else -> Other(type)
            }
        }

        private fun parseState(obj: JsonObject): StudioPlayState {
            val dance = obj["dance"]?.takeUnless { it is JsonNull }?.jsonObject
            return StudioPlayState(
                playing = obj["playing"]?.jsonPrimitive?.booleanOrNull ?: false,
                song = obj.str("song"),
                index = obj.int("index"),
                positionMs = obj.long("position_ms") ?: 0L,
                lengthMs = obj.long("length_ms"),
                rate = obj["rate"]?.jsonPrimitive?.doubleOrNull ?: 1.0,
                bpm = obj["bpm"]?.jsonPrimitive?.doubleOrNull,
                queue = obj["queue"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList(),
                danceTagId = dance?.long("tag_id"),
                danceName = dance?.str("name"),
            )
        }

        private fun JsonObject.str(key: String): String? =
            this[key]?.takeUnless {
                it is JsonNull
            }?.jsonPrimitive?.contentOrNull

        private fun JsonObject.long(key: String): Long? =
            this[key]?.takeUnless { it is JsonNull }?.jsonPrimitive?.longOrNull

        private fun JsonObject.int(key: String): Int? =
            this[key]?.takeUnless { it is JsonNull }?.jsonPrimitive?.intOrNull
    }
}

/** The messages the phone sends. Each returns the JSON text of one message. */
object StudioOutgoing {
    fun pairRequest(
        name: String,
        devicePoint: ByteArray,
        noiseKey: ByteArray,
        proof: ByteArray,
        signature: ByteArray,
    ): String =
        buildJsonObject {
            put("type", "pair_request")
            put("v", StudioWire.PROTOCOL_VERSION)
            put("name", name)
            put("device_key", StudioWire.base64Url(devicePoint))
            put("noise_key", StudioWire.base64Url(noiseKey))
            put("proof", StudioWire.base64Url(proof))
            put("sig", StudioWire.base64Url(signature))
        }.toString()

    fun hello(
        deviceId: String,
        counter: Long,
        signature: ByteArray,
    ): String =
        buildJsonObject {
            put("type", "hello")
            put("device", deviceId)
            put("counter", counter)
            put("sig", StudioWire.base64Url(signature))
        }.toString()

    fun command(
        counter: Long,
        body: String,
        signature: ByteArray,
    ): String =
        buildJsonObject {
            put("type", "command")
            put("counter", counter)
            put("body", body)
            put("sig", StudioWire.base64Url(signature))
        }.toString()
}
