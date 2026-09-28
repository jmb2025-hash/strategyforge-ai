package app.strategyforge.operations

import app.strategyforge.operations.backup.BackupArchive
import app.strategyforge.operations.backup.BackupCommand
import app.strategyforge.support.FreshDatabaseTest
import app.strategyforge.support.Postgres
import app.strategyforge.support.Replay
import app.strategyforge.support.TestOwner
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager

/** FR-112, NFR-008, MS-21, RG-08: encrypted backup, verification and clean restore with reconciliation. */
class BackupRestoreIT : FreshDatabaseTest() {
    private val testKey = "dGVzdC1tYXN0ZXIta2V5LTMyLWJ5dGVzLWxvbmctISE="

    private fun command(
        vararg args: String,
        url: String = "",
        key: String = testKey,
    ): Pair<Int, String> {
        val buf = ByteArrayOutputStream()
        val env = mapOf("DATABASE_URL" to url, "DATABASE_USER" to Postgres.container.username, "DATABASE_PASSWORD" to Postgres.container.password, "MASTER_ENCRYPTION_KEY" to key)
        val code = BackupCommand.run(args.toList(), env, PrintStream(buf, true, Charsets.UTF_8))
        return code to buf.toString(Charsets.UTF_8)
    }

    @Test
    fun `MS-21 RG-08 backup is encrypted, verifiable, and restores into a clean database that reconciles`() {
        val h = TestOwner.client(baseUrl)
        val p = Replay.portfolio(h, costModel = mapOf("commissionPerOrder" to "1.00"))
        assertThat(Replay.order(h, p, "BTC-USD", "BUY", "0.02").status).isEqualTo(201)
        assertThat(Replay.order(h, p, "ETH-USD", "BUY", "0.5").status).isEqualTo(201)
        Replay.advance(h, 2)
        assertThat(Replay.order(h, p, "BTC-USD", "SELL", "0.01").status).isEqualTo(201)
        Replay.advance(h, 2)

        val created = h.post("/v1/backups", null)
        assertThat(created.status).`as`(created.toString()).isEqualTo(201)
        val name = created.json["file"]["name"].asText()
        assertThat(name).endsWith(".sfbk")
        val fingerprint =
            created.json["fingerprint"]
                .fields()
                .asSequence()
                .associate { it.key to it.value.asText() }
        assertThat(fingerprint["ledger.net"]).isEqualTo("0")
        assertThat(fingerprint.keys).anyMatch { it.startsWith("cash.") }.anyMatch { it.startsWith("position.") }
        assertThat(h.get("/v1/backups").json["items"].map { it["name"].asText() }).contains(name)

        val file = Path.of("build/test-backups").resolve(name)
        // Encrypted at rest: no table data or identifiers are visible in the file.
        val raw = Files.readAllBytes(file)
        assertThat(String(raw, Charsets.ISO_8859_1))
            .startsWith("SFBK")
            .doesNotContain("BTC-USD")
            .doesNotContain("ledger_entries")
            .doesNotContain("manifest")

        val verified = h.post("/v1/backups/$name/verify", null).json
        assertThat(verified["valid"].asBoolean()).isTrue()
        assertThat(command("verify-backup", file.toString()).first).isZero()

        // Restore into a new, empty database.
        val target = Postgres.freshDatabase("sf_restore_" + System.nanoTime())
        val (code, out) = command("restore", file.toString(), url = target)
        assertThat(code).`as`(out).isZero()
        assertThat(out).contains("reconcile with the manifest")
        DriverManager.getConnection(target, Postgres.container.username, Postgres.container.password).use { c ->
            assertThat(BackupArchive.fingerprint(c)).isEqualTo(fingerprint)
            c.createStatement().use { s ->
                s.executeQuery("select count(*) from paper_orders where portfolio_id = '$p'").use { rs ->
                    rs.next()
                    assertThat(rs.getInt(1)).isEqualTo(3)
                }
                // Sequences continue after the restored rows (new appends do not collide).
                s.executeQuery("insert into system_health_events(occurred_at, component, status, detail) values (now(), 'restore-test', 'OK', 'x') returning id").use { rs ->
                    rs.next()
                    assertThat(rs.getLong(1)).isGreaterThan(0)
                }
                // Append-only protections are in force on the restored database.
                assertThat(runCatching { s.execute("delete from audit_events") }.isFailure).isTrue()
            }
        }

        // Refuses a non-empty target.
        val again = command("restore", file.toString(), url = target)
        assertThat(again.first).isEqualTo(1)
        assertThat(again.second).contains("not empty")

        // Tampering, truncation and a wrong key are all rejected before any data is used.
        val tampered = Files.createTempFile("sf-tampered", ".sfbk")
        Files.write(tampered, raw.copyOf().also { it[it.size / 2] = (it[it.size / 2].toInt() xor 0x01).toByte() })
        assertThat(command("verify-backup", tampered.toString()).second).contains("failed authentication")
        Files.write(tampered, raw.copyOf(raw.size - 20))
        assertThat(command("verify-backup", tampered.toString()).second).containsAnyOf("truncated", "failed authentication")
        val wrongKey = "d3Jvbmcta2V5LXdyb25nLWtleS0zMi1ieXRlcy0hISE="
        assertThat(command("verify-backup", file.toString(), key = wrongKey).second).contains("failed authentication")
        Files.deleteIfExists(tampered)

        val restoreTarget2 = Postgres.freshDatabase("sf_restore_bad_" + System.nanoTime())
        assertThat(command("restore", file.toString(), url = restoreTarget2, key = wrongKey).first).isEqualTo(1)
        assertThat(command("restore").first).isEqualTo(2)

        // FR-111 diagnostics include backup age, audit chain, push queue and scheduler state.
        val diag = h.get("/v1/diagnostics").json["components"].associate { it["component"].asText() to it }
        assertThat(diag.keys).`as`(diag.toString()).contains("backup", "audit-chain", "push-queue")
        assertThat(diag["backup"]!!["status"].asText()).isEqualTo("OK")
        assertThat(diag["audit-chain"]!!["status"].asText()).isEqualTo("OK")
        assertThat(diag["push-queue"]).`as`(diag.toString()).isNotNull()
        assertThat(diag["push-queue"]!!["data"]?.get("queueDepth")?.asInt()).`as`(diag["push-queue"].toString()).isZero()
        assertThat(diag.keys).contains("strategy-scheduler", "recent-errors")
        val audited = jdbc.sql("select count(*) from audit_events where action in ('BACKUP_CREATED', 'BACKUP_VERIFIED')").query(Int::class.java).single()
        assertThat(audited).isEqualTo(2)
    }
}
