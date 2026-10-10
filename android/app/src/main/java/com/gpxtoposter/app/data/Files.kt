package com.gpxtoposter.app.data

import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException

/** Matches the server's upload limit. */
const val MAX_TRACK_BYTES = 20 * 1024 * 1024

class TrackFile(val name: String, val bytes: ByteArray)

/** Read a picked or shared track file fully into memory (content:// permissions may not outlive the intent). */
fun readTrack(context: Context, uri: Uri): TrackFile {
    val resolver = context.contentResolver
    var name: String? = null
    resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) name = cursor.getString(0)
    }
    val bytes = resolver.openInputStream(uri)?.use { input ->
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            if (out.size() + n > MAX_TRACK_BYTES) throw IOException("File is larger than 20 MB")
            out.write(buffer, 0, n)
        }
        out.toByteArray()
    } ?: throw IOException("Cannot open file")
    return TrackFile(name ?: uri.lastPathSegment ?: "track", bytes)
}

/** Downloaded posters live in the app cache and are shared via FileProvider. */
object PosterFiles {
    fun dir(context: Context): File = File(context.cacheDir, "posters").apply { mkdirs() }

    fun mimeType(file: File): String = when (file.extension.lowercase()) {
        "png" -> "image/png"
        "svg" -> "image/svg+xml"
        "pdf" -> "application/pdf"
        else -> "application/octet-stream"
    }

    private fun uri(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

    fun share(context: Context, file: File) {
        val intent = Intent(Intent.ACTION_SEND)
            .setType(mimeType(file))
            .putExtra(Intent.EXTRA_STREAM, uri(context, file))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(intent, "Share poster"))
    }

    fun open(context: Context, file: File) {
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri(context, file), mimeType(file))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, "No app can open ${file.extension.uppercase()} files", Toast.LENGTH_SHORT).show()
        }
    }

    /** Copy a poster into shared storage: PNGs to Pictures, PDF/SVG to Downloads. Returns the folder. */
    suspend fun saveToDevice(context: Context, file: File): String = withContext(Dispatchers.IO) {
        val mime = mimeType(file)
        val isImage = mime == "image/png"
        val collection = if (isImage) {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        } else {
            MediaStore.Downloads.EXTERNAL_CONTENT_URI
        }
        val folder = (if (isImage) Environment.DIRECTORY_PICTURES else Environment.DIRECTORY_DOWNLOADS) +
            "/GPX Posters"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, folder)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val target = resolver.insert(collection, values) ?: throw IOException("Could not create file")
        try {
            resolver.openOutputStream(target)?.use { out -> file.inputStream().use { it.copyTo(out) } }
                ?: throw IOException("Could not write file")
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(target, values, null, null)
        } catch (e: Exception) {
            resolver.delete(target, null, null)
            throw e
        }
        folder
    }
}
