package app.strategyforge.android.platform

import android.content.Context
import androidx.room.Room
import app.strategyforge.android.BuildConfig
import app.strategyforge.android.core.api.ApiClient
import app.strategyforge.android.core.api.TokenStore
import app.strategyforge.android.core.cache.CachedResource
import app.strategyforge.android.core.data.Repository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
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
    fun tokens(
        @ApplicationContext context: Context,
    ): TokenStore = KeystoreTokenStore(context)

    @Provides
    @Singleton
    fun http(): OkHttpClient =
        OkHttpClient
            .Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()

    @Provides
    @Singleton
    fun api(
        config: LocalConfig,
        http: OkHttpClient,
        tokens: TokenStore,
    ): ApiClient = ApiClient({ config.serverUrl }, http, tokens)

    @Provides
    @Singleton
    fun database(
        @ApplicationContext context: Context,
    ): CacheDatabase =
        Room
            .databaseBuilder(context, CacheDatabase::class.java, "sf-cache.db")
            .fallbackToDestructiveMigration()
            .build()

    @Provides
    @Singleton
    fun repository(
        api: ApiClient,
        db: CacheDatabase,
        tokens: TokenStore,
    ): Repository = Repository(api, CachedResource(RoomCacheStore(db.cache())), tokens)

    @Provides
    @AllowInsecureLocal
    fun allowInsecureLocal(): Boolean = BuildConfig.ALLOW_INSECURE_LOCAL
}

@javax.inject.Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class AllowInsecureLocal
