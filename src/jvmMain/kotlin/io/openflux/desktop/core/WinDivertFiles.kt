package io.openflux.desktop.core

import io.openflux.desktop.data.AppDirs
import java.io.ByteArrayInputStream
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Duration
import java.util.zip.ZipInputStream

/**
 * WinDivert for the core's L3 exit on Windows: the DLL the core loads and, beside it, the signed
 * driver the DLL loads. The core asks Windows for "WinDivert.dll" by name, so it finds them in the
 * folder it is run in; [folderFor] says which folder that is.
 *
 * Beside the core when the package or the user put them there. Otherwise they are fetched, once,
 * from the WinDivert release into a folder of the app's own: the zip is checked against the
 * SHA-256 pinned here and so is each file taken from it, and the files are checked again before
 * every start, so a file that was swapped or taken by a cleaner is fetched again instead of being
 * loaded into the kernel by an elevated core.
 */
internal class WinDivertFiles(
    private val dir: File = defaultDir(),
    private val sources: List<String> = SOURCES,
    private val zipSha256: String = ZIP_SHA256,
    private val fileSha256: Map<String, String> = FILE_SHA256,
) {
    /** The folder to run the core at [core] in so that it finds WinDivert; fetches what is missing. */
    fun folderFor(core: File, log: (String) -> Unit): File {
        val beside = core.parentFile
        if (NAMES.all { File(beside, it).isFile }) return beside
        if (intact()) return dir
        log("Скачиваю WinDivert $VERSION (около 0,4 МБ): без него нода L3 на Windows не работает")
        var last = "нет источников"
        for (source in sources) {
            try {
                fetch(source)
                check(intact()) { "файлы не те, что в архиве (их мог убрать антивирус)" }
                log("WinDivert готов: $dir")
                return dir
            } catch (e: Exception) {
                last = e.message ?: e.javaClass.simpleName
                log("WinDivert: ${source.substringBefore('?')}: $last")
            }
        }
        throw IllegalStateException(
            "Не удалось скачать WinDivert ($last). Положите WinDivert.dll и WinDivert64.sys рядом с ядром ($beside) или выберите L4",
        )
    }

    private fun intact(): Boolean = fileSha256.all { (name, sum) -> File(dir, name).let { it.isFile && sha256(it.readBytes()) == sum } }

    private fun fetch(source: String) {
        val http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(15)).build()
        val request = HttpRequest.newBuilder(URI(source)).timeout(Duration.ofSeconds(60)).GET().build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofInputStream())
        check(response.statusCode() == 200) { "HTTP ${response.statusCode()}" }
        val zip = response.body().use { it.readNBytes(MAX_ZIP + 1) }
        check(zip.size <= MAX_ZIP) { "архив больше ожидаемого" }
        check(sha256(zip) == zipSha256) { "контрольная сумма архива не совпала" }
        dir.mkdirs()
        ZipInputStream(ByteArrayInputStream(zip)).use { entries ->
            while (true) {
                val entry = entries.nextEntry ?: break
                if (entry.isDirectory) continue
                val base = entry.name.substringAfterLast('/')
                // The 64-bit files, and the license that has to go with them; nothing else is taken, nor
                // a path from the archive.
                val target = when {
                    "/x64/" in entry.name && base in NAMES -> base
                    base == "LICENSE" && entry.name.count { it == '/' } == 1 -> "WinDivert-LICENSE.txt"
                    else -> continue
                }
                val tmp = File(dir, "$target.part")
                tmp.outputStream().use { entries.copyTo(it) }
                Files.move(tmp.toPath(), File(dir, target).toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    companion object {
        const val VERSION = "2.2.2"
        val NAMES = listOf("WinDivert.dll", "WinDivert64.sys")

        /** The official release (GitHub first: the same file as on reqrypt.org, and reachable where that is not). */
        val SOURCES = listOf(
            "https://github.com/basil00/WinDivert/releases/download/v$VERSION/WinDivert-$VERSION-A.zip",
            "https://reqrypt.org/download/WinDivert-$VERSION-A.zip",
        )

        /** WinDivert-2.2.2-A.zip as published, and the 64-bit DLL and driver inside it. */
        const val ZIP_SHA256 = "63cb41763bb4b20f600b6de04e991a9c2be73279e317d4d82f237b150c5f3f15"
        val FILE_SHA256 = mapOf(
            "WinDivert.dll" to "c1e060ee19444a259b2162f8af0f3fe8c4428a1c6f694dce20de194ac8d7d9a2",
            "WinDivert64.sys" to "8da085332782708d8767bcace5327a6ec7283c17cfb85e40b03cd2323a90ddc2",
        )

        private const val MAX_ZIP = 4 * 1024 * 1024

        fun defaultDir(): File {
            val local = System.getenv("LOCALAPPDATA")?.let { File(it, "OpenFlux") } ?: AppDirs.config
            return File(local, "windivert/$VERSION")
        }
    }
}
