package io.openflux.desktop

import com.sun.net.httpserver.HttpServer
import io.openflux.desktop.core.WinDivertFiles
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WinDivertFilesTest {
    private val closers = mutableListOf<() -> Unit>()

    @AfterTest
    fun cleanup() = closers.forEach { runCatching(it) }

    private val dll = ByteArray(2000) { (it * 7).toByte() }
    private val sys = ByteArray(3000) { (it * 13).toByte() }

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    /** WinDivert-2.2.2-A.zip's shape: the 32-bit files, the 64-bit ones, a license, a script that must stay unread. */
    private val zip: ByteArray = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { z ->
            fun put(name: String, data: ByteArray) { z.putNextEntry(ZipEntry(name)); z.write(data); z.closeEntry() }
            put("WinDivert-2.2.2-A/x86/WinDivert.dll", byteArrayOf(1, 2, 3))
            put("WinDivert-2.2.2-A/x64/WinDivert.dll", dll)
            put("WinDivert-2.2.2-A/x64/WinDivert64.sys", sys)
            put("WinDivert-2.2.2-A/x64/netdump.exe", byteArrayOf(9))
            put("WinDivert-2.2.2-A/LICENSE", "license text".toByteArray())
            put("WinDivert-2.2.2-A/../../evil.txt", byteArrayOf(6))
        }
    }.toByteArray()

    private fun serve(body: ByteArray?, hits: AtomicInteger = AtomicInteger()): String {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            hits.incrementAndGet()
            if (body == null) ex.sendResponseHeaders(404, -1) else { ex.sendResponseHeaders(200, body.size.toLong()); ex.responseBody.write(body) }
            ex.close()
        }
        server.start()
        closers += { server.stop(0) }
        return "http://127.0.0.1:${server.address.port}/WinDivert-2.2.2-A.zip"
    }

    private fun tempDir() = Files.createTempDirectory("windivert-test").toFile().also { d -> closers += { d.deleteRecursively() } }

    private fun files(dir: File, sources: List<String>, zipSha: String = sha(zip)) = WinDivertFiles(
        dir, sources, zipSha, mapOf("WinDivert.dll" to sha(dll), "WinDivert64.sys" to sha(sys)),
    )

    private val core get() = File(tempDir(), "openflux-windows-amd64.exe")

    @Test
    fun missingFilesAreFetchedOnceAndTheCoreRunsInTheirFolder() {
        val hits = AtomicInteger()
        val dir = File(tempDir(), "wd")
        val log = mutableListOf<String>()
        val fetched = files(dir, listOf(serve(zip, hits)))
        val got = fetched.folderFor(core) { log += it }
        assertEquals(dir, got)
        assertContentEquals(dll, File(dir, "WinDivert.dll").readBytes())
        assertContentEquals(sys, File(dir, "WinDivert64.sys").readBytes())
        assertEquals("license text", File(dir, "WinDivert-LICENSE.txt").readText())
        // Only what is needed is taken: no 32-bit file, no tool, nothing from a path inside the archive.
        assertEquals(setOf("WinDivert.dll", "WinDivert64.sys", "WinDivert-LICENSE.txt"), dir.list()!!.toSet())
        assertTrue(log.any { it.startsWith("Скачиваю WinDivert") })
        assertEquals(1, hits.get())
        // The second start finds them and asks for nothing.
        assertEquals(dir, files(dir, listOf(serve(zip, hits))).folderFor(core) { })
        assertEquals(1, hits.get())
    }

    @Test
    fun filesBesideTheCoreAreUsedAsTheyAre() {
        val coreDir = tempDir()
        File(coreDir, "WinDivert.dll").writeText("their own build")
        File(coreDir, "WinDivert64.sys").writeText("their own driver")
        val hits = AtomicInteger()
        val got = files(File(tempDir(), "wd"), listOf(serve(zip, hits))).folderFor(File(coreDir, "openflux.exe")) { }
        assertEquals(coreDir, got)
        assertEquals(0, hits.get())
    }

    @Test
    fun aFileThatWasSwappedIsFetchedAgain() {
        val dir = File(tempDir(), "wd")
        val fetched = files(dir, listOf(serve(zip)))
        fetched.folderFor(core) { }
        File(dir, "WinDivert64.sys").writeText("not the driver")
        fetched.folderFor(core) { }
        assertContentEquals(sys, File(dir, "WinDivert64.sys").readBytes())
    }

    @Test
    fun aSourceThatFailsGivesWayToTheNextAndABadArchiveIsNeverUnpacked() {
        val dir = File(tempDir(), "wd")
        val log = mutableListOf<String>()
        val got = files(dir, listOf(serve(null), serve("not the archive".toByteArray()), serve(zip))).folderFor(core) { log += it }
        assertEquals(dir, got)
        assertTrue(log.any { "HTTP 404" in it })
        assertTrue(log.any { "контрольная сумма" in it })
    }

    @Test
    fun whenNoSourceDeliversTheUserIsToldWhatToDoAndNothingIsLeftBehind() {
        val dir = File(tempDir(), "wd")
        val error = assertFailsWith<IllegalStateException> {
            files(dir, listOf(serve(null), serve("junk".toByteArray()))).folderFor(core) { }
        }
        assertTrue("WinDivert.dll" in error.message.orEmpty() && "выберите L4" in error.message.orEmpty(), error.message)
        assertFalse(File(dir, "WinDivert.dll").exists() || File(dir, "WinDivert64.sys").exists())
    }

    @Test
    fun thePinnedHashesAreOfTheOfficialRelease() {
        assertEquals(64, WinDivertFiles.ZIP_SHA256.length)
        assertTrue(WinDivertFiles.FILE_SHA256.values.all { it.matches(Regex("[0-9a-f]{64}")) })
        assertTrue(WinDivertFiles.SOURCES.first().startsWith("https://github.com/basil00/WinDivert/releases/download/v2.2.2/"))
    }
}
