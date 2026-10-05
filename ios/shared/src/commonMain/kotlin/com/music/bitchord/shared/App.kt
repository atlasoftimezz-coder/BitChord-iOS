package com.music.bitchord.shared

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.music.bitchord.data.model.SearchFilter
import com.music.bitchord.data.model.SearchResult
import com.music.bitchord.data.model.Song
import com.music.bitchord.playback.AudioEngine
import com.music.bitchord.playback.PlayerController
import com.music.bitchord.ui.SearchViewModel

/** Root of the iOS app. P1: search YouTube Music and play. */
@Composable
fun App(engine: AudioEngine) {
    val player = remember { PlayerController(engine) }
    val search = remember { SearchViewModel() }
    var nowPlayingOpen by remember { mutableStateOf(false) }

    MaterialTheme(colorScheme = darkColorScheme()) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                Column(Modifier.fillMaxSize()) {
                    SearchScreen(search, player, modifier = Modifier.weight(1f))
                    MiniPlayer(player, onOpen = { nowPlayingOpen = true })
                }
                if (nowPlayingOpen) {
                    NowPlaying(player, onClose = { nowPlayingOpen = false })
                }
            }
        }
    }
}

@Composable
private fun SearchScreen(vm: SearchViewModel, player: PlayerController, modifier: Modifier = Modifier) {
    val state by vm.state.collectAsState()
    val playerState by player.state.collectAsState()
    val focus = LocalFocusManager.current
    val listState = rememberLazyListState()

    // Infinite scroll: fetch the next page when the last rows come into view.
    LaunchedEffect(listState, state.results.size) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .collect { last -> if (state.results.isNotEmpty() && last >= state.results.size - 5) vm.loadMore() }
    }

    Column(modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = state.query,
            onValueChange = vm::onQueryChange,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            placeholder = { Text("Songs, artists, albums") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = {
                if (state.query.isNotEmpty()) {
                    IconButton(onClick = { vm.onQueryChange("") }) {
                        Icon(Icons.Filled.Clear, contentDescription = "Clear")
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(28.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = {
                focus.clearFocus()
                vm.submit()
            }),
        )
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SearchFilter.entries.forEach { filter ->
                FilterChip(
                    selected = state.filter == filter,
                    onClick = { vm.onFilterChange(filter) },
                    label = { Text(filter.label) },
                )
            }
        }

        when {
            !state.submitted && state.suggestions.isNotEmpty() -> LazyColumn(Modifier.fillMaxSize()) {
                itemsIndexed(state.suggestions) { _, suggestion ->
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            focus.clearFocus()
                            vm.submit(suggestion)
                        }.padding(horizontal = 20.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Filled.Search, contentDescription = null, tint = Color.Gray)
                        Spacer(Modifier.width(16.dp))
                        Text(suggestion, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }

            state.isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }

            state.error != null -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text("Search failed: ${state.error}", color = MaterialTheme.colorScheme.error)
            }

            state.submitted && state.results.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No results", color = Color.Gray)
            }

            else -> {
                val songs = state.results.mapNotNull { it.songOrNull() }
                LazyColumn(Modifier.fillMaxSize(), state = listState) {
                    itemsIndexed(state.results) { _, result ->
                        val song = result.songOrNull()
                        if (song != null) {
                            SongRow(song, isCurrent = playerState.current?.videoId == song.videoId) {
                                focus.clearFocus()
                                player.playQueue(songs, songs.indexOf(song))
                            }
                        } else if (result is SearchResult.Browse) {
                            BrowseRow(result.item.title, result.item.subtitle, result.item.thumbnailUrl)
                        }
                    }
                    if (state.isLoadingMore) {
                        item {
                            Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(Modifier.size(24.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun SearchResult.songOrNull(): Song? = when (this) {
    is SearchResult.TopTrack -> song
    is SearchResult.Track -> song
    is SearchResult.Browse -> null
}

@Composable
private fun SongRow(song: Song, isCurrent: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(song.thumbnailUrl, 52)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                song.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                color = if (isCurrent) MaterialTheme.colorScheme.primary else Color.Unspecified,
            )
            Text(
                listOfNotNull(song.artist.ifBlank { null }, song.durationText).joinToString(" · "),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                color = Color.Gray,
            )
        }
    }
}

/** Albums, artists and playlists: listed now, browsable in a later phase. */
@Composable
private fun BrowseRow(title: String, subtitle: String, thumbnail: String?) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(thumbnail, 52)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                subtitle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                color = Color.Gray,
            )
        }
    }
}

@Composable
private fun Artwork(url: String?, sizeDp: Int) {
    AsyncImage(
        model = url,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier.size(sizeDp.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xFF2A2A2A)),
    )
}

@Composable
private fun MiniPlayer(player: PlayerController, onOpen: () -> Unit) {
    val state by player.state.collectAsState()
    val song = state.current ?: return
    Column(Modifier.fillMaxWidth().background(Color(0xFF1E1E22))) {
        val progress = if (state.durationMs > 0) state.positionMs.toFloat() / state.durationMs else 0f
        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().height(2.dp))
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Artwork(song.thumbnailUrl, 44)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    state.error ?: song.artist,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (state.error != null) MaterialTheme.colorScheme.error else Color.Gray,
                )
            }
            PlayPauseButton(state, player)
            IconButton(onClick = player::next, enabled = state.hasNext) {
                Icon(Icons.Filled.SkipNext, contentDescription = "Next")
            }
        }
    }
}

@Composable
private fun PlayPauseButton(state: PlayerController.State, player: PlayerController, sizeDp: Int = 24) {
    if (state.isLoading && !state.isPlaying) {
        Box(Modifier.size((sizeDp * 2).dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.size(sizeDp.dp), strokeWidth = 2.dp)
        }
    } else {
        IconButton(onClick = player::togglePlay, modifier = Modifier.size((sizeDp * 2).dp)) {
            Icon(
                if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = if (state.isPlaying) "Pause" else "Play",
                modifier = Modifier.size(sizeDp.dp),
            )
        }
    }
}

@Composable
private fun NowPlaying(player: PlayerController, onClose: () -> Unit) {
    val state by player.state.collectAsState()
    val song = state.current
    var scrubbing by remember { mutableStateOf<Float?>(null) }

    Surface(Modifier.fillMaxSize(), color = Color(0xFF121216)) {
        Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.fillMaxWidth()) {
                IconButton(onClick = onClose) {
                    Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Close", modifier = Modifier.size(32.dp))
                }
            }
            Spacer(Modifier.height(24.dp))
            AsyncImage(
                model = song?.thumbnailUrl?.let(::largeArtwork),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF2A2A2A)),
            )
            Spacer(Modifier.height(28.dp))
            Text(
                song?.title.orEmpty(),
                style = MaterialTheme.typography.titleLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(song?.artist.orEmpty(), color = Color.Gray, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(20.dp))

            val duration = state.durationMs.coerceAtLeast(1)
            Slider(
                value = scrubbing ?: (state.positionMs.toFloat() / duration).coerceIn(0f, 1f),
                onValueChange = { scrubbing = it },
                onValueChangeFinished = {
                    scrubbing?.let { player.seekTo((it * duration).toLong()) }
                    scrubbing = null
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(formatTime(scrubbing?.let { (it * duration).toLong() } ?: state.positionMs), color = Color.Gray)
                Text(formatTime(state.durationMs), color = Color.Gray)
            }
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                IconButton(onClick = player::previous) {
                    Icon(Icons.Filled.SkipPrevious, contentDescription = "Previous", modifier = Modifier.size(36.dp))
                }
                PlayPauseButton(state, player, sizeDp = 40)
                IconButton(onClick = player::next, enabled = state.hasNext) {
                    Icon(Icons.Filled.SkipNext, contentDescription = "Next", modifier = Modifier.size(36.dp))
                }
            }
            Spacer(Modifier.height(16.dp))
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            state.streamInfo?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = Color.Gray) }
        }
    }
}

/** YouTube Music thumbnails carry their size in the URL (`=w120-h120`); ask for a big one. */
private fun largeArtwork(url: String): String =
    url.replace(Regex("=w\\d+-h\\d+"), "=w720-h720")

private fun formatTime(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val seconds = (total % 60).toString().padStart(2, '0')
    return "${total / 60}:$seconds"
}
