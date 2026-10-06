package io.openflux.desktop

import io.openflux.desktop.web.ArchiveServer
import io.openflux.desktop.web.BuiltInBrowser
import io.openflux.desktop.web.RuntimeArchive
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RuntimeArchiveTest {
    private val closers = mutableListOf<() -> Unit>()

    @AfterTest
    fun cleanup() = closers.forEach { runCatching(it) }

    /** What the origin saw: where in the file a request started and how much of it was sent. */
    private class Seen(val start: Long, var sent: Long = 0)

    /** A file server that behaves badly on request: cuts, stalls, crawls, ignores Range. */
    private inner class Origin(val content: ByteArray) {
        val seen = CopyOnWriteArrayList<Seen>()
        /** Responses still to be cut short, each after [cutAfter] bytes. */
        val cuts = AtomicInteger(0)
        @Volatile var cutAfter = 0L
        @Volatile var honourRange = true
        /** Responses still to hang, each after [stallAfter] bytes. */
        val stalls = AtomicInteger(0)
        @Volatile var stallAfter = 0L
        /** From this request on (0 is the first) the answer is 503; none by default. */
        @Volatile var failFrom = Int.MAX_VALUE
        /** Bytes per second, 0 unlimited. */
        @Volatile var rate = 0L
        private val server = ServerSocket(0, 20, InetAddress.getLoopbackAddress())
        val url = "http://127.0.0.1:${server.localPort}/runtime.tar.gz"
        val sent: Long get() = seen.sumOf { it.sent }

        init {
            closers += { server.close() }
            thread(isDaemon = true) {
                while (!server.isClosed) {
                    val s = runCatching { server.accept() }.getOrNull() ?: break
                    thread(isDaemon = true) { runCatching { serve(s) } }
                }
            }
        }

        private fun readHead(input: InputStream): String {
            val out = ByteArrayOutputStream()
            while (!out.toString(Charsets.ISO_8859_1).endsWith("\r\n\r\n")) {
                val b = input.read()
                if (b < 0) break
                out.write(b)
            }
            return out.toString(Charsets.ISO_8859_1)
        }

        private fun serve(s: Socket) = s.use {
            val head = readHead(it.getInputStream())
            val wanted = Regex("Range: bytes=(\\d+)-", RegexOption.IGNORE_CASE).find(head)?.groupValues?.get(1)?.toLong()
            val from = if (honourRange) wanted ?: 0 else 0
            val record = Seen(from).also { r -> seen += r }
            val out = it.getOutputStream()
            if (seen.size - 1 >= failFrom) {
                out.write("HTTP/1.1 503 Service Unavailable\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                return@use
            }
            val size = content.size.toLong()
            if (from >= size) {
                out.write("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Range: bytes */$size\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                return@use
            }
            val status = if (wanted != null && honourRange) "206 Partial Content\r\nContent-Range: bytes $from-${size - 1}/$size" else "200 OK"
            out.write("HTTP/1.1 $status\r\nContent-Length: ${size - from}\r\nConnection: close\r\n\r\n".toByteArray())
            val cut = cuts.get() > 0 && cuts.decrementAndGet() >= 0
            val stall = stalls.get() > 0 && stalls.decrementAndGet() >= 0
            var position = from.toInt()
            while (position < size) {
                if (cut && record.sent >= cutAfter) return@use
                if (stall && record.sent >= stallAfter) { Thread.sleep(20_000); return@use }
                val chunk = minOf(16 * 1024, size.toInt() - position)
                out.write(content, position, chunk)
                out.flush()
                position += chunk
                record.sent += chunk
                if (rate > 0) Thread.sleep(chunk * 1000L / rate)
            }
        }
    }

    private fun bytes(size: Int = 3_000_000) = Random(7).nextBytes(size)

    private fun sha512(data: ByteArray) = MessageDigest.getInstance("SHA-512").digest(data).joinToString("") { "%02x".format(it) }

    private fun tempDir() = Files.createTempDirectory("runtime-archive-test").toFile().also { d -> closers += { d.deleteRecursively() } }

    private fun archive(
        dir: File,
        data: ByteArray,
        vararg origins: Origin,
        sha: String? = sha512(data),
        rounds: Int = 3,
        slowBytesPerSecond: Long = 64 * 1024,
        slowWindowMs: Long = 20_000,
    ) = RuntimeArchive(
        dir, "runtime.tar.gz", origins.map { it.url }, sha,
        stallMs = 700, slowBytesPerSecond = slowBytesPerSecond, slowWindowMs = slowWindowMs,
        rounds = rounds, pauseMs = 10, spareBytes = 0,
    )

    @Test
    fun aCutConnectionResumesWhereItStoppedInsteadOfStartingOver() {
        val data = bytes()
        val origin = Origin(data).apply { cuts.set(4); cutAfter = 500_000 }
        val dir = tempDir()
        // One round only: attempts that brought data are not counted against it.
        val file = runBlocking { archive(dir, data, origin, rounds = 1).fetch { } }
        assertContentEquals(data, file.readBytes())
        // Every request after the first asked for the rest, not the whole file again.
        assertTrue(origin.seen.size >= 5, "cut four times, so at least five requests: ${origin.seen.size}")
        assertTrue(origin.seen.drop(1).all { it.start > 0 }, "resumed from offsets ${origin.seen.map { it.start }}")
        assertTrue(origin.sent < data.size * 1.3, "sent ${origin.sent} for ${data.size}: restarted?")
        assertFalse(File(dir, "runtime.tar.gz.part").exists())
    }

    @Test
    fun whatFailedToFinishStaysOnDiskAndTheNextTryContinuesFromIt() {
        val data = bytes()
        // The first response brings 1 MB, then the origin has nothing but errors.
        val origin = Origin(data).apply { cuts.set(1); cutAfter = 1_000_000; failFrom = 1 }
        val dir = tempDir()
        val failing = archive(dir, data, origin, rounds = 1)
        val error = assertFailsWith<IllegalStateException> { runBlocking { failing.fetch { } } }
        assertTrue("сохранено" in error.message.orEmpty(), error.message)
        val kept = File(dir, "runtime.tar.gz.part").length()
        assertTrue(kept in 500_000..1_100_000, "kept $kept")
        // A new start of the app: a new object, the same folder.
        origin.failFrom = Int.MAX_VALUE
        val file = runBlocking { archive(dir, data, origin).fetch { } }
        assertContentEquals(data, file.readBytes())
        assertEquals(kept, origin.seen.last().start, "the second try asked only for what was missing")
    }

    @Test
    fun aServerThatIgnoresRangeStartsTheFileOverCleanly() {
        val data = bytes()
        val origin = Origin(data).apply { cuts.set(1); cutAfter = 500_000; honourRange = false }
        val file = runBlocking { archive(tempDir(), data, origin).fetch { } }
        assertContentEquals(data, file.readBytes())
    }

    @Test
    fun aConnectionThatGoesQuietIsCutAtOnceAndKeepsWhatCame() {
        val data = bytes()
        // The first response hangs after 200 KB; then the origin has only errors.
        val origin = Origin(data).apply { stalls.set(1); stallAfter = 200_000; failFrom = 1 }
        val dir = tempDir()
        val started = System.currentTimeMillis()
        assertFailsWith<IllegalStateException> { runBlocking { archive(dir, data, origin, rounds = 1).fetch { } } }
        val took = System.currentTimeMillis() - started
        assertTrue(took < 8_000, "waited out the hang: $took ms")
        assertTrue(File(dir, "runtime.tar.gz.part").length() >= 100_000)
        origin.failFrom = Int.MAX_VALUE
        assertContentEquals(data, runBlocking { archive(dir, data, origin).fetch { } }.readBytes())
        assertEquals(File(dir, "runtime.tar.gz").length(), data.size.toLong())
    }

    @Test
    fun aHangThatEndsByItselfIsResumedWithinTheSameFetch() {
        val data = bytes()
        val origin = Origin(data).apply { stalls.set(2); stallAfter = 300_000 }
        val file = runBlocking { archive(tempDir(), data, origin, rounds = 1).fetch { } }
        assertContentEquals(data, file.readBytes())
        assertTrue(origin.seen.size >= 3)
    }

    @Test
    fun aSlowSourceGivesWayToTheNextAtTheSameOffset() {
        val data = bytes(2_000_000)
        val slow = Origin(data).apply { rate = 150_000 }
        val fast = Origin(data)
        val file = runBlocking {
            archive(tempDir(), data, slow, fast, slowBytesPerSecond = 1_000_000, slowWindowMs = 300).fetch { }
        }
        assertContentEquals(data, file.readBytes())
        assertTrue(fast.seen.isNotEmpty() && fast.seen.first().start > 0, "the second source took over at ${fast.seen.map { it.start }}")
        assertTrue(slow.sent + fast.sent < data.size * 1.2)
    }

    @Test
    fun aSourceThatDroppedWithDataIsAskedAgainBeforeTheNextOne() {
        val data = bytes()
        val mirror = Origin(data).apply { cuts.set(2); cutAfter = 800_000 }
        val cdn = Origin(data)
        val file = runBlocking { archive(tempDir(), data, mirror, cdn, rounds = 1).fetch { } }
        assertContentEquals(data, file.readBytes())
        assertTrue(cdn.seen.isEmpty(), "went on to the next source after ${mirror.seen.size} requests")
        assertTrue(mirror.seen.size >= 3)
    }

    @Test
    fun aSourceThatBringsNothingGivesWayToTheNextWithoutBeingHammered() {
        val data = bytes(500_000)
        val dead = Origin(data).apply { failFrom = 0 }
        val cdn = Origin(data)
        val file = runBlocking { archive(tempDir(), data, dead, cdn).fetch { } }
        assertContentEquals(data, file.readBytes())
        assertEquals(1, dead.seen.size)
    }

    @Test
    fun theLastSourceIsNeverAbandonedForBeingSlow() {
        val data = bytes(600_000)
        val only = Origin(data).apply { rate = 300_000 }
        val file = runBlocking { archive(tempDir(), data, only, slowBytesPerSecond = 10_000_000, slowWindowMs = 200).fetch { } }
        assertContentEquals(data, file.readBytes())
        assertEquals(1, only.seen.size)
    }

    @Test
    fun aWrongChecksumLeavesNothingToUnpackAndRetriesAreBounded() {
        val data = bytes(400_000)
        val origin = Origin(data)
        val dir = tempDir()
        assertFailsWith<IllegalStateException> { runBlocking { archive(dir, data, origin, sha = "00".repeat(64), rounds = 2).fetch { } } }
        assertEquals(2, origin.seen.size, "one request per round, no endless loop")
        assertFalse(File(dir, "runtime.tar.gz").exists())
        assertFalse(File(dir, "runtime.tar.gz.part").exists())
    }

    @Test
    fun aFinishedArchiveIsNotFetchedAgain() {
        val data = bytes(400_000)
        val origin = Origin(data)
        val dir = tempDir()
        runBlocking { archive(dir, data, origin).fetch { } }
        runBlocking { archive(dir, data, origin).fetch { } }
        assertEquals(1, origin.seen.size)
    }

    @Test
    fun aDamagedArchiveOnDiskIsReplaced() {
        val data = bytes(400_000)
        val origin = Origin(data)
        val dir = tempDir()
        File(dir, "runtime.tar.gz").writeBytes(data.copyOf(1000))
        assertContentEquals(data, runBlocking { archive(dir, data, origin).fetch { } }.readBytes())
    }

    @Test
    fun aDownloadThatEndedJustBeforeTheRenameIsAcceptedWithoutAskingForMore() {
        val data = bytes(400_000)
        val origin = Origin(data)
        val dir = tempDir()
        File(dir, "runtime.tar.gz.part").writeBytes(data)
        assertContentEquals(data, runBlocking { archive(dir, data, origin).fetch { } }.readBytes())
        assertEquals(0L, origin.sent)
    }

    @Test
    fun discardLeavesNoFolderBehind() {
        val data = bytes(100_000)
        val dir = tempDir()
        val a = archive(dir, data, Origin(data))
        runBlocking { a.fetch { } }
        a.discard()
        assertFalse(dir.exists())
    }

    @Test
    fun progressTellsResumedFromFresh() {
        val data = bytes()
        val origin = Origin(data).apply { cuts.set(1); cutAfter = 1_000_000 }
        val steps = mutableListOf<String>()
        runBlocking { archive(tempDir(), data, origin).fetch { steps += it } }
        assertTrue(steps.any { it.startsWith("Скачиваю") && it.endsWith("%") })
        assertTrue(steps.any { it.startsWith("Продолжаю") }, "no step said the download went on: $steps")
    }

    @Test
    fun theCheckIsAnnouncedAfterTheDownloadAndBeforeAnythingUnpacks() {
        val data = bytes()
        val steps = mutableListOf<String>()
        runBlocking { archive(tempDir(), data, Origin(data)).fetch { steps += it } }
        val check = steps.indexOfFirst { it.startsWith("Проверяю") }
        assertTrue(check > 0, "no step announced the check: $steps")
        assertTrue(steps.subList(0, check).all { it.startsWith("Скачиваю") || it.startsWith("Продолжаю") }, "$steps")
        assertEquals(steps.size - 1, check, "nothing but the check comes after the download: $steps")
    }

    @Test
    fun theArchiveServerHandsKcefExactlyTheFile() {
        val data = bytes(300_000)
        val file = File(tempDir(), "runtime.tar.gz").apply { writeBytes(data) }
        val server = ArchiveServer(file).also { closers += { it.close() } }
        val client = HttpClient.newHttpClient()
        val got = client.send(HttpRequest.newBuilder(URI(server.url)).build(), HttpResponse.BodyHandlers.ofByteArray())
        assertEquals(200, got.statusCode())
        assertContentEquals(data, got.body())
        val post = client.send(HttpRequest.newBuilder(URI(server.url)).POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.discarding())
        assertEquals(405, post.statusCode())
    }

    @Test
    fun everyPlatformHasItsPinnedChecksum() {
        val combos = listOf(
            Triple("Windows 11", "amd64", "windows-x64"), Triple("Windows 11", "aarch64", "windows-aarch64"),
            Triple("Mac OS X", "x86_64", "osx-x64"), Triple("Mac OS X", "aarch64", "osx-aarch64"),
            Triple("Linux", "amd64", "linux-x64"), Triple("Linux", "aarch64", "linux-aarch64"),
        )
        for ((os, arch, key) in combos) {
            assertEquals(key, BuiltInBrowser.runtimeKey(os, arch))
            assertTrue(BuiltInBrowser.packageUrl(os, arch).endsWith("-$key-b895.97.tar.gz"))
            val sum = BuiltInBrowser.RUNTIME_SHA512[key]
            assertTrue(sum != null && sum.matches(Regex("[0-9a-f]{128}")), "no pinned checksum for $key")
        }
    }

    @Test
    fun theRuntimeIsInstalledOnlyWithItsLockAndItsLibrary() {
        val dir = tempDir()
        assertFalse(BuiltInBrowser.runtimeInstalled(dir))
        File(dir, "libcef.dll").writeText("x")
        assertFalse(BuiltInBrowser.runtimeInstalled(dir), "files without the lock: unpacking did not finish")
        File(dir, "install.lock").writeText("")
        assertTrue(BuiltInBrowser.runtimeInstalled(dir))
        File(dir, "libcef.dll").delete()
        assertFalse(BuiltInBrowser.runtimeInstalled(dir), "the lock alone: a cleaner took the library")
    }

    @Test
    fun theStorageReleaseComesBeforeTheCdnAndAMirrorFromTheEnvironmentBeforeBoth() {
        val cdn = BuiltInBrowser.packageUrl()
        val storage = BuiltInBrowser.mirrorUrl()
        assertEquals(listOf(storage, cdn), BuiltInBrowser.runtimeSources(null))
        assertEquals(listOf(storage, cdn), BuiltInBrowser.runtimeSources("not a url"))
        assertEquals(listOf("https://mirror.example/jbr.tar.gz", storage, cdn), BuiltInBrowser.runtimeSources(" https://mirror.example/jbr.tar.gz "))
    }

    @Test
    fun theStorageReleaseHoldsTheFileUnderTheNameTheCdnHasIt() {
        val url = BuiltInBrowser.mirrorUrl("Windows 11", "amd64")
        assertEquals(
            "https://github.com/p1neappleXpress/OpenFluxDesktop/releases/download/browser-runtime-21.0.6-b895.97/jbr_jcef-21.0.6-windows-x64-b895.97.tar.gz",
            url,
        )
        assertEquals(BuiltInBrowser.packageUrl("Windows 11", "amd64").substringAfterLast('/'), url.substringAfterLast('/'))
    }
}
