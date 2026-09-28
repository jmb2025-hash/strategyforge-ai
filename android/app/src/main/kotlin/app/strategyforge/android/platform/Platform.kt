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
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.time.Instant
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Session token storage encrypted with a non-exportable Android Keystore AES-256-GCM key.
 * Nothing sensitive is written in plain text and the file is excluded from backups.
 */
class KeystoreTokenStore(
    context: Context,
) : TokenStore {
    private val prefs: SharedPreferences = context.getSharedPreferences("sf_secure", Context.MODE_PRIVATE)

    @Volatile
    private var cached: String? = null

    @Volatile
    private var loaded = false

    override fun token(): String? {
        if (!loaded) {
            cached = prefs.getString(KEY, null)?.let { decrypt(it) }
            loaded = true
        }
        return cached
    }

    override fun save(token: String?) {
        cached = token
        loaded = true
        if (token == null) {
            prefs.edit().remove(KEY).apply()
        } else {
            prefs.edit().putString(KEY, encrypt(token)).apply()
        }
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        gen.init(
            KeyGenParameterSpec
                .Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val out = cipher.iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    private fun decrypt(stored: String): String? =
        try {
            val bytes = Base64.decode(stored, Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, bytes, 0, IV_BYTES))
            String(cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES), Charsets.UTF_8)
        } catch (e: GeneralSecurityException) {
            prefs.edit().remove(KEY).apply() // unreadable (e.g. key invalidated): sign in again
            null
        } catch (e: IllegalArgumentException) {
            prefs.edit().remove(KEY).apply()
            null
        }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val ALIAS = "strategyforge-session"
        private const val KEY = "session"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val TAG_BITS = 128
        private const val IV_BYTES = 12
    }
}

/** Non-secret local configuration: backend address, registered device id and privacy choices. */
class LocalConfig(
    context: Context,
) {
    private val prefs = context.getSharedPreferences("sf_config", Context.MODE_PRIVATE)

    var serverUrl: String?
        get() = prefs.getString("serverUrl", null)
        set(v) = prefs.edit().putString("serverUrl", v).apply()

    var deviceId: String?
        get() = prefs.getString("deviceId", null)
        set(v) = prefs.edit().putString("deviceId", v).apply()

    /** Also redact notification text when the device is unlocked (lock-screen text is always redacted). */
    var redactUnlocked: Boolean
        get() = prefs.getBoolean("redactUnlocked", false)
        set(v) = prefs.edit().putBoolean("redactUnlocked", v).apply()

    /** Hide app content from screenshots and the recent-apps preview. */
    var secureScreen: Boolean
        get() = prefs.getBoolean("secureScreen", true)
        set(v) = prefs.edit().putBoolean("secureScreen", v).apply()

    var lastNotifiedId: String?
        get() = prefs.getString("lastNotifiedId", null)
        set(v) = prefs.edit().putString("lastNotifiedId", v).apply()
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

/** Read-through cache of backend responses; it is never the source of truth and is cleared on sign-out. */
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
