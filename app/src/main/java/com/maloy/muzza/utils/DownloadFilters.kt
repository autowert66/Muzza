package com.maloy.muzza.utils

/**
 * A playlist is considered downloaded when it has at least one song and every song is completed.
 */
fun isPlaylistFullyDownloaded(songIds: Collection<String>, completedIds: Set<String>): Boolean =
    songIds.isNotEmpty() && songIds.all { it in completedIds }

/**
 * An album is considered downloaded when every mapped song is completed. Kept separate from
 * [isPlaylistFullyDownloaded] to preserve the previous behaviour where an album with no mapped
 * songs is treated as downloaded (the old `songs.all { ... }` returned true on an empty list).
 */
fun isAlbumFullyDownloaded(songIds: Collection<String>?, completedIds: Set<String>): Boolean =
    songIds.orEmpty().all { it in completedIds }
