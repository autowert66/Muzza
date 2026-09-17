package com.maloy.muzza.models

import java.io.Serializable

data class PersistQueue(
    val title: String?,
    val items: List<MediaMetadata>,
    val mediaItemIndex: Int,
    val position: Long,
    val shuffleModeEnabled: Boolean = false,
    // Physical media item indices, in the order they are played. Empty when shuffle is off.
    val shuffleOrder: List<Int> = emptyList(),
) : Serializable {
    private companion object {
        const val serialVersionUID = 1L
    }
}
