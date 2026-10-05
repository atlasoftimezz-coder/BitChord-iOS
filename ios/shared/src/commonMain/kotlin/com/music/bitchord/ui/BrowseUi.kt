package com.music.bitchord.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.music.bitchord.data.model.Account
import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.model.Song
import com.music.bitchord.download.DownloadTarget
import com.music.bitchord.playback.PlayerController

private val Gray = Color(0xFF9A9AA2)

@Composable
fun Artwork(url: String?, modifier: Modifier, round: Boolean = false) {
    AsyncImage(
        model = url,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier
            .clip(if (round) CircleShape else RoundedCornerShape(8.dp))
            .background(Color(0xFF2A2A2A)),
    )
}

/** A Home/Library shelf: title, then a horizontal row of cards. */
@Composable
fun ShelfRow(shelf: HomeShelf, onItem: (ShelfItem) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 18.dp)) {
        Text(
            shelf.title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 16.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (shelf.subtitle.isNotBlank()) {
            Text(shelf.subtitle, color = Gray, modifier = Modifier.padding(horizontal = 16.dp), maxLines = 1)
        }
        Spacer(Modifier.height(10.dp))
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(shelf.items) { item -> ShelfCard(item) { onItem(item) } }
        }
    }
}

@Composable
private fun ShelfCard(item: ShelfItem, onClick: () -> Unit) {
    val artist = item.browseId?.startsWith("UC") == true
    Column(Modifier.width(140.dp).clickable(onClick = onClick)) {
        Artwork(item.thumbnailUrl, Modifier.size(140.dp), round = artist)
        Spacer(Modifier.height(6.dp))
        Text(item.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
        if (item.subtitle.isNotBlank()) {
            Text(item.subtitle, color = Gray, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
fun TrackRow(song: Song, isCurrent: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(song.thumbnailUrl, Modifier.size(52.dp))
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
                color = Gray,
            )
        }
        DownloadIcon(song)
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) { content() }
}

/** What a tapped card does: a track plays (with its shelf as the queue), anything else opens. */
fun onShelfItem(item: ShelfItem, shelf: HomeShelf?, player: PlayerController, vm: LibraryViewModel) {
    val song = item.toSong()
    if (song != null) {
        val queue = shelf?.items?.mapNotNull { it.toSong() }.orEmpty().ifEmpty { listOf(song) }
        player.playQueue(queue, queue.indexOfFirst { it.videoId == song.videoId }.coerceAtLeast(0))
    } else {
        vm.open(item)
    }
}

@Composable
fun HomeTab(vm: LibraryViewModel, player: PlayerController, signedIn: Boolean, onSignIn: () -> Unit) {
    val home by vm.home.collectAsState()
    val listState = rememberLazyListState()
    when (val state = home) {
        is Load.Loading -> Centered { CircularProgressIndicator() }
        is Load.Failed -> Centered {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(state.message, color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(12.dp))
                Button(onClick = vm::loadHome) { Text("Retry") }
            }
        }
        is Load.Ready -> {
            LaunchedEffect(listState, state.value.shelves.size) {
                snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
                    .collect { last -> if (last >= state.value.shelves.size - 2) vm.loadMoreHome() }
            }
            LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = 24.dp)) {
                item {
                    Text(
                        "Home",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                if (!signedIn) {
                    item { SignInBanner(onSignIn) }
                }
                items(state.value.shelves) { shelf ->
                    ShelfRow(shelf) { item -> onShelfItem(item, shelf, player, vm) }
                }
            }
        }
    }
}

@Composable
private fun SignInBanner(onSignIn: () -> Unit) {
    Surface(
        color = Color(0xFF1F1D2B),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().padding(16.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("Sign in to YouTube Music", fontWeight = FontWeight.Bold)
            Text("Your library, likes, playlists and recommendations.", color = Gray)
            Spacer(Modifier.height(10.dp))
            Button(onClick = onSignIn) { Text("Sign in") }
        }
    }
}

@Composable
fun LibraryTab(
    vm: LibraryViewModel,
    player: PlayerController,
    signedIn: Boolean,
    account: Account?,
    onSignIn: () -> Unit,
    onSignOut: () -> Unit,
    onDownloads: () -> Unit,
    onDevice: () -> Unit,
    onSettings: () -> Unit,
) {
    if (!signedIn) {
        // Downloads and imported files need no account, and are what works offline.
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Library",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, contentDescription = "Settings") }
                }
            }
            item { OfflineEntries(onDownloads, onDevice) }
            item {
                Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Your playlists and likes live in your YouTube Music account.", color = Gray)
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = onSignIn) { Text("Sign in") }
                }
            }
        }
        return
    }
    val library by vm.library.collectAsState()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Artwork(account?.thumbnailUrl, Modifier.size(48.dp), round = true)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(account?.name ?: "Library", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    account?.email?.takeIf { it.isNotBlank() }?.let { Text(it, color = Gray, maxLines = 1) }
                }
                IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, contentDescription = "Settings") }
                OutlinedButton(onClick = onSignOut) { Text("Sign out") }
            }
        }
        item { OfflineEntries(onDownloads, onDevice) }
        when (val state = library) {
            is Load.Loading -> item { Box(Modifier.fillMaxWidth().padding(32.dp), Alignment.Center) { CircularProgressIndicator() } }
            is Load.Failed -> item {
                Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(state.message, color = MaterialTheme.colorScheme.error)
                    Button(onClick = vm::loadLibrary) { Text("Retry") }
                }
            }
            is Load.Ready -> {
                val page = state.value
                item {
                    LibraryEntry("Liked songs", "${page.likedSongs.size} songs", Icons.Filled.Favorite) {
                        vm.openSongs("Liked songs", page.likedSongs)
                    }
                }
                if (page.librarySongs.isNotEmpty()) {
                    item {
                        LibraryEntry("Songs in your library", "${page.librarySongs.size} songs", Icons.Filled.PlayArrow) {
                            vm.openSongs("Songs in your library", page.librarySongs)
                        }
                    }
                }
                items(page.shelves) { shelf ->
                    ShelfRow(shelf) { item -> onShelfItem(item, shelf, player, vm) }
                }
            }
        }
    }
}

@Composable
private fun LibraryEntry(title: String, subtitle: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(52.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF3B2F6B)),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, contentDescription = null) }
        Spacer(Modifier.width(14.dp))
        Column {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = Gray, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** An opened album / playlist / artist / song list. */
@Composable
fun PageScreen(page: LibraryViewModel.Page, vm: LibraryViewModel, player: PlayerController) {
    val playerState by player.state.collectAsState()
    val listState = rememberLazyListState()
    LaunchedEffect(listState, page.songs.size) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .collect { last -> if (last >= page.songs.size - 3) vm.loadMorePage(page.browseId) }
    }
    Surface(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                Row(Modifier.padding(8.dp)) {
                    IconButton(onClick = { vm.closePage() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            }
            item {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Artwork(
                        page.thumbnailUrl?.replace(Regex("=w\\d+-h\\d+"), "=w544-h544"),
                        Modifier.fillMaxWidth(0.62f).aspectRatio(1f),
                        round = page.browseId.startsWith("UC"),
                    )
                    Spacer(Modifier.height(14.dp))
                    Text(page.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, maxLines = 2)
                    if (page.subtitle.isNotBlank()) Text(page.subtitle, color = Gray, maxLines = 2)
                    Spacer(Modifier.height(12.dp))
                    if (page.songs.isNotEmpty()) {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(onClick = { player.playQueue(page.songs, 0) }) {
                                Icon(Icons.Filled.PlayArrow, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text("Play")
                            }
                            FilledTonalButton(onClick = {
                                val shuffled = page.songs.shuffled()
                                player.playQueue(shuffled, 0)
                            }) {
                                Icon(Icons.Filled.Shuffle, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text("Shuffle")
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        CollectionDownloadButton(
                            DownloadTarget(
                                id = page.browseId,
                                title = page.title,
                                subtitle = page.subtitle,
                                thumbnailUrl = page.thumbnailUrl,
                                playlist = !page.browseId.startsWith("MPRE") && !page.browseId.startsWith("UC"),
                            ),
                            page.songs,
                        )
                    }
                }
            }
            when {
                page.loading -> item { Box(Modifier.fillMaxWidth().padding(32.dp), Alignment.Center) { CircularProgressIndicator() } }
                page.error != null && page.songs.isEmpty() -> item {
                    Text(page.error, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(24.dp))
                }
            }
            itemsIndexed(page.songs) { index, song ->
                TrackRow(song, isCurrent = playerState.current?.videoId == song.videoId) {
                    player.playQueue(page.songs, index)
                }
            }
            items(page.sections) { shelf ->
                ShelfRow(shelf) { item -> onShelfItem(item, shelf, player, vm) }
            }
        }
    }
}
