package com.music.bitchord.data.settings

import com.music.bitchord.data.lyrics.LyricsSource
import com.music.bitchord.platform.KeyValueStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/**
 * App settings, persisted in NSUserDefaults and exposed as flows — the same
 * shape as the Android app's AppSettings (SharedPreferences + MutableStateFlow),
 * so ported code reads `AppSettings.lyricsBlur.collectAsState()` unchanged.
 *
 * Only the settings the ported phases use so far are here; the rest arrive with
 * the features that read them. Names and keys match the Android ones.
 */
object AppSettings {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** Freezes ambient motion (gradient drift, particle effects). */
    val reduceAnimation = boolean("reduce_animation", false)

    /** Cheaper, static backgrounds instead of live blur. */
    val reduceDynamicBlur = boolean("reduce_dynamic_blur", false)

    /** Blurs unfocused lyric lines, keeping the active line sharp. */
    val lyricsBlur = boolean("lyrics_blur", true)

    /** Positive values delay synced lyrics; negative values bring them forward. */
    val lyricsOffsetMs = int("lyrics_offset_ms", 0)

    /** Which language the lyrics translate button translates into; "" = the device language. */
    val translationLanguage = string("translation_language", "")

    /** Lyric providers the user has left on. */
    val lyricsSources = MutableStateFlow(
        KeyValueStore.getString("lyrics_sources")?.let(::decodeSources)?.toSet() ?: LyricsSource.entries.toSet(),
    ).also { flow ->
        persist(flow) { KeyValueStore.putString("lyrics_sources", it.joinToString(",") { s -> s.name }) }
    }

    /** The order lyric providers are asked in; see LyricsRepository. */
    val lyricsSourceOrder = MutableStateFlow(
        KeyValueStore.getString("lyrics_source_order")?.let(::decodeSources) ?: LyricsSource.entries,
    ).also { flow ->
        persist(flow) { KeyValueStore.putString("lyrics_source_order", it.joinToString(",") { s -> s.name }) }
    }

    /** Keep looking for word-synced lyrics after a line-synced answer arrives. */
    val prioritizeSyllableSync = boolean("prioritize_syllable_sync", false)

    /** Whether third-party lyric services may be asked at all (also gates lyrics saved with downloads). */
    val syncedLyrics = boolean("synced_lyrics", true)

    /** User-issued credential required by api.paxsenix.org. */
    val paxSenixApiKey = string("paxsenix_api_key", "")

    private fun decodeSources(raw: String): List<LyricsSource> =
        raw.split(',').mapNotNull { name -> LyricsSource.entries.firstOrNull { it.name == name } }

    private fun boolean(key: String, default: Boolean) =
        MutableStateFlow(KeyValueStore.getBoolean(key) ?: default).also { flow ->
            persist(flow) { KeyValueStore.putBoolean(key, it) }
        }

    private fun int(key: String, default: Int) =
        MutableStateFlow(KeyValueStore.getInt(key) ?: default).also { flow ->
            persist(flow) { KeyValueStore.putInt(key, it) }
        }

    private fun string(key: String, default: String) =
        MutableStateFlow(KeyValueStore.getString(key) ?: default).also { flow ->
            persist(flow) { KeyValueStore.putString(key, it) }
        }

    private fun <T> persist(flow: MutableStateFlow<T>, write: (T) -> Unit) {
        scope.launch { flow.drop(1).collect { write(it) } }
    }
}
