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
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import com.music.bitchord.auth.AccountController
import com.music.bitchord.data.model.LikeStatus
import com.music.bitchord.ui.HomeTab
import com.music.bitchord.ui.LibraryTab
import com.music.bitchord.ui.LibraryViewModel
import com.music.bitchord.ui.PageScreen
import com.music.bitchord.ui.SignInScreen
import com.music.bitchord.ui.DeviceScreen
import com.music.bitchord.ui.DownloadIcon
import com.music.bitchord.ui.DownloadSessionBar
import com.music.bitchord.ui.DownloadsScreen
import com.music.bitchord.ui.SettingsScreen
import com.music.bitchord.ui.StatsScreen
import com.music.bitchord.download.Downloads
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.settings.TrackAnalysisState
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.music.bitchord.data.model.SearchFilter
import com.music.bitchord.data.model.BrowseType
import com.music.bitchord.data.model.EntityType
import com.music.bitchord.data.model.SearchHistoryEntity
import com.music.bitchord.data.settings.SearchHistory
import com.music.bitchord.platform.epochMillis
import com.music.bitchord.data.model.SearchResult
import com.music.bitchord.data.model.Song
import com.music.bitchord.platform.recentLog
import com.music.bitchord.platform.takeLastCrash
import com.music.bitchord.playback.AudioEngine
import com.music.bitchord.playback.LyricsController
import com.music.bitchord.playback.PlayerController
import com.music.bitchord.ui.ToastHost
import com.music.bitchord.ui.player.LyricsScreen
import com.music.bitchord.playback.largeArtwork
import com.music.bitchord.ui.SearchViewModel

/** Root of the iOS app: Home / Search / Library tabs, mini player, Now Playing, lyrics, sign-in. */
@Composable
fun App(engine: AudioEngine) {
    val player = remember { PlayerController(engine) }
    val search = remember { SearchViewModel() }
    val lyricsController = remember { LyricsController(player) }
    val accounts = remember { AccountController() }
    val library = remember { LibraryViewModel() }
    val signedIn by accounts.signedIn.collectAsState()
    val account by accounts.account.collectAsState()
    val generation by accounts.generation.collectAsState()
    val pages by library.pages.collectAsState()
    var tab by remember { mutableStateOf(0) }
    var nowPlayingOpen by remember { mutableStateOf(false) }
    var lyricsOpen by remember { mutableStateOf(false) }
    var signInOpen by remember { mutableStateOf(false) }
    var downloadsOpen by remember { mutableStateOf(false) }
    var deviceOpen by remember { mutableStateOf(false) }
    var settingsOpen by remember { mutableStateOf(false) }
    var statsOpen by remember { mutableStateOf(false) }
    var lastCrash by remember { mutableStateOf(takeLastCrash()) }
    val clipboard = LocalClipboardManager.current

    // First load, and again whenever the account behind requests changes.
    LaunchedEffect(generation) { library.reloadForAccount() }

    MaterialTheme(colorScheme = darkColorScheme()) {
        lastCrash?.let { report ->
            AlertDialog(
                onDismissRequest = { lastCrash = null },
                title = { Text("BitChord crashed last time") },
                text = { Text("Copy the report and send it over so it can be fixed.\n\n" + report.take(600)) },
                confirmButton = {
                    TextButton(onClick = {
                        clipboard.setText(AnnotatedString(report))
                        lastCrash = null
                    }) { Text("Copy report") }
                },
                dismissButton = { TextButton(onClick = { lastCrash = null }) { Text("Dismiss") } },
            )
        }
        Surface(modifier = Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                Column(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f)) {
                        when (tab) {
                            0 -> HomeTab(library, player, signedIn, onSignIn = { signInOpen = true })
                            1 -> SearchScreen(search, player, library, modifier = Modifier.fillMaxSize())
                            else -> LibraryTab(
                                library, player, signedIn, account,
                                onSignIn = { signInOpen = true },
                                onSignOut = accounts::signOut,
                                onDownloads = { downloadsOpen = true },
                                onDevice = { deviceOpen = true },
                                onSettings = { settingsOpen = true },
                                onStats = { statsOpen = true },
                            )
                        }
                        pages.lastOrNull()?.let { page -> PageScreen(page, library, player) }
                        if (deviceOpen) DeviceScreen(player, onClose = { deviceOpen = false })
                        if (downloadsOpen) DownloadsScreen(player, onClose = { downloadsOpen = false })
                        if (settingsOpen) SettingsScreen(onClose = { settingsOpen = false })
                        if (statsOpen) StatsScreen(player, library, onClose = { statsOpen = false })
                    }
                    DownloadSessionBar(onOpen = { downloadsOpen = true })
                    MiniPlayer(player, onOpen = { nowPlayingOpen = true })
                    NavigationBar(containerColor = Color(0xFF16161A)) {
                        listOf(
                            Triple("Home", Icons.Filled.Home, 0),
                            Triple("Search", Icons.Filled.Search, 1),
                            Triple("Library", Icons.Filled.LibraryMusic, 2),
                        ).forEach { (label, icon, index) ->
                            NavigationBarItem(
                                selected = tab == index && pages.isEmpty() && !downloadsOpen && !deviceOpen && !settingsOpen && !statsOpen,
                                onClick = {
                                    while (library.closePage()) Unit
                                    downloadsOpen = false
                                    deviceOpen = false
                                    settingsOpen = false
                                    statsOpen = false
                                    tab = index
                                },
                                icon = { Icon(icon, contentDescription = label) },
                                label = { Text(label) },
                            )
                        }
                    }
                }
                if (nowPlayingOpen) {
                    NowPlaying(
                        player,
                        library,
                        signedIn = signedIn,
                        onClose = { nowPlayingOpen = false },
                        onOpenLyrics = { lyricsOpen = true },
                    )
                }
                if (lyricsOpen) {
                    LyricsScreen(player, lyricsController, onClose = { lyricsOpen = false })
                }
                if (signInOpen) {
                    SignInScreen(accounts, onDone = { signedInNow ->
                        signInOpen = false
                        if (signedInNow) tab = 2
                    })
                }
                ToastHost(Modifier.align(Alignment.BottomCenter).padding(bottom = 140.dp))
            }
        }
    }
}

@Composable
private fun SearchScreen(
    vm: SearchViewModel,
    player: PlayerController,
    library: LibraryViewModel,
    modifier: Modifier = Modifier,
) {
    val state by vm.state.collectAsState()
    val recent by SearchHistory.recent.collectAsState()
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
            !state.submitted && state.query.isBlank() -> RecentSearches(recent, player, library)

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
                                SearchHistory.record(
                                    SearchHistoryEntity(song.videoId, song.title, song.artist, song.thumbnailUrl, EntityType.TRACK),
                                )
                                player.playQueue(songs, songs.indexOf(song))
                            }
                        } else if (result is SearchResult.Browse) {
                            val item = result.item
                            BrowseRow(item.title, item.subtitle, item.thumbnailUrl) {
                                focus.clearFocus()
                                SearchHistory.record(
                                    SearchHistoryEntity(item.browseId, item.title, item.subtitle, item.thumbnailUrl, item.type.toEntityType()),
                                )
                                library.openPage(item.browseId, item.title, item.subtitle, item.thumbnailUrl)
                            }
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
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
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
        DownloadIcon(song)
    }
}

/** An album, artist or playlist row; tapping opens its page. */
@Composable
private fun BrowseRow(title: String, subtitle: String, thumbnail: String?, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
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
private fun NowPlaying(
    player: PlayerController,
    library: LibraryViewModel,
    signedIn: Boolean,
    onClose: () -> Unit,
    onOpenLyrics: () -> Unit,
) {
    val likes by library.likes.collectAsState()
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
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onOpenLyrics) { Text("Lyrics") }
                if (song != null) DownloadIcon(song)
                if (signedIn && song != null && !song.videoId.startsWith(Downloads.LOCAL_PREFIX)) {
                    val liked = (likes[song.videoId] ?: LikeStatus.INDIFFERENT) == LikeStatus.LIKE
                    IconButton(onClick = { library.toggleLike(song) }) {
                        Icon(
                            if (liked) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                            contentDescription = if (liked) "Unlike" else "Like",
                            tint = if (liked) Color(0xFFFF4D6D) else Color.White,
                        )
                    }
                }
            }
            MixControls()
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            state.streamInfo?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = Color.Gray) }
            val clipboard = LocalClipboardManager.current
            var copied by remember { mutableStateOf(false) }
            TextButton(onClick = {
                clipboard.setText(AnnotatedString(recentLog()))
                copied = true
            }) { Text(if (copied) "Debug log copied" else "Copy debug log", style = MaterialTheme.typography.labelSmall) }
        }
    }
}

private fun formatTime(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val seconds = (total % 60).toString().padStart(2, '0')
    return "${total / 60}:$seconds"
}

/** Automix on/off, the crossfade length, and what the analysis of this pair has got to. */
@Composable
private fun MixControls() {
    val automix by AppSettings.smartFadeEnabled.collectAsState()
    val crossfade by AppSettings.crossfadeSeconds.collectAsState()
    val analysis by AppSettings.smartAnalysis.collectAsState()
    val mixing by AppSettings.smartMixInProgress.collectAsState()
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = automix,
            onClick = { AppSettings.smartFadeEnabled.value = !automix },
            label = { Text(if (mixing) "Automix · mixing" else "Automix") },
        )
        FilterChip(
            selected = crossfade > 0,
            onClick = {
                val steps = listOf(0, 3, 6, 9, 12)
                AppSettings.crossfadeSeconds.value = steps[(steps.indexOf(crossfade).coerceAtLeast(0) + 1) % steps.size]
            },
            label = { Text(if (crossfade > 0) "Crossfade ${crossfade}s" else "Crossfade off") },
        )
    }
    if (automix) {
        Text(
            "This track: ${analysis.current.label()} · next: ${analysis.next.label()}",
            style = MaterialTheme.typography.labelSmall,
            color = Color.Gray,
        )
    }
}

private fun TrackAnalysisState.label(): String = when (this) {
    TrackAnalysisState.WAITING -> "waiting"
    TrackAnalysisState.ANALYSING -> "analysing"
    TrackAnalysisState.ANALYSED -> "analysed"
    TrackAnalysisState.REFINING -> "refining"
    TrackAnalysisState.FAILED -> "no analysis"
}

private fun BrowseType.toEntityType(): EntityType = when (this) {
    BrowseType.ALBUM -> EntityType.ALBUM
    BrowseType.ARTIST -> EntityType.ARTIST
    else -> EntityType.PLAYLIST
}

/** What was tapped in search lately (kept on this device), shown while the field is empty. */
@Composable
private fun RecentSearches(recent: List<SearchHistoryEntity>, player: PlayerController, library: LibraryViewModel) {
    if (recent.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.TopCenter) {
            Text("Search for songs, albums, artists and playlists.", color = Color.Gray)
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Recent", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = SearchHistory::clear) { Text("Clear") }
            }
        }
        itemsIndexed(recent) { _, entry ->
            Row(
                Modifier.fillMaxWidth().clickable {
                    SearchHistory.record(entry.copy(timestamp = epochMillis()))
                    if (entry.entityType == EntityType.TRACK) {
                        player.playQueue(listOf(Song(videoId = entry.id, title = entry.title, artist = entry.subtitle, thumbnailUrl = entry.artworkUrl)), 0)
                    } else {
                        library.openPage(entry.id, entry.title, entry.subtitle, entry.artworkUrl)
                    }
                }.padding(start = 16.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Artwork(entry.artworkUrl, 48)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(entry.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOf(entry.entityType.name.lowercase().replaceFirstChar { it.uppercase() }, entry.subtitle)
                            .filter { it.isNotBlank() }.joinToString(" · "),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray,
                    )
                }
                IconButton(onClick = { SearchHistory.remove(entry.id) }) {
                    Icon(Icons.Filled.Clear, contentDescription = "Remove", tint = Color.Gray)
                }
            }
        }
    }
}
