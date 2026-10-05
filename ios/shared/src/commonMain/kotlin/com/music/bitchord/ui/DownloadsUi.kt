package com.music.bitchord.ui

// ios-native: download controls and the Downloads / On this device screens for
// the interim iOS UI. The Android screens (MainActivity's download sheet,
// the Downloads page) come over with the full UI port in P7.

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.music.bitchord.data.LocalMediaRepository
import com.music.bitchord.data.model.Song
import com.music.bitchord.download.DownloadProgress
import com.music.bitchord.download.DownloadSession
import com.music.bitchord.download.DownloadState
import com.music.bitchord.download.DownloadTarget
import com.music.bitchord.download.DownloadedCollection
import com.music.bitchord.download.Downloads
import com.music.bitchord.playback.PlayerController
import kotlinx.coroutines.launch

private val Gray = Color(0xFF9A9AA2)

private fun toast(text: String) = Toast.makeText(Context.app, text, Toast.LENGTH_SHORT).show()

/** Per-track download control: download, progress (tap to cancel), done, or failed (tap to retry). */
@Composable
fun DownloadIcon(song: Song, modifier: Modifier = Modifier) {
    if (song.videoId.startsWith(Downloads.LOCAL_PREFIX)) return
    val saved by Downloads.saved.collectAsState()
    val active by Downloads.active.collectAsState()
    val state = active[song.videoId]
    Box(modifier.size(40.dp), contentAlignment = Alignment.Center) {
        when {
            state is DownloadState.Running || state is DownloadState.Queued -> {
                Box(Modifier.size(40.dp).clickable { Downloads.cancel(song.videoId) }, contentAlignment = Alignment.Center) {
                    val fraction = (state as? DownloadState.Running)?.fraction ?: 0f
                    if (fraction > 0f) {
                        CircularProgressIndicator(progress = { fraction }, modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                    } else {
                        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    }
                    Icon(Icons.Filled.Close, contentDescription = "Cancel download", modifier = Modifier.size(12.dp))
                }
            }
            song.videoId in saved -> Icon(
                Icons.Filled.DownloadDone,
                contentDescription = "Downloaded",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp),
            )
            state is DownloadState.Failed -> IconButton(onClick = {
                toast(state.reason)
                Downloads.enqueue(Context.app, song)
            }) {
                Icon(Icons.Filled.ErrorOutline, contentDescription = "Download failed, retry", tint = MaterialTheme.colorScheme.error)
            }
            else -> IconButton(onClick = { Downloads.enqueue(Context.app, song) }) {
                Icon(Icons.Filled.Download, contentDescription = "Download", tint = Gray)
            }
        }
    }
}

/** An album / playlist page's "Download" button, with how far the batch has got. */
@Composable
fun CollectionDownloadButton(target: DownloadTarget, songs: List<Song>) {
    val wanted = songs.filterNot { it.videoId.startsWith(Downloads.LOCAL_PREFIX) }
    if (wanted.isEmpty()) return
    val saved by Downloads.saved.collectAsState()
    val active by Downloads.active.collectAsState()
    val done = wanted.count { it.videoId in saved }
    val inFlight = wanted.count { active[it.videoId] is DownloadState.Queued || active[it.videoId] is DownloadState.Running }
    when {
        done == wanted.size -> OutlinedButton(onClick = {}, enabled = false) {
            Icon(Icons.Filled.DownloadDone, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text("Downloaded")
        }
        inFlight > 0 -> OutlinedButton(onClick = { wanted.forEach { Downloads.cancel(it.videoId) } }) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
            Text("$done / ${wanted.size} · Cancel")
        }
        else -> OutlinedButton(onClick = {
            Downloads.enqueueAll(Context.app, target, wanted)
            toast("Downloading ${wanted.size - done} songs")
        }) {
            Icon(Icons.Filled.Download, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text(if (done > 0) "Download rest" else "Download")
        }
    }
}

/** A thin bar above the mini player while a batch is running, or finished and not yet looked at. */
@Composable
fun DownloadSessionBar(onOpen: () -> Unit) {
    val session by DownloadSession.state.collectAsState()
    if (!session.visible) return
    Column(
        Modifier.fillMaxWidth().background(Color(0xFF1A1A20)).clickable(onClick = onOpen)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        val text = when {
            session.busy -> "Downloading ${session.finished + session.failed + 1} of ${session.items.size}"
            session.failed > 0 -> "Downloaded ${session.finished}, ${session.failed} failed — tap to see"
            else -> "Downloaded ${session.finished} songs — tap to see"
        }
        Text(text, style = MaterialTheme.typography.bodySmall)
        if (session.busy) {
            Spacer(Modifier.height(4.dp))
            LinearProgressIndicator(progress = { session.fraction }, modifier = Modifier.fillMaxWidth())
        }
    }
}

/** Everything downloaded: the batch in progress, downloaded releases, then every song. */
@Composable
fun DownloadsScreen(player: PlayerController, onClose: () -> Unit) {
    val saved by Downloads.saved.collectAsState()
    val session by DownloadSession.state.collectAsState()
    val playerState by player.state.collectAsState()
    var songs by remember { mutableStateOf<List<Song>?>(null) }
    var openCollection by remember { mutableStateOf<DownloadedCollection?>(null) }
    var confirmDelete by remember { mutableStateOf<Pair<String, suspend () -> Unit>?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(saved) { songs = Downloads.getDownloadedSongs(Context.app) }
    LaunchedEffect(Unit) { DownloadSession.markSeen() }

    confirmDelete?.let { (label, action) ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete download?") },
            text = { Text("$label will be removed from this iPhone.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = null
                    scope.launch { action() }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
        )
    }

    val all = songs.orEmpty()
    val collection = openCollection
    val shown = collection?.songs ?: all
    Surface(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { if (collection != null) openCollection = null else onClose() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                    Text(
                        collection?.title ?: "Downloads",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (collection != null) {
                        IconButton(onClick = {
                            confirmDelete = collection.title to suspend {
                                Downloads.deleteCollection(Context.app, collection.id)
                                openCollection = null
                            }
                        }) { Icon(Icons.Filled.Delete, contentDescription = "Delete download") }
                    }
                }
            }
            if (shown.isNotEmpty()) {
                item { PlayShuffleRow(shown, player) }
            }
            if (collection == null && session.items.isNotEmpty()) {
                item { SectionTitle("This session") }
                items(session.items.sortedBy { it.sequence }, key = { "session:" + it.videoId }) { item ->
                    SessionRow(item)
                }
            }
            if (collection == null) {
                val releases = Downloads.collectionsAmong(all)
                if (releases.isNotEmpty()) {
                    item { SectionTitle("Albums & playlists") }
                    items(releases, key = { "release:" + it.id }) { release ->
                        Row(
                            Modifier.fillMaxWidth().clickable { openCollection = release }
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Artwork(release.thumbnailUrl, Modifier.size(52.dp))
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text(release.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${release.songs.size} songs", color = Gray, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                item { SectionTitle("Songs") }
            }
            when {
                songs == null -> item { Box(Modifier.fillMaxWidth().padding(32.dp), Alignment.Center) { CircularProgressIndicator() } }
                shown.isEmpty() -> item {
                    Text(
                        "Nothing downloaded yet. Tap the download icon next to a song, or Download on an album or playlist.",
                        color = Gray,
                        modifier = Modifier.padding(24.dp),
                    )
                }
            }
            itemsIndexed(shown, key = { _, song -> "song:" + song.videoId }) { index, song ->
                LocalSongRow(
                    song,
                    isCurrent = playerState.current?.videoId == song.videoId,
                    detail = song.downloadFormat,
                    onClick = { player.playQueue(shown, index) },
                    onDelete = {
                        confirmDelete = song.title to suspend { Downloads.delete(Context.app, song.videoId); Unit }
                    },
                )
            }
        }
    }
}

/** Songs imported from the Files app. */
@Composable
fun DeviceScreen(player: PlayerController, onClose: () -> Unit) {
    val songs by LocalMediaRepository.songs.collectAsState()
    val importing by LocalMediaRepository.importing.collectAsState()
    val playerState by player.state.collectAsState()
    var confirmDelete by remember { mutableStateOf<Song?>(null) }

    confirmDelete?.let { song ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Remove song?") },
            text = { Text("${song.title} will be removed from BitChord. The original in Files is not touched.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = null
                    LocalMediaRepository.delete(song)
                }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
        )
    }

    Surface(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                    Text(
                        "On this device",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = { LocalMediaRepository.importFromFiles(::toast) }, enabled = !importing) {
                        Icon(Icons.Filled.FileOpen, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text(if (importing) "Importing…" else "Import from Files")
                    }
                }
            }
            if (songs.isNotEmpty()) {
                item { PlayShuffleRow(songs, player) }
            } else {
                item {
                    Text(
                        "Add MP3, M4A, FLAC, WAV or AIFF files from the Files app — iCloud Drive, On My iPhone, " +
                            "a USB drive or a server connected in Files. They are copied into BitChord and play offline.",
                        color = Gray,
                        modifier = Modifier.padding(24.dp),
                    )
                }
            }
            itemsIndexed(songs, key = { _, song -> song.videoId }) { index, song ->
                LocalSongRow(
                    song,
                    isCurrent = playerState.current?.videoId == song.videoId,
                    detail = song.albumName,
                    onClick = { player.playQueue(songs, index) },
                    onDelete = { confirmDelete = song },
                )
            }
        }
    }
}

@Composable
private fun PlayShuffleRow(songs: List<Song>, player: PlayerController) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Button(onClick = { player.playQueue(songs, 0) }) {
            Icon(Icons.Filled.PlayArrow, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text("Play")
        }
        FilledTonalButton(onClick = { player.playQueue(songs.shuffled(), 0) }) {
            Icon(Icons.Filled.Shuffle, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text("Shuffle")
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun SessionRow(item: DownloadSession.Item) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(item.song.thumbnailUrl, Modifier.size(40.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(item.song.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            when (val progress = item.progress) {
                DownloadProgress.Queued -> Text("Waiting", color = Gray, style = MaterialTheme.typography.bodySmall)
                is DownloadProgress.Running -> LinearProgressIndicator(
                    progress = { progress.fraction },
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                )
                DownloadProgress.Done -> Text("Downloaded", color = Gray, style = MaterialTheme.typography.bodySmall)
                is DownloadProgress.Failed -> Text(
                    progress.reason,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                )
            }
        }
        when (item.progress) {
            DownloadProgress.Queued, is DownloadProgress.Running ->
                IconButton(onClick = { Downloads.cancel(item.videoId) }) {
                    Icon(Icons.Filled.Close, contentDescription = "Cancel")
                }
            is DownloadProgress.Failed ->
                TextButton(onClick = { Downloads.enqueue(Context.app, item.song, item.from) }) { Text("Retry") }
            else -> Unit
        }
    }
}

@Composable
private fun LocalSongRow(song: Song, isCurrent: Boolean, detail: String?, onClick: () -> Unit, onDelete: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 16.dp, top = 8.dp, bottom = 8.dp),
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
                listOfNotNull(song.artist.ifBlank { null }, song.durationText, detail).joinToString(" · "),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                color = Gray,
            )
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Filled.Delete, contentDescription = "Delete", tint = Gray)
        }
    }
}

/** Library entries for the two offline screens, shown signed in or not. */
@Composable
fun OfflineEntries(onDownloads: () -> Unit, onDevice: () -> Unit) {
    val saved by Downloads.saved.collectAsState()
    val local by LocalMediaRepository.songs.collectAsState()
    val downloadedCount = saved.values.toSet().size
    Column {
        OfflineEntry("Downloads", "$downloadedCount songs · play offline", Icons.Filled.DownloadDone, onDownloads)
        OfflineEntry("On this device", "${local.size} songs from Files", Icons.Filled.FileOpen, onDevice)
    }
}

@Composable
private fun OfflineEntry(title: String, subtitle: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(52.dp).background(Color(0xFF24324A), RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, contentDescription = null) }
        Spacer(Modifier.width(14.dp))
        Column {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = Gray, style = MaterialTheme.typography.bodySmall)
        }
    }
}
