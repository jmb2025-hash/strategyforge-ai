package app.strategyforge.common.security

import app.strategyforge.common.config.StrategyForgeProperties
import org.springframework.stereotype.Component
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.HexFormat
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-256-GCM encryption of secrets at rest using the external master key
 * (FR-031, section 15). Ciphertext layout: [version=1][12-byte IV][ciphertext+tag].
 * The associated data binds a ciphertext to its owning record so it cannot be
 * transplanted to another row.
 */
@Component
class SecretCipher(
    props: StrategyForgeProperties,
) {
    private val rawKey: ByteArray =
        decodeKey(props.masterEncryptionKey, "MASTER_ENCRYPTION_KEY").also {
            require(it.size == 32) { "MASTER_ENCRYPTION_KEY must be a base64-encoded 32-byte key (got ${it.size} bytes)" }
        }
    private val key = SecretKeySpec(rawKey, "AES")
    val keyId: String = Crypto.sha256Hex(rawKey).take(12)
    private val random = SecureRandom()

    fun encrypt(
        plaintext: ByteArray,
        associatedData: String,
    ): ByteArray {
        val iv = ByteArray(IV_LEN).also(random::nextBytes)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        c.updateAAD(associatedData.toByteArray(Charsets.UTF_8))
        return byteArrayOf(VERSION) + iv + c.doFinal(plaintext)
    }

    fun decrypt(
        blob: ByteArray,
        associatedData: String,
    ): ByteArray {
        require(blob.size > 1 + IV_LEN && blob[0] == VERSION) { "Unsupported ciphertext format" }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, blob, 1, IV_LEN))
        c.updateAAD(associatedData.toByteArray(Charsets.UTF_8))
        return c.doFinal(blob, 1 + IV_LEN, blob.size - 1 - IV_LEN)
    }

    fun encryptString(
        plaintext: String,
        associatedData: String,
    ): ByteArray = encrypt(plaintext.toByteArray(Charsets.UTF_8), associatedData)

    fun decryptString(
        blob: ByteArray,
        associatedData: String,
    ): String = String(decrypt(blob, associatedData), Charsets.UTF_8)

    /** Derives an independent sub-key (HKDF-like, HMAC-SHA256) for a named purpose, e.g. backups. */
    fun deriveKey(purpose: String): ByteArray = deriveKey(key.encoded, purpose)

    companion object {
        private const val VERSION: Byte = 1
        private const val IV_LEN = 12
        private const val TAG_BITS = 128

        /** Same derivation without a Spring context (offline restore command). */
        fun deriveKey(
            masterKey: ByteArray,
            purpose: String,
        ): ByteArray = Crypto.hmacSha256(masterKey, "strategyforge-subkey:$purpose".toByteArray())

        fun decodeKey(
            value: String,
            name: String,
        ): ByteArray {
            require(value.isNotBlank()) { "$name is required. Generate one with scripts/generate-secrets.sh" }
            return try {
                Base64.getDecoder().decode(value.trim())
            } catch (e: IllegalArgumentException) {
                throw IllegalArgumentException("$name must be base64-encoded", e)
            }
        }
    }
}

/** HMAC signing key for action tokens and code peppering (JWT_SIGNING_KEY). */
@Component
class SigningKey(
    props: StrategyForgeProperties,
) {
    private val key: ByteArray = SecretCipher.decodeKey(props.jwtSigningKey, "JWT_SIGNING_KEY")

    init {
        require(key.size >= 32) { "JWT_SIGNING_KEY must decode to at least 32 bytes" }
    }

    fun hmacHex(value: String): String = HexFormat.of().formatHex(Crypto.hmacSha256(key, value.toByteArray(Charsets.UTF_8)))
}

object Crypto {
    private val random = SecureRandom()

    fun randomBytes(n: Int): ByteArray = ByteArray(n).also(random::nextBytes)

    fun randomToken(bytes: Int = 32): String = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes(bytes))

    fun sha256Hex(data: ByteArray): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data))

    fun sha256Hex(s: String): String = sha256Hex(s.toByteArray(Charsets.UTF_8))

    fun hmacSha256(
        key: ByteArray,
        data: ByteArray,
    ): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    fun constantTimeEquals(
        a: String,
        b: String,
    ): Boolean = MessageDigest.isEqual(a.toByteArray(), b.toByteArray())

    /** Short non-reversible fingerprint that lets the owner recognise a credential without disclosing it. */
    fun fingerprint(secret: String): String = sha256Hex(secret).take(8)
}
