package org.dancesonic.player.studio

import java.net.URLDecoder

/**
 * The pairing code Studio shows under "Pair a phone" (`dancesonic-studio:pair?...`). It holds no address and no
 * credential: only what is needed to recognise Studio and to prove that the phone scanned this code.
 */
data class StudioOffer(
    /** Studio's fingerprint. The phone checks the connected Studio against it. */
    val fingerprint: ByteArray,
    /** The one-time pairing secret. Never logged or shown again once used. */
    val secret: ByteArray,
    /** When the code expires, in Unix seconds. */
    val expiresAt: Long,
    /** The BLE service UUID, as text. Kept for the Bluetooth transport. */
    val bleService: String,
    /** Studio's display name, at most 64 characters. */
    val name: String,
) {
    /** Whether the code is still usable at [nowSeconds]. */
    fun isOpen(nowSeconds: Long): Boolean = nowSeconds < expiresAt

    // Arrays make data-class equality identity-based; compare the parts that matter instead.
    override fun equals(other: Any?): Boolean =
        other is StudioOffer &&
            fingerprint.contentEquals(other.fingerprint) &&
            secret.contentEquals(other.secret) &&
            expiresAt == other.expiresAt &&
            bleService == other.bleService &&
            name == other.name

    override fun hashCode(): Int {
        var result = fingerprint.contentHashCode()
        result = 31 * result + secret.contentHashCode()
        result = 31 * result + expiresAt.hashCode()
        result = 31 * result + bleService.hashCode()
        return 31 * result + name.hashCode()
    }

    /** Why a scanned code was refused. */
    enum class Problem { NOT_A_STUDIO_CODE, UNSUPPORTED_VERSION, MISSING_FIELD, BAD_FIELD, EXPIRED }

    /** The outcome of [parse]: the offer, or the reason it was refused. */
    sealed interface ParseResult {
        data class Ok(
            val offer: StudioOffer,
        ) : ParseResult

        data class Refused(
            val problem: Problem,
        ) : ParseResult
    }

    companion object {
        private const val SCHEME_PREFIX = "dancesonic-studio:pair?"
        private const val FINGERPRINT_BYTES = 32
        private const val SECRET_BYTES = 16
        private const val MAX_NAME_CHARS = 64
        private val UUID_PATTERN = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

        /** Reads a scanned code. [nowSeconds] decides whether it has expired. One return per refusal, in order. */
        @Suppress("ReturnCount", "CyclomaticComplexMethod")
        fun parse(
            text: String,
            nowSeconds: Long,
        ): ParseResult {
            if (!text.startsWith(SCHEME_PREFIX)) return ParseResult.Refused(Problem.NOT_A_STUDIO_CODE)
            val fields = parseFields(text.removePrefix(SCHEME_PREFIX)) ?: return ParseResult.Refused(Problem.BAD_FIELD)
            if (fields["v"] != StudioWire.PROTOCOL_VERSION.toString()) {
                return ParseResult.Refused(Problem.UNSUPPORTED_VERSION)
            }
            val fingerprint = required(fields, "fp")?.let { StudioWire.decodeBase64Url(it, FINGERPRINT_BYTES) }
            val secret = required(fields, "s")?.let { StudioWire.decodeBase64Url(it, SECRET_BYTES) }
            val expiresAt = required(fields, "exp")?.toLongOrNull()
            val ble = required(fields, "ble")
            val name = fields["name"] ?: ""
            if (fields["fp"] == null || fields["s"] == null) return ParseResult.Refused(Problem.MISSING_FIELD)
            if (expiresAt == null || ble == null) return ParseResult.Refused(Problem.MISSING_FIELD)
            if (fingerprint == null || secret == null) return ParseResult.Refused(Problem.BAD_FIELD)
            if (fingerprint.size != FINGERPRINT_BYTES || secret.size != SECRET_BYTES) {
                return ParseResult.Refused(Problem.BAD_FIELD)
            }
            if (!UUID_PATTERN.matches(ble) || name.length > MAX_NAME_CHARS) {
                return ParseResult.Refused(Problem.BAD_FIELD)
            }
            if (nowSeconds >= expiresAt) return ParseResult.Refused(Problem.EXPIRED)
            return ParseResult.Ok(StudioOffer(fingerprint, secret, expiresAt, ble, name))
        }

        /** The `key=value` pairs of the query. A repeated key makes the whole code bad. */
        private fun parseFields(query: String): Map<String, String>? {
            val fields = LinkedHashMap<String, String>()
            for (pair in query.split('&').filter { it.isNotEmpty() }) {
                val (key, value) = pair.split('=', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
                if (fields.containsKey(key)) return null
                fields[key] = runCatching { URLDecoder.decode(value, Charsets.UTF_8) }.getOrNull() ?: return null
            }
            return fields
        }

        private fun required(
            fields: Map<String, String>,
            key: String,
        ): String? = fields[key]?.takeIf { it.isNotEmpty() }
    }
}
