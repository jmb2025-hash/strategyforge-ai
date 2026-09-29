package app.strategyforge.android.platform

import android.content.Context
import android.net.Uri
import java.io.File
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** Where the engine keeps backups (app-private storage), and copying files in and out of it. */
object BackupFiles {
    private val NAME = Regex("^strategyforge-[0-9]{8}-[0-9]{6}-[a-z0-9]{1,8}\\.sfbk$")
    private const val MAX_BYTES = 512L * 1024 * 1024

    fun dir(context: Context): File = File(context.filesDir, "backups")

    fun file(
        context: Context,
        name: String,
    ): File {
        require(NAME.matches(name)) { "Invalid backup name" }
        return File(dir(context), name).also { require(it.isFile) { "Backup $name was not found" } }
    }

    /** Copies a picked file into backup storage under a fresh name and returns that name. */
    fun import(
        context: Context,
        source: Uri,
    ): String {
        val name = "strategyforge-${DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(ZonedDateTime.now(ZoneOffset.UTC))}-import.sfbk"
        val target = File(dir(context).apply { mkdirs() }, name)
        val input = context.contentResolver.openInputStream(source) ?: error("Could not open the chosen file")
        input.use { i ->
            target.outputStream().use { o ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var total = 0L
                while (true) {
                    val n = i.read(buffer)
                    if (n < 0) break
                    total += n
                    if (total > MAX_BYTES) {
                        target.delete()
                        error("The file is too large to be a StrategyForge backup")
                    }
                    o.write(buffer, 0, n)
                }
            }
        }
        return name
    }
}
