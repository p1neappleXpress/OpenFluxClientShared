package io.openflux.desktop.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

/**
 * What an update of the app needs from the network: a text (GitHub's release list, SHA256SUMS.txt) and a
 * file whose SHA-256 is checked as it is written. Only https, and only GitHub's own hosts: a release's
 * download URL redirects to GitHub's storage, nowhere else.
 */
object UpdateDownloads {
    private val hosts = listOf("github.com", "githubusercontent.com")

    /** Which addresses may be opened; a test points it at a local server. */
    internal var allowed: (URL) -> Boolean = { url ->
        url.protocol == "https" && hosts.any { url.host == it || url.host.endsWith(".$it") }
    }

    /** Opens [url], following GitHub's redirects by hand so that each hop can be checked. */
    private fun open(url: String, readTimeoutMs: Int): HttpURLConnection {
        var current = URL(url)
        repeat(6) {
            if (!allowed(current)) throw IOException("Адрес ${current.host} не принадлежит GitHub")
            val c = current.openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 15_000
            c.readTimeout = readTimeoutMs
            c.setRequestProperty("User-Agent", "OpenFlux-Updater")
            c.setRequestProperty("Accept", "application/octet-stream, application/vnd.github+json, text/plain, */*")
            val code = c.responseCode
            if (code in 300..399) {
                val next = c.getHeaderField("Location") ?: throw IOException("Перенаправление без адреса")
                current = URL(current, next)
                c.disconnect()
                return@repeat
            }
            if (code != 200) {
                c.disconnect()
                throw IOException(if (code == 403 || code == 429) "GitHub просит подождать (код $code)" else "Ответ $code")
            }
            return c
        }
        throw IOException("Слишком много перенаправлений")
    }

    suspend fun text(url: String): String = withContext(Dispatchers.IO) {
        val c = open(url, 20_000)
        try {
            c.inputStream.use { it.readNBytes(MAX_TEXT).toString(Charsets.UTF_8) }
        } finally {
            c.disconnect()
        }
    }

    /**
     * Writes [url] to [dir]/[name] through a ".part" file, checks the SHA-256 against [sha256] (hex) and only
     * then gives the file its name. A wrong sum deletes what was written and throws.
     */
    suspend fun file(url: String, dir: File, name: String, sha256: String, onProgress: (Long, Long) -> Unit): File = withContext(Dispatchers.IO) {
        require(name.isNotEmpty() && '/' !in name && '\\' !in name && name != "." && name != "..") { "Недопустимое имя файла" }
        dir.mkdirs()
        val target = File(dir, name)
        val part = File(dir, "$name.part")
        part.delete()
        // A file downloaded and checked earlier (the person had to allow installing first): no second download.
        if (target.isFile && sha256Of(target).equals(sha256, ignoreCase = true)) {
            onProgress(target.length(), target.length())
            return@withContext target
        }
        val c = open(url, 60_000)
        try {
            val total = c.contentLengthLong.coerceAtLeast(0)
            val digest = MessageDigest.getInstance("SHA-256")
            var done = 0L
            var reported = 0L
            c.inputStream.use { input ->
                part.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = input.read(buffer)
                        if (n < 0) break
                        digest.update(buffer, 0, n)
                        output.write(buffer, 0, n)
                        done += n
                        // At most a few updates a second.
                        if (done - reported >= 256 * 1024 || done == total) {
                            reported = done
                            onProgress(done, total)
                        }
                    }
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            if (!actual.equals(sha256, ignoreCase = true)) {
                part.delete()
                throw IOException("Контрольная сумма не совпала: файл повреждён или подменён")
            }
            target.delete()
            if (!part.renameTo(target)) throw IOException("Не удалось сохранить файл")
            onProgress(done, if (total > 0) total else done)
            target
        } catch (e: Throwable) {
            part.delete()
            throw e
        } finally {
            c.disconnect()
        }
    }

    private fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private const val MAX_TEXT = 4 * 1024 * 1024
}
