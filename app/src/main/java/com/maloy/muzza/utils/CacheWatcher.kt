package com.maloy.muzza.utils

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Emits immediately and then on a fixed interval, giving screens a single low-frequency trigger
 * to recompute cache-derived state.
 *
 * media3 1.7.x only exposes per-key `Cache.Listener` registrations, so there is no global cache
 * change callback to subscribe to. Consumers combine these ticks inside a `WhileSubscribed` flow,
 * which means the work only runs (off the main thread) while a screen is actually observing it —
 * unlike the previous per-screen `while (true) { ... delay(1000) }` loops that ran forever on the
 * main thread.
 */
@Singleton
class CacheWatcher @Inject constructor() {
    val ticks: Flow<Unit> = flow {
        while (currentCoroutineContext().isActive) {
            emit(Unit)
            delay(POLL_INTERVAL_MS)
        }
    }

    companion object {
        private const val POLL_INTERVAL_MS = 2_000L
    }
}
