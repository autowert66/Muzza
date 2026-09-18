package com.maloy.muzza.db.entities

/**
 * Lightweight join rows used to check download completeness without materializing full [Song]
 * relation graphs.
 */
data class AlbumSongPair(
    val albumId: String,
    val songId: String,
)

data class PlaylistSongPair(
    val playlistId: String,
    val songId: String,
)
