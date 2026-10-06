package app.strategyforge.android.platform

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/**
 * Copies the daily backup to Downloads/StrategyForge (D-069), where it survives the app being
 * uninstalled. MediaStore needs no storage permission for files the app adds itself. The newest
 * [KEEP] daily copies are kept; files from an earlier install, or saved by hand, are never touched.
 */
object DownloadsBackups {
    const val KEEP = 3
    private const val FOLDER = "StrategyForge"
    private val relative = "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER/"

    /** Copies [file] and returns where it is, for example "Download/StrategyForge/strategyforge-....sfbk". */
    fun export(
        context: Context,
        file: File,
    ): String {
        val resolver = context.contentResolver
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val values =
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
                put(MediaStore.MediaColumns.RELATIVE_PATH, relative)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        val uri = resolver.insert(collection, values) ?: error("Could not create the backup file in Downloads")
        try {
            val out = resolver.openOutputStream(uri) ?: error("Could not write the backup file in Downloads")
            out.use { o -> file.inputStream().use { it.copyTo(o) } }
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
        runCatching { prune(context) }
        return relative + file.name
    }

    /** Deletes the oldest daily copies this install made, keeping the newest [KEEP]. */
    private fun prune(context: Context) {
        val resolver = context.contentResolver
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val old = mutableListOf<Long>()
        resolver
            .query(
                collection,
                arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME),
                "${MediaStore.MediaColumns.RELATIVE_PATH} = ? AND ${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?",
                arrayOf(relative, "strategyforge-%-auto%.sfbk"),
                "${MediaStore.MediaColumns.DISPLAY_NAME} DESC",
            )?.use { c ->
                var n = 0
                while (c.moveToNext()) {
                    if (++n > KEEP) old += c.getLong(0)
                }
            }
        old.forEach { id -> resolver.delete(ContentUris.withAppendedId(collection, id), null, null) }
    }
}
