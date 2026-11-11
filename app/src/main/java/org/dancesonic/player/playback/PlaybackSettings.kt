package org.dancesonic.player.playback

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlin.math.pow

/** How the player bar's tempo control works. */
enum class TempoMode { Multiplier, Bpm }

/** Playback choices from the config page. Not secret, so they are stored without encryption. */
data class PlaybackSettings(
    /** Scale each track by its ReplayGain value, where the server has one. */
    val replayGain: Boolean = false,
    val tempoMode: TempoMode = TempoMode.Multiplier,
    /** Speed for every track in [TempoMode.Multiplier], and for tracks without a BPM in [TempoMode.Bpm]. */
    val multiplier: Float = 1f,
    /** The BPM that tracks with a known BPM play at, in [TempoMode.Bpm]. */
    val targetBpm: Int = PlaybackMath.DEFAULT_TARGET_BPM,
    /**
     * Whether the tempo chosen on the player bar carries over to the next track. When false, each new track starts
     * at its own tempo (1x, or its own BPM), and the bar shows those values.
     */
    val keepTempoForNextTrack: Boolean = true,
)

/** Rules for the speed and volume of one track. Pure, so they are tested without Android. */
object PlaybackMath {
    const val MIN_TEMPO = 0.5f
    const val MAX_TEMPO = 1.5f
    const val MIN_TARGET_BPM = 60
    const val MAX_TARGET_BPM = 180
    const val DEFAULT_TARGET_BPM = 120

    /**
     * The playback speed for a track. In BPM mode a track with a known tempo plays at the target BPM; other tracks
     * use the multiplier. The speed is kept within the range the player bar offers.
     */
    fun speed(
        settings: PlaybackSettings,
        trackBpm: Int?,
    ): Float {
        val bpm = trackBpm
        val speed =
            if (settings.tempoMode == TempoMode.Bpm && bpm != null && bpm > 0) {
                settings.targetBpm.toFloat() / bpm
            } else {
                settings.multiplier
            }
        return speed.coerceIn(MIN_TEMPO, MAX_TEMPO)
    }

    /**
     * The settings a track starts with when the tempo is not kept: 1x, and the track's own BPM as the target, so
     * a track with a BPM plays at its original speed. Tracks without a BPM keep the target they had.
     */
    fun trackStartSettings(
        settings: PlaybackSettings,
        trackBpm: Int?,
    ): PlaybackSettings {
        val bpm = trackBpm?.takeIf { it > 0 }?.coerceIn(MIN_TARGET_BPM, MAX_TARGET_BPM) ?: settings.targetBpm
        return settings.copy(multiplier = 1f, targetBpm = bpm)
    }

    /**
     * The volume for a track, from its ReplayGain track gain (dB) and peak (linear). Only cuts, never boosts: the
     * player volume cannot go above 1. A track whose peak would clip after the gain is kept below full scale.
     */
    fun volume(
        replayGainEnabled: Boolean,
        trackGainDb: Double?,
        trackPeak: Double?,
    ): Float {
        val gain = trackGainDb
        if (!replayGainEnabled || gain == null) return 1f
        var volume = 10.0.pow(gain / DB_PER_AMPLITUDE_FACTOR).toFloat()
        if (trackPeak != null && trackPeak > 0.0) {
            volume = minOf(volume, (1.0 / trackPeak).toFloat())
        }
        return volume.coerceIn(0f, 1f)
    }

    /** Amplitude is 10^(dB / 20). */
    private const val DB_PER_AMPLITUDE_FACTOR = 20.0
}

/** Reads and writes [PlaybackSettings] in DataStore. */
class PlaybackSettingsStore(private val dataStore: DataStore<Preferences>) {
    val settings: Flow<PlaybackSettings> =
        dataStore.data.map { prefs ->
            PlaybackSettings(
                replayGain = prefs[REPLAY_GAIN] ?: false,
                tempoMode =
                    prefs[TEMPO_MODE]?.let { runCatching { TempoMode.valueOf(it) }.getOrNull() }
                        ?: TempoMode.Multiplier,
                multiplier = (prefs[MULTIPLIER] ?: 1f).coerceIn(PlaybackMath.MIN_TEMPO, PlaybackMath.MAX_TEMPO),
                targetBpm =
                    (prefs[TARGET_BPM] ?: PlaybackMath.DEFAULT_TARGET_BPM)
                        .coerceIn(PlaybackMath.MIN_TARGET_BPM, PlaybackMath.MAX_TARGET_BPM),
                keepTempoForNextTrack = prefs[KEEP_TEMPO] ?: true,
            )
        }

    suspend fun save(settings: PlaybackSettings) {
        dataStore.edit { prefs ->
            prefs[REPLAY_GAIN] = settings.replayGain
            prefs[TEMPO_MODE] = settings.tempoMode.name
            prefs[MULTIPLIER] = settings.multiplier
            prefs[TARGET_BPM] = settings.targetBpm
            prefs[KEEP_TEMPO] = settings.keepTempoForNextTrack
        }
    }

    private companion object {
        val REPLAY_GAIN = booleanPreferencesKey("playback_replay_gain")
        val TEMPO_MODE = stringPreferencesKey("playback_tempo_mode")
        val MULTIPLIER = floatPreferencesKey("playback_multiplier")
        val TARGET_BPM = intPreferencesKey("playback_target_bpm")
        val KEEP_TEMPO = booleanPreferencesKey("playback_keep_tempo")
    }
}
