package com.maloy.muzza.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadFiltersTest {
    @Test
    fun `playlist requires at least one song`() {
        assertFalse(isPlaylistFullyDownloaded(emptyList(), emptySet()))
        assertFalse(isPlaylistFullyDownloaded(emptyList(), setOf("a")))
    }

    @Test
    fun `playlist is downloaded only when every song is completed`() {
        assertTrue(isPlaylistFullyDownloaded(listOf("a", "b"), setOf("a", "b", "c")))
        assertFalse(isPlaylistFullyDownloaded(listOf("a", "b"), setOf("a")))
    }

    @Test
    fun `album with no mapped songs keeps the legacy downloaded behaviour`() {
        assertTrue(isAlbumFullyDownloaded(null, emptySet()))
        assertTrue(isAlbumFullyDownloaded(emptyList(), emptySet()))
    }

    @Test
    fun `album is downloaded when all mapped songs are completed`() {
        assertTrue(isAlbumFullyDownloaded(listOf("a", "b"), setOf("a", "b")))
        assertFalse(isAlbumFullyDownloaded(listOf("a", "b"), setOf("a")))
    }
}
