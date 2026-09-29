package app.strategyforge.android.platform

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import app.strategyforge.android.core.api.TokenStore
import app.strategyforge.android.core.cache.CacheEntry
import app.strategyforge.android.core.cache.CacheStore
import app.strategyforge.engine.research.SecretStore
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.time.Instant
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-256-GCM with a non-exportable Android Keystore key. Values are encrypted before they reach
 * app storage, and the storage file is excluded from device and cloud backups.
 */
class KeystoreCipher(
    private val alias: String,
) {
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        gen.init(
            KeyGenParameterSpec
                .Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val out = cipher.iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    /** Null when the value cannot be decrypted (for example the key was invalidated). */
    fun decrypt(stored: String): String? =
        try {
            val bytes = Base64.decode(stored, Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, bytes, 0, IV_BYTES))
            String(cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES), Charsets.UTF_8)
        } catch (e: GeneralSecurityException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_BITS = 128
        const val IV_BYTES = 12
    }
}

/**
 * AI provider keys (D-030): encrypted with the Keystore before they are written, never stored in
 * the engine database, backups, exports or logs.
 */
class KeystoreSecretStore(
    context: Context,
) : SecretStore {
    private val prefs: SharedPreferences = context.getSharedPreferences("sf_secrets", Context.MODE_PRIVATE)
    private val cipher = KeystoreCipher("strategyforge-secrets")

    override fun put(
        alias: String,
        secret: String,
    ) {
        prefs.edit().putString(alias, cipher.encrypt(secret)).apply()
    }

    override fun get(alias: String): String? = prefs.getString(alias, null)?.let { cipher.decrypt(it) }

    override fun delete(alias: String) {
        prefs.edit().remove(alias).apply()
    }
}

/** The app talks only to the on-device engine, so there is no session token (D-031). */
object NoTokens : TokenStore {
    override fun token(): String? = null

    override fun save(token: String?) = Unit
}

/** Non-secret local configuration: privacy, app lock and background choices. */
class LocalConfig(
    context: Context,
) {
    private val prefs = context.getSharedPreferences("sf_config", Context.MODE_PRIVATE)

    /** Also redact notification text when the device is unlocked (lock-screen text is always redacted). */
    var redactUnlocked: Boolean
        get() = prefs.getBoolean("redactUnlocked", false)
        set(v) = prefs.edit().putBoolean("redactUnlocked", v).apply()

    /** Hide app content from screenshots and the recent-apps preview. */
    var secureScreen: Boolean
        get() = prefs.getBoolean("secureScreen", true)
        set(v) = prefs.edit().putBoolean("secureScreen", v).apply()

    /** Ask for the device lock (fingerprint, face or PIN) when the app is opened. */
    var appLock: Boolean
        get() = prefs.getBoolean("appLock", true)
        set(v) = prefs.edit().putBoolean("appLock", v).apply()

    /** Keep the engine running in the background (foreground service). */
    var runInBackground: Boolean
        get() = prefs.getBoolean("runInBackground", true)
        set(v) = prefs.edit().putBoolean("runInBackground", v).apply()

    /** Keep the processor awake while the screen is off, so live evaluation never pauses. */
    var keepAwake: Boolean
        get() = prefs.getBoolean("keepAwake", true)
        set(v) = prefs.edit().putBoolean("keepAwake", v).apply()
}

// ------------------------------------------------------------------------------ Room cache (NFR-003)

@Entity(tableName = "cached_responses")
data class CachedResponse(
    @PrimaryKey val key: String,
    val json: String,
    val fetchedAtEpochMs: Long,
)

@Dao
interface CachedResponseDao {
    @Query("SELECT * FROM cached_responses WHERE `key` = :key")
    suspend fun get(key: String): CachedResponse?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(row: CachedResponse)

    @Query("DELETE FROM cached_responses")
    suspend fun clear()
}

/** Read-through cache of engine responses for fast screen starts; the engine stays the source of truth. */
@Database(entities = [CachedResponse::class], version = 1, exportSchema = false)
abstract class CacheDatabase : RoomDatabase() {
    abstract fun cache(): CachedResponseDao
}

class RoomCacheStore(
    private val dao: CachedResponseDao,
) : CacheStore {
    override suspend fun read(key: String): CacheEntry? = dao.get(key)?.let { CacheEntry(it.key, it.json, Instant.ofEpochMilli(it.fetchedAtEpochMs)) }

    override suspend fun write(entry: CacheEntry) = dao.put(CachedResponse(entry.key, entry.json, entry.fetchedAt.toEpochMilli()))

    override suspend fun clear() = dao.clear()
}
