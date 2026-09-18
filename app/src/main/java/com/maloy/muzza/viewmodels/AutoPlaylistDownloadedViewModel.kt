package com.maloy.muzza.viewmodels

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.maloy.muzza.constants.SongSortDescendingKey
import com.maloy.muzza.constants.SongSortType
import com.maloy.muzza.constants.SongSortTypeKey
import com.maloy.muzza.db.entities.Song
import com.maloy.muzza.extensions.reversed
import com.maloy.muzza.extensions.toEnum
import com.maloy.muzza.playback.DownloadUtil
import com.maloy.muzza.utils.dataStore
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class AutoPlaylistDownloadedViewModel @Inject constructor(
    @ApplicationContext context: Context,
    private val downloadUtil: DownloadUtil,
) : ViewModel() {
    // `null` while the shared download list is still loading, so the UI can distinguish
    // "not loaded yet" from "no downloads".
    val downloadedSongs: StateFlow<List<Song>?> = combine(
        context.dataStore.data
            .map {
                it[SongSortTypeKey].toEnum(SongSortType.CREATE_DATE) to
                    (it[SongSortDescendingKey] ?: true)
            }
            .distinctUntilChanged(),
        downloadUtil.downloadedSongs
    ) { (sortType, descending), songs ->
        songs?.let {
            when (sortType) {
                SongSortType.CREATE_DATE -> it
                SongSortType.NAME -> it.sortedBy { song -> song.song.title }
                SongSortType.ARTIST -> it.sortedBy { song ->
                    song.artists.joinToString(separator = "") { it.name }
                }

                SongSortType.PLAY_TIME -> it.sortedBy { song -> song.song.totalPlayTime }
            }.reversed(descending)
        }
    }.stateIn(viewModelScope, SharingStarted.Lazily, null)
}
