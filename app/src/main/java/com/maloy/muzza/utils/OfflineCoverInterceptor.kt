package com.maloy.muzza.utils

import android.content.Context
import coil.intercept.Interceptor
import coil.request.ImageResult

/**
 * Serves already-downloaded cover art from [CoverStore] instead of the network.
 *
 * Because this runs before caching/decoding, a single persisted cover satisfies every
 * requested size variant, so downloaded songs show their artwork fully offline even if
 * the user has never scrolled the item into composition.
 */
class OfflineCoverInterceptor(private val context: Context) : Interceptor {
    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val data = chain.request.data
        if (data is String && data.isNotEmpty()) {
            val file = CoverStore.coverFile(context, data)
            if (file.exists()) {
                return chain.proceed(chain.request.newBuilder().data(file).build())
            }
        }
        return chain.proceed(chain.request)
    }
}
