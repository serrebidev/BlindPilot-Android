package org.blindpilot.android.local

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.blindpilot.android.core.Cancellation
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

data class LocalModel(val name: String, val path: String, val bytes: Long)

/** All model operations run on the caller's worker thread. */
class ModelManager(private val context: Context, private val client: OkHttpClient = OkHttpClient.Builder()
    .readTimeout(60, TimeUnit.SECONDS).build()) {
    private val directory = File(context.filesDir, "models").apply { check(mkdirs() || isDirectory) }

    fun list(): List<LocalModel> = synchronized(lock) {
        directory.listFiles().orEmpty().filter { it.isFile && it.extension == "gguf" }
            .sortedBy { it.name.lowercase() }.map { model(it) }
    }

    fun resolve(path: String): File = checkedModelPath(directory, path).also {
        require(it.isFile) { "Select an installed GGUF model first." }
        validateGguf(it)
    }

    fun import(uri: Uri, cancellation: Cancellation = Cancellation(), onProgress: (Long, Long) -> Unit = { _, _ -> }): LocalModel = synchronized(lock) {
        var name = "imported.gguf"
        var size = -1L
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use {
            if (it.moveToFirst()) {
                val nameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = it.getColumnIndex(OpenableColumns.SIZE)
                if (nameIndex >= 0) name = it.getString(nameIndex) ?: name
                if (sizeIndex >= 0 && !it.isNull(sizeIndex)) size = it.getLong(sizeIndex)
            }
        }
        val destination = destination(name)
        require(!destination.exists()) { "A model with this name already exists. Delete it or rename the source." }
        val temporary = File.createTempFile("import-", ".part", directory)
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                copy(input, temporary, false, size, cancellation, onProgress)
            } ?: throw IOException("Cannot read the selected document.")
            validateGguf(temporary)
            cancellation.check()
            finish(temporary, destination)
        } finally { temporary.delete() }
    }

    /** A published SHA-256 is required so interrupted downloads cannot become installed models. */
    fun download(url: String, name: String, sha256: String, cancellation: Cancellation = Cancellation(),
                 onProgress: (Long, Long) -> Unit = { _, _ -> }): LocalModel = synchronized(lock) {
        require(sha256.matches(Regex("[a-fA-F0-9]{64}"))) { "Enter the model publisher's 64-character SHA-256." }
        val address = url.toHttpUrl()
        require(address.isHttps || address.host in setOf("localhost", "127.0.0.1", "::1")) { "Model downloads require HTTPS." }
        val destination = destination(name)
        require(!destination.exists()) { "A model with this name already exists." }
        // The content hash identifies resumable bytes even when a download URL expires or changes.
        val temporary = File(directory, ".${destination.name}.${sha256.lowercase()}.part")
        val offset = temporary.length()
        cancellation.check()
        val request = Request.Builder().url(address).header("Accept-Encoding", "identity").apply {
            if (offset > 0) header("Range", "bytes=$offset-")
        }.build()
        val call = client.newCall(request)
        cancellation.onCancel { call.cancel() }
        call.execute().use { response ->
            cancellation.check()
            if (response.code != 416 || offset == 0L) {
                if (!response.isSuccessful) throw IOException("Model download failed: HTTP ${response.code}.")
                require(response.request.url.isHttps || response.request.url.host in setOf("localhost", "127.0.0.1", "::1")) { "Download redirected to insecure HTTP." }
                val append = response.code == 206 && offset > 0
                if (response.code == 206) {
                    val range = Regex("bytes (\\d+)-(\\d+)/(\\d+)").matchEntire(response.header("Content-Range").orEmpty())
                        ?: throw IOException("The server returned an invalid download range.")
                    require(range.groupValues[1].toLong() == if (append) offset else 0L) { "The server resumed at the wrong position." }
                    val total = range.groupValues[3].toLong()
                    require(range.groupValues[2].toLong() == total - 1) { "The server did not return the complete remaining model." }
                }
                val body = response.body ?: throw IOException("The download has no model data.")
                val total = if (body.contentLength() >= 0) body.contentLength() + if (append) offset else 0 else -1
                copy(body.byteStream(), temporary, append, total, cancellation, onProgress)
            }
        }
        cancellation.check()
        val actual = digest(temporary, cancellation)
        if (!actual.equals(sha256, true)) {
            temporary.delete()
            throw IOException("Model integrity check failed. The incomplete or changed download was removed; retry it.")
        }
        validateGguf(temporary)
        finish(temporary, destination)
    }

    fun delete(path: String) = synchronized(lock) {
        val file = checkedModelPath(directory, path)
        if (file.exists() && !file.delete()) throw IOException("Cannot delete ${file.name}.")
    }

    private fun destination(name: String): File {
        require(name.matches(Regex("[\\p{L}\\p{N}_. -]+\\.gguf")) && !name.startsWith('.')) { "Use a filename ending in .gguf, without folders." }
        return checkedModelPath(directory, File(directory, name).absolutePath)
    }

    private fun copy(input: InputStream, file: File, append: Boolean, total: Long, cancellation: Cancellation,
                     progress: (Long, Long) -> Unit) {
        var count = if (append) file.length() else 0
        if (total >= 0 && total - count > directory.usableSpace - RESERVE) throw IOException("Not enough free storage for this model.")
        FileOutputStream(file, append).use { output ->
            val buffer = ByteArray(256 * 1024)
            while (true) {
                cancellation.check()
                val read = input.read(buffer)
                if (read < 0) break
                if (directory.usableSpace < read + RESERVE) throw IOException("Model download stopped because storage is nearly full.")
                output.write(buffer, 0, read)
                count += read
                progress(count, total)
            }
            output.fd.sync()
        }
        cancellation.check()
        if (total >= 0 && count != total) throw IOException("Model transfer was interrupted. Retry to resume.")
    }

    private fun finish(temporary: File, destination: File): LocalModel {
        Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
        return model(destination)
    }

    private fun model(file: File) = LocalModel(file.name, file.absolutePath, file.length())

    companion object {
        private val lock = Any()
        private const val RESERVE = 32L * 1024 * 1024
        internal fun checkedModelPath(directory: File, path: String): File {
            val file = File(path)
            require(file.isAbsolute && file.canonicalFile.parentFile == directory.canonicalFile && file.extension == "gguf" && !file.name.startsWith('.')) {
                "Select a GGUF file installed in the app's model storage."
            }
            return file.canonicalFile
        }
        internal fun validateGguf(file: File) {
            require(file.length() >= 24) { "This is not a complete GGUF model." }
            val header = ByteArray(24)
            FileInputStream(file).use { require(it.read(header) == header.size) }
            val bytes = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
            require(bytes.int == 0x46554747 && bytes.int in 2..3 && bytes.long > 0 && bytes.long >= 0) { "The file is not a supported GGUF model (version 2 or 3)." }
        }
        private fun digest(file: File, cancellation: Cancellation): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(256 * 1024)
                while (true) {
                    cancellation.check()
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
