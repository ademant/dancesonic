package org.dancesonic.player.account

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The refusal code in a server's error body. folksonic answers `{"error": "<code>"}`; DanceBrainz answers
 * `{"error": {"code": "<code>", "message": "...", "hint": "..."}}`. Returns null when the body has neither shape.
 */
@Suppress("SwallowedException")
internal fun refusalCodeOf(text: String?): String? {
    if (text.isNullOrBlank()) return null
    // Not JSON at all: the caller reports the HTTP status instead, so a parse failure is simply no code.
    val root = runCatching { Json.parseToJsonElement(text) }.getOrNull() ?: return null
    return when (val error = (root as? JsonObject)?.get("error")) {
        is JsonPrimitive -> error.takeIf { it.isString }?.content
        is JsonObject -> (error["code"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        else -> null
    }
}
