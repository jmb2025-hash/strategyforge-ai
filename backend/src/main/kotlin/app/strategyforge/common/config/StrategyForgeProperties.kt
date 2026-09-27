package app.strategyforge.common.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties(prefix = "strategyforge")
data class StrategyForgeProperties(
    /** Must be exactly "false". Any other value prevents startup (FR-113). */
    val realMoneyTradingEnabled: String = "false",
    /** Base64-encoded 32-byte key used for AES-256-GCM encryption of secrets. */
    val masterEncryptionKey: String = "",
    /** Base64-encoded key (>= 32 bytes) used for HMAC of single-use action tokens. */
    val jwtSigningKey: String = "",
    /** Optional one-time token required to bootstrap the owner account. */
    val bootstrapToken: String = "",
    val marketProvider: String = "REPLAY",
    val defaultTimezone: String = "America/Halifax",
    val defaultBaseCurrency: String = "USD",
    val replay: Replay = Replay(),
    val auth: Auth = Auth(),
    val scheduler: Scheduler = Scheduler(),
    val backup: Backup = Backup(),
    val push: Push = Push(),
) {
    data class Replay(
        /** Directory with replay fixtures; "classpath:replay" uses the bundled synthetic set. */
        val fixturesDir: String = "classpath:replay",
        /** When true, the replay clock advances automatically by [autoAdvanceStep] every [autoAdvanceInterval]. */
        val autoAdvance: Boolean = false,
        val autoAdvanceStep: Duration = Duration.ofMinutes(1),
        val autoAdvanceInterval: Duration = Duration.ofSeconds(5),
    )

    data class Auth(
        val sessionLifetime: Duration = Duration.ofDays(30),
        val recentAuthWindow: Duration = Duration.ofMinutes(5),
        val maxFailedLogins: Int = 5,
        val lockoutDuration: Duration = Duration.ofMinutes(15),
        val actionTokenLifetime: Duration = Duration.ofMinutes(10),
    )

    data class Scheduler(
        val enabled: Boolean = true,
        val evaluationInterval: Duration = Duration.ofSeconds(20),
        val executionInterval: Duration = Duration.ofSeconds(2),
        val marketDataInterval: Duration = Duration.ofSeconds(15),
        val reconciliationInterval: Duration = Duration.ofMinutes(5),
    )

    data class Backup(
        val directory: String = "/var/lib/strategyforge/backups",
        val pgDumpPath: String = "pg_dump",
        val pgRestorePath: String = "pg_restore",
    )

    data class Push(
        /** Path to Firebase service-account JSON; empty disables FCM push (inbox stays authoritative). */
        val fcmCredentialsPath: String = "",
        val fcmEndpoint: String = "https://fcm.googleapis.com",
    )
}
