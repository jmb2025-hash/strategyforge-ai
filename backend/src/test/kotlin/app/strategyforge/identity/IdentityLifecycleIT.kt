package app.strategyforge.identity

import app.strategyforge.support.FreshDatabaseTest
import app.strategyforge.support.TestHttp
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import java.time.Instant

/** Destructive identity flows on a private database: bootstrap, lockout, TOTP, recovery, password change. */
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class IdentityLifecycleIT : FreshDatabaseTest() {
    private val user = "jason"

    private fun http() = TestHttp(baseUrl)

    private fun login(
        pass: String = password,
        totp: String? = null,
    ) = http().post(
        "/v1/auth/login",
        buildMap {
            put("username", user)
            put("password", pass)
            put("deviceName", "phone")
            totp?.let { put("totpCode", it) }
        },
    )

    @Test
    @Order(1)
    fun `FR-001 MS-01 first-run bootstrap creates exactly one owner and disables registration`() {
        val h = http()
        assertThat(h.get("/v1/bootstrap").json["bootstrapped"].asBoolean()).isFalse()
        assertThat(h.post("/v1/bootstrap", mapOf("username" to user, "password" to "short", "deviceName" to "phone")).json["code"].asText()).isEqualTo("weak-password")
        val r = h.post("/v1/bootstrap", mapOf("username" to user, "password" to password, "deviceName" to "phone", "timezone" to "America/Halifax"))
        assertThat(r.status).isEqualTo(201)
        assertThat(r.json["recoveryCodes"].size()).isEqualTo(10)
        assertThat(r.json["session"]["accessToken"].asText()).hasSizeGreaterThan(30)
        recovery = r.json["recoveryCodes"].map { it.asText() }

        val second = h.post("/v1/bootstrap", mapOf("username" to "intruder", "password" to "another-long-passphrase", "deviceName" to "x"))
        assertThat(second.status).isEqualTo(409)
        assertThat(second.json["code"].asText()).isEqualTo("registration-disabled")
        assertThat(jdbc.sql("select count(*) from owners").query(Int::class.java).single()).isEqualTo(1)
        // The database itself refuses a second owner row.
        val direct = runCatching { jdbc.sql("insert into owners(singleton, id, username, password_hash, created_at, password_changed_at) values (false, gen_random_uuid(), 'x', 'x', now(), now())").update() }
        assertThat(direct.isFailure).isTrue()
        assertThat(jdbc.sql("select count(*) from audit_events where action='BOOTSTRAP' and outcome='DENIED'").query(Int::class.java).single()).isGreaterThanOrEqualTo(1)
    }

    @Test
    @Order(2)
    fun `FR-002 password is stored with Argon2id and login issues revocable sessions`() {
        val hash = jdbc.sql("select password_hash from owners").query(String::class.java).single()
        assertThat(hash).startsWith("\$argon2id\$").doesNotContain(password)
        val tokenRow = jdbc.sql("select token_hash from sessions limit 1").query(String::class.java).single()
        assertThat(tokenRow).hasSize(64)
        val r = login()
        assertThat(r.status).isEqualTo(200)
        val h = http().also { it.token = r.json["accessToken"].asText() }
        assertThat(h.get("/v1/auth/me").status).isEqualTo(200)
        val sessionId = r.json["sessionId"].asText()
        assertThat(h.delete("/v1/sessions/$sessionId").status).isEqualTo(204)
        assertThat(h.get("/v1/auth/me").status).isEqualTo(401)
    }

    @Test
    @Order(3)
    fun `FR-002 repeated failures lock the account and are audited`() {
        repeat(5) { assertThat(login("wrong-password-xxxxxx").status).isEqualTo(401) }
        val locked = login()
        assertThat(locked.status).isEqualTo(423)
        assertThat(locked.json["code"].asText()).isEqualTo("account-locked")
        assertThat(jdbc.sql("select count(*) from audit_events where action='LOGIN' and outcome='FAILURE'").query(Int::class.java).single()).isGreaterThanOrEqualTo(5)
        jdbc.sql("update owners set locked_until = null, failed_logins = 0").update()
        assertThat(login().status).isEqualTo(200)
    }

    @Test
    @Order(4)
    fun `FR-002 optional TOTP requires recent auth to enable, then protects login and rejects replay`() {
        val h = http().also { it.token = login().json["accessToken"].asText() }
        jdbc.sql("update sessions set last_authenticated_at = now() - interval '1 hour'").update()
        val stale = h.post("/v1/auth/totp/setup")
        assertThat(stale.status).isEqualTo(403)
        assertThat(stale.json["code"].asText()).isEqualTo("recent-authentication-required")
        assertThat(h.post("/v1/auth/reauthenticate", mapOf("password" to password)).status).isEqualTo(200)
        val setup = h.post("/v1/auth/totp/setup")
        assertThat(setup.status).isEqualTo(200)
        val secret = setup.json["secret"].asText()
        assertThat(setup.json["otpauthUri"].asText()).startsWith("otpauth://totp/")
        val step = Totp.step(Instant.now())
        assertThat(h.post("/v1/auth/totp/confirm", mapOf("code" to Totp.code(secret, step))).status).isEqualTo(204)
        val enc = jdbc.sql("select totp_secret_enc from owners").query(ByteArray::class.java).single()
        assertThat(String(enc, Charsets.ISO_8859_1)).doesNotContain(secret)

        assertThat(login().json["code"].asText()).isEqualTo("totp-required")
        assertThat(login(totp = "000000").status).isEqualTo(401)
        jdbc.sql("update owners set failed_logins = 0").update()
        // The confirmation code consumed `step`; the same code is a replay and is rejected.
        assertThat(login(totp = Totp.code(secret, step)).status).isEqualTo(401)
        jdbc.sql("update owners set failed_logins = 0").update()
        assertThat(login(totp = Totp.code(secret, step + 1)).status).isEqualTo(200)
        totpSecret = secret
    }

    @Test
    @Order(5)
    fun `FR-002 offline recovery code resets password, revokes sessions, disables TOTP and is single use`() {
        val code = recovery.first()
        val newPass = "brand-new-owner-passphrase"
        val r = http().post("/v1/auth/recover", mapOf("username" to user, "recoveryCode" to code, "newPassword" to newPass, "deviceName" to "new-phone"))
        assertThat(r.status).isEqualTo(200)
        assertThat(r.json["remainingRecoveryCodes"].asInt()).isEqualTo(9)
        assertThat(jdbc.sql("select count(*) from sessions where revoked_at is null").query(Int::class.java).single()).isEqualTo(1)
        assertThat(jdbc.sql("select totp_enabled from owners").query(Boolean::class.java).single()).isFalse()
        password = newPass
        val reuse = http().post("/v1/auth/recover", mapOf("username" to user, "recoveryCode" to code, "newPassword" to "yet-another-passphrase-9", "deviceName" to "x"))
        assertThat(reuse.status).isEqualTo(401)
        assertThat(login().status).isEqualTo(200)
    }

    @Test
    @Order(6)
    fun `FR-002 recovery code regeneration requires recent authentication`() {
        val h = http().also { it.token = login().json["accessToken"].asText() }
        jdbc.sql("update sessions set last_authenticated_at = now() - interval '1 hour'").update()
        assertThat(h.post("/v1/auth/recovery-codes/regenerate").status).isEqualTo(403)
        h.post("/v1/auth/reauthenticate", mapOf("password" to password))
        val r = h.post("/v1/auth/recovery-codes/regenerate")
        assertThat(r.status).isEqualTo(200)
        assertThat(r.json["recoveryCodes"].size()).isEqualTo(10)
        assertThat(jdbc.sql("select count(*) from recovery_codes where used_at is null").query(Int::class.java).single()).isEqualTo(10)
    }

    companion object {
        var recovery: List<String> = emptyList()
        var totpSecret: String? = null
        var password = "a-long-owner-passphrase-1"
    }
}
