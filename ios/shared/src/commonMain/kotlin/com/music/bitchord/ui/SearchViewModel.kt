package com.music.bitchord.ui

import com.music.bitchord.data.DebugLog as Log
import com.music.bitchord.data.innertube.Innertube
import com.music.bitchord.data.innertube.InnertubeParser
import com.music.bitchord.data.model.SearchFilter
import com.music.bitchord.data.model.SearchResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Search screen state: typeahead suggestions while typing, results on submit. */
class SearchViewModel {

    data class State(
        val query: String = "",
        val filter: SearchFilter = SearchFilter.SONGS,
        val suggestions: List<String> = emptyList(),
        val results: List<SearchResult> = emptyList(),
        val continuation: String? = null,
        val isLoading: Boolean = false,
        val isLoadingMore: Boolean = false,
        val submitted: Boolean = false,
        val error: String? = null,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var suggestJob: Job? = null
    private var searchJob: Job? = null

    fun onQueryChange(query: String) {
        _state.update { it.copy(query = query, submitted = false) }
        suggestJob?.cancel()
        if (query.isBlank()) {
            _state.update { it.copy(suggestions = emptyList()) }
            return
        }
        suggestJob = scope.launch {
            delay(SUGGEST_DEBOUNCE_MS)
            try {
                val list = InnertubeParser.parseSearchSuggestions(Innertube.searchSuggestions(query))
                _state.update { if (it.query == query) it.copy(suggestions = list) else it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.d(TAG, "suggestions failed: ${e.message}")
            }
        }
    }

    fun onFilterChange(filter: SearchFilter) {
        _state.update { it.copy(filter = filter) }
        if (_state.value.submitted) submit()
    }

    fun submit(query: String = _state.value.query) {
        if (query.isBlank()) return
        suggestJob?.cancel()
        searchJob?.cancel()
        val filter = _state.value.filter
        _state.update {
            it.copy(query = query, submitted = true, suggestions = emptyList(), isLoading = true, error = null)
        }
        searchJob = scope.launch {
            try {
                val page = InnertubeParser.parseSearchPage(
                    Innertube.search(query, filter.params),
                    includeVideos = filter == SearchFilter.VIDEOS,
                )
                _state.update { it.copy(results = page.rows, continuation = page.continuation, isLoading = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "search failed: ${e.message}")
                _state.update { it.copy(isLoading = false, error = e.message ?: "Search failed") }
            }
        }
    }

    fun loadMore() {
        val s = _state.value
        val token = s.continuation ?: return
        if (s.isLoadingMore || s.isLoading) return
        _state.update { it.copy(isLoadingMore = true) }
        scope.launch {
            try {
                val page = InnertubeParser.parseSearchPage(
                    Innertube.searchContinuation(token),
                    includeVideos = s.filter == SearchFilter.VIDEOS,
                )
                _state.update {
                    it.copy(results = it.results + page.rows, continuation = page.continuation, isLoadingMore = false)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(isLoadingMore = false, continuation = null) }
            }
        }
    }

    private companion object {
        const val TAG = "BitChord"
        const val SUGGEST_DEBOUNCE_MS = 250L
    }
}
