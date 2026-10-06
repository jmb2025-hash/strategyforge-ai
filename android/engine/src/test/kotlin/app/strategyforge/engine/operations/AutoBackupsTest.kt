package app.strategyforge.engine.operations

import app.strategyforge.engine.Engine
import app.strategyforge.engine.EngineScheduler
import app.strategyforge.engine.db.JdbcSqlBackend
import app.strategyforge.engine.support.MutableClock
import app.strategyforge.engine.support.TestEngine
import app.strategyforge.engine.support.portfolio
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.sql.DriverManager
import java.time.Duration
import java.time.Instant

/** D-069 a daily backup is written and copied out of the app, kept to seven, and retried after failures. */
class AutoBackupsTest {
    @TempDir lateinit var appDir: File

    @TempDir lateinit var downloads: File

    private val clock = MutableClock(Instant.parse("2026-10-06T12:00:00Z"))
    private var failExport = false

    private fun engine(export: ((File) -> String)? = { f -> copy(f) }): Engine =
        Engine(
            JdbcSqlBackend(DriverManager.getConnection("jdbc:sqlite::memory:")),
            clock,
            fixtureReader = { rel -> TestEngine::class.java.getResource("/replay/$rel")!!.readText() },
            backupDir = { appDir },
            exportBackup = export,
        )

    private fun copy(f: File): String {
        if (failExport) error("Downloads is not available")
        f.copyTo(File(downloads, f.name), overwrite = true)
        return "Download/StrategyForge/${f.name}"
    }

    private fun autoFiles(dir: File) =
        dir
            .listFiles()
            .orEmpty()
            .map { it.name }
            .filter { it.endsWith(".sfbk") && it.substringAfterLast('-').startsWith("auto") }

    private fun advance(d: Duration) = clock.advanceSeconds(d.seconds)

    @Test
    fun `nothing is backed up before there is anything to keep, then once a day with a copy outside the app`() {
        val e = engine()
        e.autoBackups.runIfDue()
        assertThat(autoFiles(appDir)).isEmpty()

        e.portfolio("Main")
        e.autoBackups.runIfDue()
        assertThat(autoFiles(appDir)).hasSize(1)
        assertThat(autoFiles(downloads)).isEqualTo(autoFiles(appDir))
        val s = e.autoBackups.status()
        assertThat(s.lastLocation).startsWith("Download/StrategyForge/strategyforge-").endsWith(".sfbk")
        assertThat(s.lastError).isNull()
        assertThat(s.nextDueAt).isEqualTo(Instant.parse("2026-10-07T12:00:00Z"))
        assertThat(e.backups.verify(s.lastName!!).valid).isTrue()

        advance(Duration.ofHours(23))
        e.autoBackups.runIfDue()
        assertThat(autoFiles(appDir)).hasSize(1)
        advance(Duration.ofHours(1))
        e.autoBackups.runIfDue()
        assertThat(autoFiles(appDir)).hasSize(2)
    }

    @Test
    fun `seven daily backups are kept and manual backups are never pruned`() {
        val e = engine()
        e.portfolio("Main")
        val manual =
            e.backups
                .create()
                .file.name
        repeat(10) {
            e.autoBackups.runIfDue()
            advance(Duration.ofHours(24))
        }
        assertThat(autoFiles(appDir)).hasSize(AutoBackups.KEEP)
        assertThat(File(appDir, manual)).exists()
    }

    @Test
    fun `a failed copy is recorded and retried an hour later, and turning it off stops it`() {
        val e = engine()
        e.portfolio("Main")
        failExport = true
        e.autoBackups.runIfDue()
        assertThat(e.autoBackups.status().lastError).contains("Downloads is not available")
        assertThat(e.autoBackups.status().lastAt).isNull()
        failExport = false
        advance(Duration.ofMinutes(30))
        e.autoBackups.runIfDue()
        assertThat(autoFiles(downloads)).isEmpty()
        advance(Duration.ofMinutes(31))
        e.autoBackups.runIfDue()
        assertThat(autoFiles(downloads)).hasSize(1)
        assertThat(e.autoBackups.status().lastError).isNull()

        e.autoBackups.enabled = false
        advance(Duration.ofDays(2))
        e.autoBackups.runIfDue()
        assertThat(autoFiles(downloads)).hasSize(1)
        assertThat(e.autoBackups.status().nextDueAt).isNull()
    }

    @Test
    fun `the scheduler runs the daily backup in demo mode too`() {
        val e = engine()
        e.portfolio("Main")
        EngineScheduler(e, clock).tick()
        assertThat(autoFiles(downloads)).hasSize(1)
    }

    @Test
    fun `without a place to copy to, backups are still kept in the app`() {
        val e = engine(export = null)
        e.portfolio("Main")
        e.autoBackups.runIfDue()
        assertThat(autoFiles(appDir)).hasSize(1)
        assertThat(e.autoBackups.status().exportAvailable).isFalse()
        assertThat(e.autoBackups.status().lastLocation).isNull()
    }
}
