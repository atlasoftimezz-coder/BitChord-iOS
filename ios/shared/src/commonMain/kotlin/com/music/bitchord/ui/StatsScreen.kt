package com.music.bitchord.ui

// ios-native: an interim view of Replay — the listening stats the Android app
// keeps (data/stats/ListeningStats, ported unchanged) as plain charts. The
// Android Replay screens (stories, poster, share sheet) come with the UI port.

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.music.bitchord.data.stats.ListeningStats
import com.music.bitchord.data.stats.RankedEntry
import com.music.bitchord.data.stats.ReplayPeriod
import com.music.bitchord.data.stats.ReplaySummary
import com.music.bitchord.playback.PlayerController

private val Gray = Color(0xFF9A9AA2)

@Composable
fun StatsScreen(player: PlayerController, library: LibraryViewModel, onClose: () -> Unit) {
    var period by remember { mutableStateOf(ReplayPeriod.THIS_MONTH) }
    var summary by remember { mutableStateOf<ReplaySummary?>(null) }
    LaunchedEffect(period) {
        ListeningStats.flush()
        summary = null
        summary = runCatching { ListeningStats.summary(period) }.getOrNull()
    }

    Surface(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
            item {
                Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                    Text("Your stats", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                }
            }
            item {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ReplayPeriod.entries.forEach { option ->
                        FilterChip(selected = period == option, onClick = { period = option }, label = { Text(option.chip) })
                    }
                }
            }
            val current = summary
            when {
                current == null -> item {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                }
                current.isEmpty -> item {
                    Text(
                        "Nothing yet for ${current.label}. Stats are kept on this iPhone as you listen.",
                        color = Gray,
                        modifier = Modifier.padding(24.dp),
                    )
                }
                else -> {
                    item { Headline(current) }
                    item { Heading("Top songs") }
                    val songs = current.songs.take(TOP)
                    itemsIndexed(songs) { index, ranked ->
                        RankRow(index + 1, ranked.song.title, ranked.song.artist, ranked.song.thumbnailUrl, ranked.ms, ranked.plays) {
                            player.playQueue(songs.map { it.song }, index)
                        }
                    }
                    if (current.artists.isNotEmpty()) {
                        item { Heading("Top artists") }
                        itemsIndexed(current.artists.take(TOP)) { index, entry -> EntryRow(index + 1, entry, library, onClose) }
                    }
                    if (current.albums.isNotEmpty()) {
                        item { Heading("Top albums") }
                        itemsIndexed(current.albums.take(TOP)) { index, entry -> EntryRow(index + 1, entry, library, onClose) }
                    }
                    if (current.genres.isNotEmpty()) {
                        item { Heading("Top genres") }
                        itemsIndexed(current.genres.take(TOP)) { index, entry -> EntryRow(index + 1, entry, library, onClose) }
                    }
                }
            }
        }
    }
}

private const val TOP = 10

@Composable
private fun Headline(summary: ReplaySummary) {
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Text(summary.label, color = Gray)
        Text("${summary.minutes} minutes", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(
            "${summary.totalPlays} plays · ${summary.distinctSongs} songs · ${summary.distinctArtists} artists",
            color = Gray,
        )
        summary.peakHour?.let { hour ->
            Text("You listen most around ${hourLabel(hour)}", color = Gray, style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun hourLabel(hour: Int): String = when {
    hour == 0 -> "midnight"
    hour < 12 -> "$hour am"
    hour == 12 -> "noon"
    else -> "${hour - 12} pm"
}

@Composable
private fun Heading(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun EntryRow(rank: Int, entry: RankedEntry, library: LibraryViewModel, onClose: () -> Unit) {
    val browseId = entry.browseId
    RankRow(rank, entry.title, entry.subtitle, entry.artworkUrl, entry.ms, entry.plays, onClick = browseId?.let {
        {
            onClose()
            library.openPage(it, entry.title, entry.subtitle.orEmpty(), entry.artworkUrl)
        }
    })
}

@Composable
private fun RankRow(
    rank: Int,
    title: String,
    subtitle: String?,
    artwork: String?,
    ms: Long,
    plays: Int,
    onClick: (() -> Unit)?,
) {
    Row(
        Modifier.fillMaxWidth().let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("$rank", color = Gray, modifier = Modifier.width(28.dp))
        Artwork(artwork, Modifier.size(44.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            subtitle?.takeIf { it.isNotBlank() }?.let {
                Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis, color = Gray, style = MaterialTheme.typography.bodySmall)
            }
        }
        Text("${ms / 60_000} min · $plays", color = Gray, style = MaterialTheme.typography.bodySmall)
    }
}
