package com.maloy.muzza.di

import android.content.Context
import androidx.media3.database.DatabaseProvider
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import com.maloy.muzza.constants.MaxSongCacheSizeKey
import com.maloy.muzza.db.InternalDatabase
import com.maloy.muzza.db.MusicDatabase
import com.maloy.muzza.listentogether.ListenTogetherClient
import com.maloy.muzza.listentogether.ListenTogetherManager
import com.maloy.muzza.utils.DynamicSizeLruCacheEvictor
import com.maloy.muzza.utils.dataStore
import com.maloy.muzza.utils.get
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ImageCache

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class PlayerCache

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DownloadCache

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Singleton
    @Provides
    fun provideDatabase(@ApplicationContext context: Context): MusicDatabase =
        InternalDatabase.newInstance(context)

    @Singleton
    @Provides
    fun provideDatabaseProvider(@ApplicationContext context: Context): DatabaseProvider =
        StandaloneDatabaseProvider(context)

    @Singleton
    @Provides
    @PlayerCache
    fun providePlayerCache(@ApplicationContext context: Context, databaseProvider: DatabaseProvider): SimpleCache {
        // The evictor consults this on every cache write, so keep the preference in memory and
        // update it asynchronously instead of blocking on DataStore from the cache's callbacks.
        val maxSongCacheSize = MutableStateFlow(context.dataStore[MaxSongCacheSizeKey] ?: 1024)
        applicationScope.launch {
            context.dataStore.data
                .map { it[MaxSongCacheSizeKey] ?: 1024 }
                .distinctUntilChanged()
                .collect { maxSongCacheSize.value = it }
        }
        val constructor = {
            SimpleCache(
                context.filesDir.resolve("exoplayer"),
                DynamicSizeLruCacheEvictor {
                    when (val cacheSize = maxSongCacheSize.value) {
                        -1 -> Long.MAX_VALUE
                        else -> cacheSize * 1024 * 1024L
                    }
                },
                databaseProvider
            )
        }
        constructor().release()
        return constructor()
    }

    @Singleton
    @Provides
    @DownloadCache
    fun provideDownloadCache(@ApplicationContext context: Context, databaseProvider: DatabaseProvider): SimpleCache {
        val constructor = {
            SimpleCache(context.filesDir.resolve("download"), NoOpCacheEvictor(), databaseProvider)
        }
        constructor().release()
        return constructor()
    }
    @Provides
    @Singleton
    fun provideHttpClient(): HttpClient {
        return HttpClient(OkHttp) {
            install(ContentNegotiation) {
                json(Json {
                    ignoreUnknownKeys = true
                    coerceInputValues = true
                })
            }
        }
    }
    @Singleton
    @Provides
    fun provideListenTogetherClient(
        @ApplicationContext context: Context,
    ): ListenTogetherClient = ListenTogetherClient(context)

    @Singleton
    @Provides
    fun provideListenTogetherManager(
        @ApplicationContext context: Context,
        client: ListenTogetherClient,
    ): ListenTogetherManager = ListenTogetherManager(client, context)
}
