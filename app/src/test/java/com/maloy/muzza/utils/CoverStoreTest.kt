package com.maloy.muzza.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class CoverStoreTest {
    @Test
    fun `googleusercontent variants normalize to the same key`() {
        val base = "https://lh3.googleusercontent.com/abc123"
        assertEquals(base, canonicalCoverUrl("$base=w120-h120-p-l90-rj"))
        assertEquals(base, canonicalCoverUrl("$base=s200-p-l90-rj"))
        assertEquals(base, canonicalCoverUrl(base))
    }

    @Test
    fun `ggpht variants normalize to the same key`() {
        val base = "https://yt3.ggpht.com/def456"
        assertEquals(base, canonicalCoverUrl("$base=w544-h544-l90-rj"))
    }

    @Test
    fun `ytimg variants normalize to the same key`() {
        val expected = "https://i.ytimg.com/vi/VIDEO/default.jpg"
        assertEquals(expected, canonicalCoverUrl("https://i.ytimg.com/vi/VIDEO/hqdefault.jpg"))
        assertEquals(expected, canonicalCoverUrl("https://i.ytimg.com/vi/VIDEO/maxresdefault.jpg"))
    }

    @Test
    fun `unrelated urls are unchanged`() {
        val url = "https://example.com/a/b.jpg"
        assertEquals(url, canonicalCoverUrl(url))
    }
}
