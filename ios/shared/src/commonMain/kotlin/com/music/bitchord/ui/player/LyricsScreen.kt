package com.music.bitchord.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.music.bitchord.R
import com.music.bitchord.data.lyrics.LyricsSource
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.playback.LyricsController
import com.music.bitchord.playback.LyricsProviderState
import com.music.bitchord.playback.PlayerController
import com.music.bitchord.ui.haptics.rememberHaptics

/**
 * The full-screen lyrics view: BitChord's own LyricsPanel (word-synced sweep,
 * duet lanes, background vocals, tap-to-seek), with the translation and
 * romanization toggles and the provider picker from the Android player.
 */
@Composable
fun LyricsScreen(
    player: PlayerController,
    lyricsController: LyricsController,
    onClose: () -> Unit,
) {
    val playerState by player.state.collectAsState()
    val lyrics by lyricsController.state.collectAsState()
    val song = playerState.current ?: return
    val offsetMs by AppSettings.lyricsOffsetMs.collectAsState()
    val haptics = rememberHaptics()
    var showProviders by remember { mutableStateOf(false) }

    val loadingLines = stringArrayResource(R.array.lyrics_loading_lines)
    val loadingText = remember(song.videoId) { loadingLines.random() }
    val translation = rememberLyricsTranslation(
        trackId = song.videoId,
        lyrics = lyrics.lines,
        lyricsSource = lyrics.source,
        lyricsUnavailable = lyrics.unavailable,
        loadingText = loadingText,
        haptics = haptics,
    )

    Box(
        Modifier.fillMaxSize().background(
            Brush.verticalGradient(listOf(Color(0xFF1B1830), Color(0xFF0E0E12))),
        ),
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onClose) {
                    Icon(
                        Icons.Filled.KeyboardArrowDown,
                        contentDescription = "Close",
                        tint = Color.White,
                        modifier = Modifier.size(32.dp),
                    )
                }
                Column(Modifier.weight(1f)) {
                    Text(song.title, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    Text(song.artist, color = Color.White.copy(alpha = 0.6f), maxLines = 1)
                }
                RomanizationToggleButton(
                    state = translation.romanizationState,
                    showingRomanization = translation.showingRomanization,
                    enabled = !lyrics.lines.isNullOrEmpty(),
                    onClick = translation.toggleRomanization,
                )
                TranslationToggleButton(
                    state = translation.translationState,
                    showingTranslation = translation.showingTranslation,
                    enabled = !lyrics.lines.isNullOrEmpty(),
                    onClick = translation.toggleTranslation,
                )
            }
            LyricsStatusWithChange(
                status = translation.status,
                onChange = { showProviders = true },
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
            LyricsTranslationMotion(
                trigger = translation.transition,
                reduceMotion = translation.reduceMotion,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            ) { particleProgress ->
                LyricsPanel(
                    lines = lyrics.lines.orEmpty(),
                    subLines = translation.subLines,
                    trackKey = song.videoId,
                    positionMs = adjustedLyricsPosition(playerState.positionMs, offsetMs),
                    looking = !lyrics.unavailable,
                    isPlaying = playerState.isPlaying,
                    onSeekToLine = { lineMs -> player.seekTo(adjustedLyricsSeekTarget(lineMs, offsetMs)) },
                    controlsOpen = true,
                    onRevealControls = {},
                    onHideControls = {},
                    translationProgress = particleProgress,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        if (showProviders) {
            ProviderPicker(
                current = lyrics.source,
                states = lyrics.providers,
                onPick = {
                    lyricsController.selectProvider(it)
                    showProviders = false
                },
                onDismiss = { showProviders = false },
            )
        }
    }
}

@Composable
private fun ProviderPicker(
    current: LyricsSource?,
    states: Map<LyricsSource, LyricsProviderState>,
    onPick: (LyricsSource) -> Unit,
    onDismiss: () -> Unit,
) {
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)).clickable(onClick = onDismiss),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            Modifier.fillMaxWidth().background(Color(0xFF1E1E24)).clickable(enabled = false) {}
                .padding(vertical = 12.dp),
        ) {
            Text(
                "Lyrics provider",
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            LazyColumn(Modifier.fillMaxWidth().height(420.dp)) {
                items(LyricsSource.entries) { source ->
                    val state = states[source] ?: LyricsProviderState.IDLE
                    Row(
                        Modifier.fillMaxWidth().clickable(enabled = state != LyricsProviderState.NOT_FOUND) {
                            onPick(source)
                        }.padding(horizontal = 20.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                source.label,
                                color = if (source == current) MaterialTheme.colorScheme.primary else Color.White,
                                fontWeight = if (source == current) FontWeight.Bold else FontWeight.Normal,
                            )
                            Text(source.detail, color = Color.White.copy(alpha = 0.5f), style = MaterialTheme.typography.bodySmall)
                        }
                        Spacer(Modifier.width(12.dp))
                        when (state) {
                            LyricsProviderState.FETCHING -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            LyricsProviderState.FOUND -> Text("Found", color = Color(0xFF7BD88F), style = MaterialTheme.typography.labelMedium)
                            LyricsProviderState.NOT_FOUND -> Text("None", color = Color.White.copy(alpha = 0.4f), style = MaterialTheme.typography.labelMedium)
                            LyricsProviderState.IDLE -> Text("Try", color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        }
    }
}
