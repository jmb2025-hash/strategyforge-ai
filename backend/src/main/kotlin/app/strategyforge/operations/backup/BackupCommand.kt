package app.strategyforge.operations.backup

import app.strategyforge.common.security.SecretCipher
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path

/**
 * Offline command mode (D-007): `restore <file>` and `verify-backup <file>`. Runs without the
 * Spring context so no startup hook writes to the target database. Configuration comes from the
 * same environment variables as the service: DATABASE_URL, DATABASE_USER, DATABASE_PASSWORD and
 * MASTER_ENCRYPTION_KEY.
 */
object BackupCommand {
    val COMMANDS = setOf("restore", "verify-backup")

    /** Returns the process exit code: 0 success, 1 verification or restore failure, 2 usage error. */
    fun run(
        args: List<String>,
        env: Map<String, String>,
        out: PrintStream = System.out,
    ): Int {
        if (args.size != 2 || args[0] !in COMMANDS) {
            out.println("usage: strategyforge restore|verify-backup <backup-file.sfbk>")
            return 2
        }
        val file = Path.of(args[1])
        if (!Files.isRegularFile(file)) {
            out.println("Backup file not found: $file")
            return 2
        }
        val key =
            try {
                val raw = SecretCipher.decodeKey(env["MASTER_ENCRYPTION_KEY"].orEmpty(), "MASTER_ENCRYPTION_KEY")
                require(raw.size == KEY_BYTES) { "MASTER_ENCRYPTION_KEY must decode to 32 bytes" }
                SecretCipher.deriveKey(raw, BackupService.KEY_PURPOSE)
            } catch (e: IllegalArgumentException) {
                out.println(e.message)
                return 2
            }
        return try {
            if (args[0] == "verify-backup") {
                val m = BackupArchive.verify(file, key)
                out.println("OK: backup of schema ${m.schemaVersion} created ${m.createdAt}, ${m.tables.size} tables, ${m.tables.sumOf { it.rows }} rows; all hashes match")
            } else {
                val url = env["DATABASE_URL"] ?: "jdbc:postgresql://localhost:5432/strategyforge"
                val r = BackupArchive.restore(file, key, url, env["DATABASE_USER"] ?: "strategyforge", env["DATABASE_PASSWORD"].orEmpty())
                out.println("OK: restored ${r.rowsLoaded} rows at schema ${r.restoredSchemaVersion}; ledger, positions, orders, recommendations and audit head reconcile with the manifest")
                out.println("Schema upgraded to ${r.finalSchemaVersion}. Start the service normally and open Diagnostics: the audit-chain and reconciliation checks confirm the restored state.")
            }
            0
        } catch (e: BackupException) {
            out.println("FAILED: ${e.message}")
            1
        }
    }

    private const val KEY_BYTES = 32
}
