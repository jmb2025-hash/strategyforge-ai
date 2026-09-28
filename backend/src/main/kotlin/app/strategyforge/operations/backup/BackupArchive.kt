package app.strategyforge.operations.backup

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.postgresql.PGConnection
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.security.SecureRandom
import java.sql.Connection
import java.time.Instant
import java.util.HexFormat
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

data class TableDump(
    val name: String,
    val rows: Long,
    val sha256: String,
)

/**
 * Describes a backup. [fingerprint] holds the consistency totals (ledger net, cash and position
 * balances, order/recommendation states, audit head) captured in the same snapshot as the
 * data; restore recomputes them and fails unless they match exactly (NFR-008).
 */
data class BackupManifest(
    val formatVersion: Int,
    val application: String,
    val createdAt: Instant,
    val schemaVersion: String,
    val tables: List<TableDump>,
    val fingerprint: Map<String, String>,
)

class BackupException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/**
 * Encrypted logical backup of the StrategyForge schema (D-007). Pure JDBC and Flyway so the same
 * code serves the running service and the offline restore command, which must not start the
 * application (startup hooks would write to the target database).
 *
 * Container: `SFBK` | version | 8-byte nonce prefix, then AES-256-GCM chunks. Each chunk is
 * authenticated with its index and a final-chunk flag, so truncation, reordering and tampering
 * are all detected before any row is loaded.
 */
object BackupArchive {
    const val FORMAT_VERSION = 1
    private val MAGIC = "SFBK".toByteArray(Charsets.US_ASCII)
    private const val CONTAINER_VERSION: Byte = 1
    private const val CHUNK = 1 shl 20
    private const val FINAL_FLAG = 1 shl 31
    private const val TAG_BITS = 128
    private const val MANIFEST = "manifest.json"
    private const val EXCLUDED = "flyway_schema_history"

    val mapper: ObjectMapper =
        jacksonObjectMapper().registerModule(JavaTimeModule()).disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)

    // ---------------------------------------------------------------- backup

    /**
     * Dumps every application table from one REPEATABLE READ snapshot into [target] (encrypted).
     * The connection's transaction settings are changed; callers pass a dedicated connection.
     */
    fun create(
        conn: Connection,
        target: Path,
        key: ByteArray,
        createdAt: Instant,
    ): BackupManifest {
        val plain = tempFile("sf-backup")
        try {
            conn.autoCommit = false
            conn.transactionIsolation = Connection.TRANSACTION_REPEATABLE_READ
            conn.isReadOnly = true
            val manifest =
                try {
                    val copy = conn.unwrap(PGConnection::class.java).copyAPI
                    val tables = tables(conn)
                    val dumps = mutableListOf<TableDump>()
                    ZipOutputStream(Files.newOutputStream(plain)).use { zip ->
                        for (t in tables) {
                            zip.putNextEntry(ZipEntry("tables/$t.csv"))
                            val counting = HashingOutputStream(zip)
                            val rows = copy.copyOut("copy ${q(t)} to stdout with (format csv, header true)", counting)
                            dumps += TableDump(t, rows, counting.hex())
                            zip.closeEntry()
                        }
                        val m = BackupManifest(FORMAT_VERSION, "strategyforge", createdAt, schemaVersion(conn), dumps, fingerprint(conn))
                        zip.putNextEntry(ZipEntry(MANIFEST))
                        zip.write(mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(m))
                        zip.closeEntry()
                        m
                    }
                } finally {
                    conn.rollback()
                }
            val partial = target.resolveSibling(target.fileName.toString() + ".partial")
            Files.newInputStream(plain).use { input -> Files.newOutputStream(partial).use { out -> encrypt(input, out, key) } }
            Files.move(partial, target, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
            return manifest
        } finally {
            Files.deleteIfExists(plain)
        }
    }

    // ---------------------------------------------------------------- verify / restore

    /** Decrypts and checks every table hash without touching a database. */
    fun verify(
        source: Path,
        key: ByteArray,
    ): BackupManifest =
        withPlainArchive(source, key) { zip, manifest ->
            for (t in manifest.tables) {
                val entry = zip.getEntry("tables/${t.name}.csv") ?: throw BackupException("Backup is missing table ${t.name}")
                val h = HashingOutputStream(OutputStream.nullOutputStream())
                zip.getInputStream(entry).use { it.transferTo(h) }
                if (h.hex() != t.sha256) throw BackupException("Table ${t.name} does not match its manifest hash")
            }
            manifest
        }

    data class RestoreReport(
        val manifest: BackupManifest,
        val restoredSchemaVersion: String,
        val finalSchemaVersion: String,
        val rowsLoaded: Long,
    )

    /**
     * Restores into an EMPTY database: migrates to the backup's schema version, loads every
     * table in foreign-key order in one transaction, resets sequences, verifies the fingerprint
     * against the manifest and only then upgrades to the latest schema.
     */
    fun restore(
        source: Path,
        key: ByteArray,
        url: String,
        user: String,
        password: String,
    ): RestoreReport =
        withPlainArchive(source, key) { zip, manifest ->
            java.sql.DriverManager.getConnection(url, user, password).use { conn ->
                val existing = tables(conn, includeFlyway = true)
                if (existing.isNotEmpty()) throw BackupException("Target database is not empty (${existing.size} tables); restore only into a new, empty database")
            }
            val flyway = { target: String? ->
                org.flywaydb.core.Flyway
                    .configure()
                    .dataSource(url, user, password)
                    .locations("classpath:db/migration")
                    .apply { if (target != null) target(target) }
                    .load()
            }
            flyway(manifest.schemaVersion).migrate()
            var loaded = 0L
            java.sql.DriverManager.getConnection(url, user, password).use { conn ->
                val restoredVersion = schemaVersion(conn)
                if (restoredVersion != manifest.schemaVersion) {
                    throw BackupException("Backup schema ${manifest.schemaVersion} is newer than this build supports ($restoredVersion)")
                }
                conn.autoCommit = false
                try {
                    val copy = conn.unwrap(PGConnection::class.java).copyAPI
                    val present = tables(conn).toSet()
                    val wanted = manifest.tables.associateBy { it.name }
                    if (present != wanted.keys) throw BackupException("Schema tables differ from backup: missing ${wanted.keys - present}, extra ${present - wanted.keys}")
                    // As pg_dump does: foreign keys are dropped for the load (the schema has FK cycles,
                    // e.g. strategies <-> strategy_versions) and re-added afterwards, which re-validates
                    // every row. DDL is transactional, so a failure leaves the database untouched.
                    val foreignKeys = foreignKeys(conn)
                    val order = present.sorted()
                    conn.createStatement().use { s ->
                        foreignKeys.forEach { (table, name, _) -> s.execute("alter table ${q(table)} drop constraint ${q(name)}") }
                        // Migrations may seed singleton rows; the backup's rows replace them.
                        s.execute("set local strategyforge.allow_purge = 'on'")
                        order.forEach { s.executeUpdate("delete from ${q(it)}") }
                    }
                    for (t in order) {
                        val dump = wanted.getValue(t)
                        val entry = zip.getEntry("tables/$t.csv") ?: throw BackupException("Backup is missing table $t")
                        val bytes = zip.getInputStream(entry).use { it.readAllBytes() }
                        if (sha256(bytes) != dump.sha256) throw BackupException("Table $t does not match its manifest hash")
                        val header = bytes.inputStream().bufferedReader().readLine() ?: throw BackupException("Table $t has no header")
                        val columns = header.split(',').joinToString(",") { q(it.trim('"')) }
                        val rows = copy.copyIn("copy ${q(t)} ($columns) from stdin with (format csv, header match)", bytes.inputStream())
                        if (rows != dump.rows) throw BackupException("Table $t loaded $rows rows, manifest says ${dump.rows}")
                        loaded += rows
                    }
                    conn.createStatement().use { s ->
                        // Runs the deferred double-entry checks now (every journal must balance).
                        s.execute("set constraints all immediate")
                        foreignKeys.forEach { (table, name, def) -> s.execute("alter table ${q(table)} add constraint ${q(name)} $def") }
                    }
                    resetSequences(conn)
                    conn.commit()
                } catch (e: java.sql.SQLException) {
                    conn.rollback()
                    throw BackupException("Restore failed while loading data: ${e.message}", e)
                }
                conn.autoCommit = true
                val actual = fingerprint(conn)
                if (actual != manifest.fingerprint) {
                    val diff = (actual.keys + manifest.fingerprint.keys).filter { actual[it] != manifest.fingerprint[it] }
                    throw BackupException("Restored data does not reconcile with the backup manifest: $diff")
                }
            }
            flyway(null).migrate()
            val finalVersion =
                java.sql.DriverManager
                    .getConnection(url, user, password)
                    .use { schemaVersion(it) }
            RestoreReport(manifest, manifest.schemaVersion, finalVersion, loaded)
        }

    private fun <T> withPlainArchive(
        source: Path,
        key: ByteArray,
        block: (ZipFile, BackupManifest) -> T,
    ): T {
        val plain = tempFile("sf-restore")
        try {
            Files.newInputStream(source).use { input -> Files.newOutputStream(plain).use { out -> decrypt(input, out, key) } }
            ZipFile(plain.toFile()).use { zip ->
                val entry = zip.getEntry(MANIFEST) ?: throw BackupException("Backup has no manifest")
                val manifest = zip.getInputStream(entry).use { mapper.readValue<BackupManifest>(it) }
                if (manifest.formatVersion != FORMAT_VERSION) throw BackupException("Unsupported backup format ${manifest.formatVersion}")
                return block(zip, manifest)
            }
        } finally {
            Files.deleteIfExists(plain)
        }
    }

    // ---------------------------------------------------------------- consistency fingerprint

    /** Consistency totals compared before and after restore (NFR-008). Deterministic and canonical. */
    fun fingerprint(conn: Connection): Map<String, String> {
        val out = sortedMapOf<String, String>()

        fun rows(
            sql: String,
            f: (java.sql.ResultSet) -> Pair<String, String>,
        ) = conn.createStatement().use { s -> s.executeQuery(sql).use { rs -> while (rs.next()) f(rs).let { out[it.first] = it.second } } }

        fun dec(v: BigDecimal?): String = (v ?: BigDecimal.ZERO).stripTrailingZeros().toPlainString()
        rows("select coalesce(sum(amount), 0) from ledger_entries") { "ledger.net" to dec(it.getBigDecimal(1)) }
        rows("select count(*) from ledger_entries") { "ledger.entries" to it.getLong(1).toString() }
        rows("select portfolio_id, sum(amount) from ledger_entries where account = 'CASH' group by 1") { "cash.${it.getString(1)}" to dec(it.getBigDecimal(2)) }
        rows("select portfolio_id, instrument_id, sum(quantity), sum(amount) from ledger_entries where account = 'POSITION' group by 1, 2") {
            "position.${it.getString(1)}.${it.getString(2)}" to "${dec(it.getBigDecimal(3))}@${dec(it.getBigDecimal(4))}"
        }
        rows("select status, count(*) from paper_orders group by 1") { "orders.${it.getString(1)}" to it.getLong(2).toString() }
        rows("select status, count(*) from recommendations group by 1") { "recommendations.${it.getString(1)}" to it.getLong(2).toString() }
        rows("select count(*), coalesce(max(id), 0) from audit_events") { "audit.count" to "${it.getLong(1)}/${it.getLong(2)}" }
        rows("select coalesce((select hash from audit_events order by id desc limit 1), 'EMPTY')") { "audit.head" to it.getString(1) }
        return out
    }

    // ---------------------------------------------------------------- schema helpers

    private fun tables(
        conn: Connection,
        includeFlyway: Boolean = false,
    ): List<String> =
        conn
            .createStatement()
            .use { s ->
                s.executeQuery("select table_name from information_schema.tables where table_schema = current_schema() and table_type = 'BASE TABLE' order by 1").use { rs ->
                    buildList { while (rs.next()) add(rs.getString(1)) }
                }
            }.filter { includeFlyway || it != EXCLUDED }

    fun schemaVersion(conn: Connection): String =
        conn.createStatement().use { s ->
            s.executeQuery("select version from flyway_schema_history where success and version is not null order by installed_rank desc limit 1").use { rs ->
                if (rs.next()) rs.getString(1) else throw BackupException("Database has no Flyway schema history")
            }
        }

    /** (table, constraint, definition) for every foreign key in the application schema. */
    private fun foreignKeys(conn: Connection): List<Triple<String, String, String>> =
        conn.createStatement().use { s ->
            s
                .executeQuery(
                    """
                    select cl.relname, c.conname, pg_get_constraintdef(c.oid)
                    from pg_constraint c join pg_class cl on cl.oid = c.conrelid join pg_namespace n on n.oid = cl.relnamespace
                    where c.contype = 'f' and n.nspname = current_schema() order by 1, 2
                    """.trimIndent(),
                ).use { rs -> buildList { while (rs.next()) add(Triple(rs.getString(1), rs.getString(2), rs.getString(3))) } }
        }

    private fun resetSequences(conn: Connection) {
        val targets =
            conn.createStatement().use { s ->
                s
                    .executeQuery(
                        """
                        select table_name, column_name, pg_get_serial_sequence(quote_ident(table_name), column_name) seq
                        from information_schema.columns where table_schema = current_schema()
                        """.trimIndent(),
                    ).use { rs -> buildList { while (rs.next()) rs.getString(3)?.let { add(Triple(rs.getString(1), rs.getString(2), it)) } } }
            }
        conn.createStatement().use { s ->
            for ((table, column, seq) in targets) {
                val max = "(select max(${q(column)}) from ${q(table)})"
                s.execute("select setval('$seq', coalesce($max, 1), $max is not null)")
            }
        }
    }

    private fun q(identifier: String): String {
        require(identifier.matches(Regex("[a-z_][a-z0-9_]*"))) { "Unexpected identifier" }
        return "\"$identifier\""
    }

    // ---------------------------------------------------------------- encryption container

    fun encrypt(
        input: InputStream,
        output: OutputStream,
        key: ByteArray,
    ) {
        val prefix = ByteArray(8).also(SecureRandom()::nextBytes)
        val header = MAGIC + byteArrayOf(CONTAINER_VERSION) + prefix
        val out = DataOutputStream(output)
        out.write(header)
        val buf = ByteArray(CHUNK)
        var index = 0
        var current = input.readNBytes(buf, 0, CHUNK)
        while (true) {
            val next = ByteArray(CHUNK)
            val nextLen = if (current == CHUNK) input.readNBytes(next, 0, CHUNK) else 0
            val last = nextLen == 0
            val ct = cipher(Cipher.ENCRYPT_MODE, key, header, prefix, index, last).doFinal(buf, 0, current)
            out.writeInt(ct.size or (if (last) FINAL_FLAG else 0))
            out.write(ct)
            if (last) break
            next.copyInto(buf)
            current = nextLen
            index++
        }
        out.flush()
    }

    fun decrypt(
        input: InputStream,
        output: OutputStream,
        key: ByteArray,
    ) {
        val din = DataInputStream(input)
        val header = ByteArray(MAGIC.size + 1 + 8)
        try {
            din.readFully(header)
        } catch (e: EOFException) {
            throw BackupException("Not a StrategyForge backup", e)
        }
        if (!header.copyOfRange(0, MAGIC.size).contentEquals(MAGIC) || header[MAGIC.size] != CONTAINER_VERSION) throw BackupException("Not a StrategyForge backup")
        val prefix = header.copyOfRange(MAGIC.size + 1, header.size)
        var index = 0
        while (true) {
            val word =
                try {
                    din.readInt()
                } catch (e: EOFException) {
                    throw BackupException("Backup is truncated", e)
                }
            val last = word and FINAL_FLAG != 0
            val len = word and FINAL_FLAG.inv()
            if (len > CHUNK + TAG_BITS / 8) throw BackupException("Backup chunk is malformed")
            val ct = ByteArray(len)
            try {
                din.readFully(ct)
            } catch (e: EOFException) {
                throw BackupException("Backup is truncated", e)
            }
            val plain =
                try {
                    cipher(Cipher.DECRYPT_MODE, key, header, prefix, index, last).doFinal(ct)
                } catch (e: AEADBadTagException) {
                    throw BackupException("Backup failed authentication: wrong MASTER_ENCRYPTION_KEY or modified file", e)
                }
            output.write(plain)
            if (last) break
            index++
        }
        if (din.read() != -1) throw BackupException("Unexpected data after the final backup chunk")
    }

    private fun cipher(
        mode: Int,
        key: ByteArray,
        header: ByteArray,
        prefix: ByteArray,
        index: Int,
        last: Boolean,
    ): Cipher {
        val nonce =
            ByteArrayOutputStream()
                .also {
                    DataOutputStream(it).apply {
                        write(prefix)
                        writeInt(index)
                    }
                }.toByteArray()
        val aad =
            ByteArrayOutputStream()
                .also {
                    DataOutputStream(it).apply {
                        write(header)
                        writeInt(index)
                        writeBoolean(last)
                    }
                }.toByteArray()
        return Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
            updateAAD(aad)
        }
    }

    private fun tempFile(prefix: String): Path =
        runCatching { Files.createTempFile(prefix, ".zip", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))) }
            .getOrElse { Files.createTempFile(prefix, ".zip") }

    private fun sha256(bytes: ByteArray): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

    /** Pass-through stream that hashes what it forwards and never closes the delegate. */
    private class HashingOutputStream(
        private val delegate: OutputStream,
    ) : OutputStream() {
        private val digest = MessageDigest.getInstance("SHA-256")

        override fun write(b: Int) {
            digest.update(b.toByte())
            delegate.write(b)
        }

        override fun write(
            b: ByteArray,
            off: Int,
            len: Int,
        ) {
            digest.update(b, off, len)
            delegate.write(b, off, len)
        }

        fun hex(): String = HexFormat.of().formatHex(digest.digest())
    }
}
