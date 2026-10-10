package app.strategyforge.engine.operations

import app.strategyforge.engine.Engine
import app.strategyforge.engine.common.Hashing
import app.strategyforge.engine.db.JdbcSqlBackend
import app.strategyforge.engine.support.MutableClock
import app.strategyforge.engine.support.TestEngine
import app.strategyforge.engine.support.portfolio
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.sql.DriverManager
import java.time.Instant
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** D-079 backups carry the TSX plan runs and each slot's portfolio; backups made before them still restore. */
class BackupTablesTest {
    @TempDir lateinit var dir: File

    private val e =
        Engine(
            JdbcSqlBackend(DriverManager.getConnection("jdbc:sqlite::memory:")),
            MutableClock(Instant.parse("2026-10-10T12:00:00Z")),
            fixtureReader = { rel -> TestEngine::class.java.getResource("/replay/$rel")!!.readText() },
            backupDir = { dir },
        )

    private fun tsxRuns() = e.db.sql("select count(*) from tsx_runs").long()

    private fun addRun() {
        e.db
            .sql(
                """
                insert into tsx_runs(id, plan_id, slot, mode, drip, starting_cash, cash, holdings, sleeve_state, status, created_at)
                values (:id, 'tsx-high-yield-trend', 1, 'NOTIFY', 1, '10000', '10000', '[]', '{}', 'ACTIVE', 0)
                """.trimIndent(),
            ).param("id", java.util.UUID.randomUUID())
            .update()
    }

    @Test
    fun `TSX runs and slot portfolios are backed up and restored`() {
        val p = e.portfolio("Crypto slot 1")
        e.slots.linkPortfolios()
        addRun()
        val backup = e.backups.create()
        e.db.sql("delete from tsx_runs").update()
        e.auth.confirmed()
        e.backups.restore(backup.file.name)
        assertThat(tsxRuns()).isEqualTo(1)
        assertThat(e.slots.portfolioSlots().keys).containsExactly(p)
    }

    @Test
    fun `a backup from before these tables restores and leaves current TSX runs alone`() {
        e.portfolio("Crypto slot 1")
        e.slots.linkPortfolios()
        val name =
            e.backups
                .create()
                .file.name
        // Rewrite it as 1.23 made it: without the later tables.
        val f = File(dir, name)
        val doc =
            kotlinx.serialization.json.Json
                .parseToJsonElement(GZIPInputStream(f.inputStream()).use { String(it.readBytes()) })
                .jsonObject
        val tables = JsonObject(doc.getValue("tables").jsonObject.filterKeys { it !in BackupService.LATER_TABLES })
        val old = JsonObject(doc + mapOf("tables" to tables, "sha256" to JsonPrimitive(Hashing.sha256Hex(tables.toString())), "schemaVersion" to JsonPrimitive("engine-10")))
        GZIPOutputStream(f.outputStream()).use { it.write(old.toString().toByteArray()) }
        addRun()
        e.auth.confirmed()
        e.backups.restore(name)
        assertThat(tsxRuns()).isEqualTo(1)
        // Slot links are rebuilt from the restored portfolios at the next start.
        assertThat(e.slots.portfolioSlots()).isEmpty()
        assertThat(e.slots.linkPortfolios()).isEqualTo(1)
    }
}
