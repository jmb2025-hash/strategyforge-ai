package app.strategyforge.android.platform

import android.content.Context
import androidx.room.Room
import app.strategyforge.android.core.api.ApiClient
import app.strategyforge.android.core.api.TokenStore
import app.strategyforge.android.core.cache.CachedResource
import app.strategyforge.android.core.data.Repository
import app.strategyforge.engine.Engine
import app.strategyforge.engine.api.EngineRuntime
import app.strategyforge.engine.api.LocalApiInterceptor
import app.strategyforge.engine.market.CoinbaseProvider
import app.strategyforge.engine.market.KrakenFuturesProvider
import app.strategyforge.engine.market.TwelveDataProvider
import app.strategyforge.engine.research.AiClients
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import java.time.Clock
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @Singleton
    fun localConfig(
        @ApplicationContext context: Context,
    ): LocalConfig = LocalConfig(context)

    @Provides
    @Singleton
    fun tokens(): TokenStore = NoTokens

    /**
     * The on-device engine (D-027): Android SQLite storage, Keystore-held keys, Coinbase public
     * crypto data, Twelve Data stocks with the owner's key (D-032), replay data for demo mode and
     * local notifications.
     */
    @Provides
    @Singleton
    fun engineRuntime(
        @ApplicationContext context: Context,
        config: LocalConfig,
    ): EngineRuntime {
        val backend = AndroidSqlBackend(context)
        val secrets = KeystoreSecretStore(context)
        val coinbase by lazy { CoinbaseProvider(Clock.systemUTC()) }
        val kraken by lazy { KrakenFuturesProvider(Clock.systemUTC()) }
        val yahooTsx by lazy {
            app.strategyforge.engine.tsx
                .YahooTsxProvider()
        }
        val streamClient by lazy {
            app.strategyforge.engine.market.WebSocketQuoteStream
                .client()
        }
        val coinbaseStream by lazy {
            app.strategyforge.engine.market
                .CoinbaseQuoteStream(streamClient, Clock.systemUTC())
        }
        val yahooStream by lazy {
            app.strategyforge.engine.market
                .YahooQuoteStream(streamClient, Clock.systemUTC())
        }
        val aiClients by lazy { AiClients.default() }
        return EngineRuntime(
            create = { host ->
                Engine(
                    backend,
                    fixtureReader = { rel -> context.assets.open("replay/$rel").use { String(it.readBytes(), Charsets.UTF_8) } },
                    cryptoProvider = { coinbase },
                    derivativesProvider = { kraken },
                    tsxProvider = { yahooTsx },
                    cryptoStream = { coinbaseStream },
                    stockStream = { yahooStream },
                    equityProvider = { key -> TwelveDataProvider(key, Clock.systemUTC()) },
                    secrets = secrets,
                    aiClients = aiClients,
                    background = host::background,
                    engineThread = host::post,
                    backupDir = { BackupFiles.dir(context) },
                )
            },
            show = { n -> Notifier.show(context, n, config.redactUnlocked) },
        )
    }

    /** Only the in-process engine answers; the app makes no calls to any server of its own. */
    @Provides
    @Singleton
    fun http(runtime: EngineRuntime): OkHttpClient =
        OkHttpClient
            .Builder()
            .addInterceptor(runtime.interceptor())
            .callTimeout(10, TimeUnit.MINUTES)
            .build()

    @Provides
    @Singleton
    fun api(
        http: OkHttpClient,
        tokens: TokenStore,
    ): ApiClient = ApiClient({ LocalApiInterceptor.BASE_URL }, http, tokens, retryDelaysMs = emptyList())

    @Provides
    @Singleton
    fun database(
        @ApplicationContext context: Context,
    ): CacheDatabase =
        Room
            .databaseBuilder(context, CacheDatabase::class.java, "sf-cache.db")
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()

    @Provides
    @Singleton
    fun repository(
        api: ApiClient,
        db: CacheDatabase,
        tokens: TokenStore,
    ): Repository = Repository(api, CachedResource(RoomCacheStore(db.cache())), tokens)
}
