package com.music.bitchord.ui

import com.music.bitchord.data.DebugLog as Log
import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.LibraryPage
import com.music.bitchord.data.model.LikeStatus
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.model.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Loading / loaded / failed, as the Android screens' UiState. */
sealed interface Load<out T> {
    data object Loading : Load<Nothing>
    data class Ready<T>(val value: T) : Load<T>
    data class Failed(val message: String) : Load<Nothing>
}

/**
 * Home feed, Library and the album/playlist/artist pages opened from them —
 * the browse half of the Android MainViewModel, on the same YtMusicRepository.
 */
class LibraryViewModel {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    data class HomeState(val shelves: List<HomeShelf> = emptyList(), val continuation: String? = null)

    private val _home = MutableStateFlow<Load<HomeState>>(Load.Loading)
    val home: StateFlow<Load<HomeState>> = _home.asStateFlow()
    private var loadingMoreHome = false

    private val _library = MutableStateFlow<Load<LibraryPage>>(Load.Loading)
    val library: StateFlow<Load<LibraryPage>> = _library.asStateFlow()

    /** A page opened from a card: its own header and tracks. */
    data class Page(
        val browseId: String,
        val title: String,
        val subtitle: String,
        val thumbnailUrl: String?,
        val songs: List<Song> = emptyList(),
        val sections: List<HomeShelf> = emptyList(),
        val continuation: String? = null,
        val loading: Boolean = true,
        val error: String? = null,
    )

    private val _pages = MutableStateFlow<List<Page>>(emptyList())
    /** The stack of opened pages; the last one is on screen. */
    val pages: StateFlow<List<Page>> = _pages.asStateFlow()

    /** Like status the user set this session, over what pages reported. */
    private val _likes = MutableStateFlow<Map<String, LikeStatus>>(emptyMap())
    val likes: StateFlow<Map<String, LikeStatus>> = _likes.asStateFlow()

    fun loadHome() {
        _home.value = Load.Loading
        scope.launch {
            YtMusicRepository.home()
                .onSuccess { feed -> _home.value = Load.Ready(HomeState(feed.shelves, feed.continuation)) }
                .onFailure { _home.value = Load.Failed(it.message ?: "Could not load Home") }
        }
    }

    fun loadMoreHome() {
        val state = (_home.value as? Load.Ready)?.value ?: return
        val token = state.continuation ?: return
        if (loadingMoreHome) return
        loadingMoreHome = true
        scope.launch {
            YtMusicRepository.moreHome(token)
                .onSuccess { more ->
                    _home.value = Load.Ready(HomeState(state.shelves + more.shelves, more.continuation))
                }
                .onFailure { _home.value = Load.Ready(state.copy(continuation = null)) }
            loadingMoreHome = false
        }
    }

    fun loadLibrary() {
        _library.value = Load.Loading
        scope.launch {
            YtMusicRepository.library()
                .onSuccess { page ->
                    _library.value = Load.Ready(page)
                    _likes.update { current -> page.likedSongs.associate { it.videoId to LikeStatus.LIKE } + current }
                }
                .onFailure { _library.value = Load.Failed(it.message ?: "Could not load your library") }
        }
    }

    fun open(item: ShelfItem) {
        val browseId = item.browseId ?: return
        openPage(browseId, item.title, item.subtitle, item.thumbnailUrl)
    }

    fun openSongs(title: String, songs: List<Song>) {
        _pages.update {
            it + Page(
                browseId = "local:$title",
                title = title,
                subtitle = "${songs.size} songs",
                thumbnailUrl = songs.firstOrNull()?.thumbnailUrl,
                songs = songs,
                loading = false,
            )
        }
    }

    /** Your YouTube Music listening history, newest first. */
    fun openHistory() {
        val id = "local:history"
        _pages.update { it + Page(id, "History", "Played on any device", null) }
        scope.launch {
            YtMusicRepository.history()
                .onSuccess { songs ->
                    updatePage(id) {
                        it.copy(
                            songs = songs,
                            subtitle = "${songs.size} songs",
                            thumbnailUrl = songs.firstOrNull()?.thumbnailUrl,
                            loading = false,
                        )
                    }
                }
                .onFailure { e -> updatePage(id) { it.copy(loading = false, error = e.message ?: "Could not load history") } }
        }
    }

    fun openPage(browseId: String, title: String, subtitle: String, thumbnailUrl: String?) {
        _pages.update { it + Page(browseId, title, subtitle, thumbnailUrl) }
        scope.launch {
            if (browseId.startsWith("UC")) {
                YtMusicRepository.artistPage(browseId)
                    .onSuccess { artist ->
                        updatePage(browseId) {
                            it.copy(
                                title = artist.name ?: it.title,
                                subtitle = artist.subscriberCountText ?: artist.monthlyListenerCount ?: it.subtitle,
                                thumbnailUrl = artist.thumbnailUrl ?: it.thumbnailUrl,
                                songs = artist.songs,
                                sections = artist.sections,
                                loading = false,
                            )
                        }
                    }
                    .onFailure { e -> updatePage(browseId) { it.copy(loading = false, error = e.message) } }
            } else {
                YtMusicRepository.browseSongs(browseId)
                    .onSuccess { page ->
                        updatePage(browseId) {
                            it.copy(
                                title = page.header?.title?.ifBlank { null } ?: it.title,
                                subtitle = page.header?.subtitle?.ifBlank { null } ?: it.subtitle,
                                thumbnailUrl = page.header?.thumbnailUrl ?: it.thumbnailUrl,
                                songs = page.songs,
                                continuation = page.continuation,
                                loading = false,
                            )
                        }
                    }
                    .onFailure { e -> updatePage(browseId) { it.copy(loading = false, error = e.message) } }
            }
        }
    }

    fun loadMorePage(browseId: String) {
        val page = _pages.value.lastOrNull { it.browseId == browseId } ?: return
        val token = page.continuation ?: return
        updatePage(browseId) { it.copy(continuation = null) }
        scope.launch {
            YtMusicRepository.moreSongs(token).onSuccess { more ->
                updatePage(browseId) { it.copy(songs = it.songs + more.songs, continuation = more.continuation) }
            }
        }
    }

    /** @return false when there was no page to close. */
    fun closePage(): Boolean {
        if (_pages.value.isEmpty()) return false
        _pages.update { it.dropLast(1) }
        return true
    }

    fun likeStatusOf(videoId: String): LikeStatus = _likes.value[videoId] ?: LikeStatus.INDIFFERENT

    fun toggleLike(song: Song) {
        val next = if (likeStatusOf(song.videoId) == LikeStatus.LIKE) LikeStatus.INDIFFERENT else LikeStatus.LIKE
        val before = _likes.value
        _likes.update { it + (song.videoId to next) }
        scope.launch {
            YtMusicRepository.rate(song.videoId, next).onFailure {
                Log.w("BitChord", "like failed: ${it.message}")
                _likes.value = before
            }
        }
    }

    fun reloadForAccount() {
        _pages.value = emptyList()
        _likes.value = emptyMap()
        loadHome()
        loadLibrary()
    }

    private fun updatePage(browseId: String, change: (Page) -> Page) {
        _pages.update { stack ->
            val index = stack.indexOfLast { it.browseId == browseId }
            if (index < 0) stack else stack.toMutableList().also { it[index] = change(it[index]) }
        }
    }
}

/** A track card from a shelf, as a playable Song. */
fun ShelfItem.toSong(): Song? = videoId?.let {
    Song(videoId = it, title = title, artist = subtitle.substringBefore(" • "), thumbnailUrl = thumbnailUrl)
}
