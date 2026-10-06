package io.openflux.desktop.web

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * The browser runtime's archive (a JetBrains Runtime with JCEF, about 230 MB)
 * and how it gets to the disk.
 *
 * KCEF downloads it into a temp file from the first byte, deletes its install
 * folder first whenever an earlier install did not finish, and tries twice per
 * start. On a slow or cut connection that was a new 230 MB attempt after every
 * failure, each ending in "restart OpenFlux". So the archive is fetched here,
 * into a folder that outlives a failed attempt, a restart and a failed start:
 *  - what has arrived stays as `.part`, the next attempt asks only for the
 *    rest (Range);
 *  - a connection that gives no data for [stallMs] is cut and tried again (it has
 *    to be closed: interrupting a blocked read of the body does nothing);
 *  - an attempt that brought data does not use up [rounds], only one that
 *    brought none does, so a connection that keeps dropping still gets there;
 *  - with several [sources], one that is too slow gives way to the next at the
 *    same offset, since they serve the same file;
 *  - the whole is checked against [sha512] before anything unpacks it, so a
 *    mirror needs no trust;
 *  - the archive is deleted only once the browser has started from it
 *    ([discard]), so a failed unpacking or start costs no download.
 */
internal class RuntimeArchive(
    private val dir: File,
    private val name: String,
    private val sources: List<String>,
    /** Lower-case hex of the whole archive; null skips the check (a platform nobody pinned). */
    private val sha512: String?,
    private val stallMs: Long = 25_000,
    private val slowBytesPerSecond: Long = 64 * 1024,
    private val slowWindowMs: Long = 20_000,
    private val rounds: Int = 3,
    private val pauseMs: Long = 5_000,
    /** What unpacking and starting need on top of the archive itself. */
    private val spareBytes: Long = 800L * 1024 * 1024,
) {
    val file: File get() = File(dir, name)
    private val part: File get() = File(dir, "$name.part")

    private val http = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(20))
        .build()

    /** The whole, verified archive; downloads, or finishes downloading, what is missing. */
    suspend fun fetch(onStep: (String) -> Unit): File {
        if (file.isFile) {
            onStep("Проверяю скачанный браузер…")
            if (matches(file)) return file
            BrowserLog.problem("сохранённый архив браузера повреждён, скачиваю заново")
            file.delete()
        }
        dir.mkdirs()
        var last = "нет источников"
        var empty = 0
        var attempts = 0
        while (empty < rounds && attempts < MAX_ATTEMPTS) {
            val before = part.length()
            for ((index, source) in sources.withIndex()) {
                // A source that dropped after bringing data is asked again, from where it stopped;
                // the next one is for a source that brought nothing or was too slow.
                while (attempts++ < MAX_ATTEMPTS) {
                    val had = part.length()
                    try {
                        download(source, canGiveWay = index < sources.lastIndex, onStep)
                        return finish(onStep)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: OutOfSpace) {
                        throw IllegalStateException(e.message, e)
                    } catch (e: Exception) {
                        last = e.message ?: e.javaClass.simpleName
                        BrowserLog.problem("скачивание с ${BrowserLog.short(source)}: $last (на диске ${part.length() / MB} МБ)")
                        if (e is TooSlow || part.length() <= had) break
                    }
                }
            }
            if (part.length() > before) continue
            if (++empty < rounds) delay(pauseMs * empty)
        }
        throw IllegalStateException("Не удалось скачать встроенный браузер: $last. Скачанное (${part.length() / MB} МБ) сохранено, повтор продолжит с этого места")
    }

    /** Removes the archive and what is left of an unfinished one: the browser runs from its own folder now. */
    fun discard() {
        file.delete()
        part.delete()
        dir.delete()
    }

    private suspend fun download(source: String, canGiveWay: Boolean, onStep: (String) -> Unit) {
        val have = part.length()
        val request = HttpRequest.newBuilder(URI(source)).timeout(Duration.ofSeconds(30)).GET()
            .apply { if (have > 0) header("Range", "bytes=$have-") }
            .build()
        val response = runInterruptible(Dispatchers.IO) { http.send(request, HttpResponse.BodyHandlers.ofInputStream()) }
        response.body().use { input ->
            val total: Long
            val start: Long
            when (response.statusCode()) {
                206 -> {
                    val range = response.headers().firstValue("Content-Range").orElse("")
                    total = range.substringAfter('/').toLongOrNull() ?: throw IOException("сервер не назвал размер")
                    start = range.removePrefix("bytes ").substringBefore('-').toLongOrNull() ?: -1
                    if (start != have) {
                        part.delete()
                        throw IOException("сервер отдал не то место файла ($start вместо $have)")
                    }
                }
                200 -> {
                    // The server ignored Range (or nothing was there): the file starts over.
                    total = response.headers().firstValueAsLong("Content-Length").orElse(-1)
                    start = 0
                    if (have > 0) part.delete()
                }
                416 -> {
                    // Nothing left to ask for: what is on the disk is all of it, if the size agrees.
                    val size = response.headers().firstValue("Content-Range").orElse("").substringAfter('/').toLongOrNull()
                    if (size == have) return
                    part.delete()
                    throw IOException("сервер не знает такого места файла")
                }
                else -> throw IOException("HTTP ${response.statusCode()}")
            }
            if (total > 0 && dir.usableSpace < total - start + spareBytes) {
                throw OutOfSpace("Не хватает места на диске: нужно ещё около ${(total - start + spareBytes) / MB} МБ в ${dir.absolutePath}")
            }
            BrowserLog.info("скачиваю ${BrowserLog.short(source)}: с ${start / MB} МБ из ${if (total > 0) "${total / MB}" else "?"}")
            copy(input, start, total, canGiveWay, onStep)
        }
    }

    private suspend fun copy(input: InputStream, start: Long, total: Long, canGiveWay: Boolean, onStep: (String) -> Unit) = coroutineScope {
        var done = start
        var windowStart = System.currentTimeMillis()
        var windowBytes = 0L
        val resumed = start > 0
        val lastData = AtomicLong(windowStart)
        val finished = AtomicBoolean(false)
        val stalled = AtomicBoolean(false)
        // A read of the body blocks and does not wake on an interrupt; closing the stream does.
        // Also when this is cancelled: nobody is left to read what comes.
        val watchdog = launch(Dispatchers.IO) {
            try {
                while (isActive) {
                    delay((stallMs / 4).coerceIn(50, 1000))
                    if (System.currentTimeMillis() - lastData.get() > stallMs) {
                        stalled.set(true)
                        break
                    }
                }
            } finally {
                if (!finished.get()) runCatching { input.close() }
            }
        }
        try {
            RandomAccessFile(part, "rw").use { out ->
                out.seek(start)
                out.setLength(start)
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = try {
                        withContext(Dispatchers.IO) { input.read(buffer) }
                    } catch (e: IOException) {
                        throw if (stalled.get()) IOException("нет данных ${stallMs / 1000} с") else e
                    }
                    if (read < 0) break
                    out.write(buffer, 0, read)
                    done += read
                    windowBytes += read
                    val now = System.currentTimeMillis()
                    lastData.set(now)
                    if (now - windowStart >= slowWindowMs) {
                        val rate = windowBytes * 1000 / (now - windowStart)
                        if (rate < slowBytesPerSecond && canGiveWay) throw TooSlow("источник слишком медленный (${rate / 1024} КБ/с)")
                        onStep(progress(done, total, resumed, rate.takeIf { it < slowBytesPerSecond }))
                        windowStart = now
                        windowBytes = 0
                    } else {
                        onStep(progress(done, total, resumed, null))
                    }
                }
            }
            if (stalled.get()) throw IOException("нет данных ${stallMs / 1000} с")
            if (total > 0 && done < total) throw IOException("соединение закрыто на $done из $total")
        } finally {
            finished.set(true)
            watchdog.cancel()
        }
    }

    private fun progress(done: Long, total: Long, resumed: Boolean, slowRate: Long?): String {
        val percent = if (total > 0) (done * 100 / total).toInt() else 0
        val head = if (resumed) "Продолжаю скачивать встроенный браузер" else "Скачиваю встроенный браузер (около 230 МБ, один раз)"
        return "$head…" + (if (percent in 1..99) " $percent%" else "") +
            (if (slowRate != null) " (медленно: ${slowRate / 1024} КБ/с)" else "")
    }

    /** Checks the downloaded file and puts it under its final name. */
    private fun finish(onStep: (String) -> Unit): File {
        onStep("Проверяю скачанный браузер…")
        if (!matches(part)) {
            part.delete()
            throw IOException("контрольная сумма скачанного не совпала")
        }
        Files.move(part.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        return file
    }

    private fun matches(candidate: File): Boolean {
        val expected = sha512 ?: return true
        val digest = MessageDigest.getInstance("SHA-512")
        candidate.inputStream().use { stream ->
            val buffer = ByteArray(256 * 1024)
            while (true) {
                val n = stream.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) } == expected
    }

    /** Not worth asking this source again: the next one may do better. */
    private class TooSlow(message: String) : IOException(message)

    /** Not worth another source or another round: no disk will have the room next time either. */
    private class OutOfSpace(message: String) : IOException(message)

    private companion object {
        const val MB = 1024L * 1024
        /** However it goes, a fetch ends: only an attempt that brought something is not counted against [rounds]. */
        const val MAX_ATTEMPTS = 200
    }
}
