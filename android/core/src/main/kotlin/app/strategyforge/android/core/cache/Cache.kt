package app.strategyforge.android.core.cache

import app.strategyforge.android.core.api.ApiError
import app.strategyforge.android.core.api.SfJson
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement
import java.time.Clock
import java.time.Duration
import java.time.Instant

/** One cached backend response (Android: a Room row). The backend stays authoritative (NFR-003). */
data class CacheEntry(
    val key: String,
    val json: String,
    val fetchedAt: Instant,
)

interface CacheStore {
    suspend fun read(key: String): CacheEntry?

    suspend fun write(entry: CacheEntry)

    suspend fun clear()
}

/** In-memory store for tests and for the first launch before Room is ready. */
class MemoryCacheStore : CacheStore {
    private val map = java.util.concurrent.ConcurrentHashMap<String, CacheEntry>()

    override suspend fun read(key: String) = map[key]

    override suspend fun write(entry: CacheEntry) {
        map[entry.key] = entry
    }

    override suspend fun clear() = map.clear()
}

/**
 * What a screen shows. Cached data is always labelled with its age, and whether a refresh failed
 * because the device is offline, so stale data is never presented as current.
 */
sealed interface Resource<out T> {
    data object Loading : Resource<Nothing>

    data class Data<T>(
        val value: T,
        val fetchedAt: Instant,
        val fromCache: Boolean,
        val stale: Boolean,
        val refreshing: Boolean,
        val offline: Boolean = false,
        val error: ApiError? = null,
    ) : Resource<T>

    data class Failure(
        val error: ApiError,
    ) : Resource<Nothing>
}

val Resource<*>.isOffline: Boolean get() = (this as? Resource.Data<*>)?.offline == true || (this as? Resource.Failure)?.error is ApiError.Offline

/**
 * Cache-then-network: emit the cached copy immediately (fast dashboard load, NFR-005), then the
 * authoritative network copy, which replaces the cache. Network failures keep the cached copy
 * visible with an Offline/stale label; without a cache the failure is shown.
 */
class CachedResource(
    private val store: CacheStore,
    private val clock: Clock = Clock.systemUTC(),
    private val staleAfter: Duration = Duration.ofMinutes(2),
) {
    fun <T> load(
        key: String,
        serializer: KSerializer<T>,
        fetch: suspend () -> JsonElement,
    ): Flow<Resource<T>> =
        flow {
            val cached = store.read(key)?.let { e -> decode(e.json, serializer)?.let { it to e.fetchedAt } }
            if (cached == null) {
                emit(Resource.Loading)
            } else {
                emit(Resource.Data(cached.first, cached.second, fromCache = true, stale = isStale(cached.second), refreshing = true))
            }
            try {
                val element = fetch()
                val value = SfJson.decodeFromJsonElement(serializer, element)
                val now = clock.instant()
                store.write(CacheEntry(key, SfJson.encodeToString(JsonElement.serializer(), element), now))
                emit(Resource.Data(value, now, fromCache = false, stale = false, refreshing = false))
            } catch (e: ApiError) {
                emit(fallback(cached, e))
            } catch (e: SerializationException) {
                emit(fallback(cached, ApiError.Malformed(e)))
            } catch (e: IllegalArgumentException) {
                emit(fallback(cached, ApiError.Malformed(e)))
            }
        }

    suspend fun clear() = store.clear()

    private fun <T> fallback(
        cached: Pair<T, Instant>?,
        e: ApiError,
    ): Resource<T> =
        if (cached == null) {
            Resource.Failure(e)
        } else {
            Resource.Data(cached.first, cached.second, fromCache = true, stale = true, refreshing = false, offline = e is ApiError.Offline, error = e)
        }

    private fun isStale(at: Instant) = Duration.between(at, clock.instant()) > staleAfter

    private fun <T> decode(
        json: String,
        serializer: KSerializer<T>,
    ): T? =
        try {
            SfJson.decodeFromString(serializer, json)
        } catch (e: SerializationException) {
            null // An incompatible cache entry is ignored; the network copy replaces it.
        } catch (e: IllegalArgumentException) {
            null
        }
}
