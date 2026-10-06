package io.openflux.desktop.platform

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import io.openflux.desktop.core.CoreBinary
import io.openflux.desktop.core.ExitL3
import io.openflux.desktop.service.PlatformServices
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.awt.Desktop
import java.awt.FileDialog
import java.awt.Frame
import java.awt.Image
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.image.BufferedImage
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO

class JvmPlatformServices(
    override val appVersion: String,
    private val binary: CoreBinary,
    private val coreVersionProvider: () -> String,
) : PlatformServices {
    private val os = System.getProperty("os.name").lowercase()
    private val random = SecureRandom()

    override val coreVersion: String get() = coreVersionProvider()
    override val clientRepo: String get() = RELEASE_REPO
    override val systemProxySupported: Boolean = os.contains("win")
    override val fullTunnelSupported: Boolean = os.contains("win") || MacElevation.mac
    override val elevated: Boolean get() = if (MacElevation.mac) MacElevation.root else WindowsElevation.elevated
    override val fullTunnelPrompt: String? get() = when {
        MacElevation.mac && !MacElevation.root -> "при подключении macOS спросит пароль администратора"
        WindowsCoreElevation.windows && !WindowsElevation.elevated -> "при подключении Windows попросит разрешение администратора"
        else -> null
    }

    override val exitL3Supported: Boolean = os.contains("win") || os.contains("linux")
    override val exitL3Needs: String? get() = when {
        WindowsCoreElevation.windows && !WindowsElevation.elevated ->
            "при запуске Windows попросит разрешение администратора, а драйвер WinDivert (0,4 МБ) скачается сам, если его нет"
        WindowsCoreElevation.windows -> "драйвер WinDivert (0,4 МБ) скачается сам, если его нет"
        os.contains("linux") && !ExitL3.isRoot() -> "запустите OpenFlux от root"
        else -> null
    }

    override fun restartElevated(): Boolean {
        if (!WindowsElevation.restartElevated(RELAUNCHED_ARG)) return false
        // The shutdown hook stops the core and puts the system proxy back.
        kotlin.system.exitProcess(0)
    }

    private val clipboard get() = Toolkit.getDefaultToolkit().systemClipboard

    override fun clipboardText(): String? = runCatching {
        if (clipboard.isDataFlavorAvailable(DataFlavor.stringFlavor)) clipboard.getData(DataFlavor.stringFlavor) as String else null
    }.getOrNull()

    override fun setClipboardText(text: String) {
        clipboard.setContents(StringSelection(text), null)
    }

    override fun qrFromClipboardImage(): String? = runCatching {
        if (!clipboard.isDataFlavorAvailable(DataFlavor.imageFlavor)) return null
        decodeQr(toBuffered(clipboard.getData(DataFlavor.imageFlavor) as Image))
    }.getOrNull()

    override fun qrFromFile(path: String): String? = runCatching {
        ImageIO.read(File(path))?.let(::decodeQr)
    }.getOrNull()

    // The AWT dialog is modal on the UI thread, as when it was called from a click.
    override suspend fun pickFile(title: String, extensions: List<String>): String? = withContext(Dispatchers.Main) {
        val dialog = FileDialog(null as Frame?, title, FileDialog.LOAD)
        if (extensions.isNotEmpty()) {
            dialog.setFilenameFilter { _, name -> extensions.any { name.lowercase().endsWith(".$it") } }
            if (os.contains("win")) dialog.file = extensions.joinToString(";") { "*.$it" }
        }
        dialog.isVisible = true
        dialog.file?.let { File(dialog.directory, it).absolutePath }
    }

    override fun readTextFile(path: String, maxBytes: Int): String? = runCatching {
        val file = File(path)
        if (!file.isFile || file.length() > maxBytes) return null
        file.readText()
    }.getOrNull()

    override fun qrMatrix(text: String): List<BooleanArray> {
        val matrix = QRCodeWriter().encode(
            text, BarcodeFormat.QR_CODE, 0, 0,
            mapOf(EncodeHintType.MARGIN to 0, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M),
        )
        return List(matrix.height) { y -> BooleanArray(matrix.width) { x -> matrix[x, y] } }
    }

    override fun openUrl(url: String) {
        runCatching { Desktop.getDesktop().browse(URI(url)) }
    }

    override fun newSecret(): String {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    override fun now(): Long = System.currentTimeMillis()

    override suspend fun latestRelease(): String? = withContext(Dispatchers.IO) {
        releaseTags().firstOrNull { it.startsWith(DESKTOP_TAG_PREFIX) }?.removePrefix(DESKTOP_TAG_PREFIX)
    }

    /** The newest release of either channel; GitHub lists them newest first. */
    override suspend fun latestNightly(): String? = withContext(Dispatchers.IO) {
        releaseTags().firstOrNull { it.startsWith(DESKTOP_TAG_PREFIX) || it.startsWith(NIGHTLY_TAG_PREFIX) }
            ?.removePrefix(DESKTOP_TAG_PREFIX)
    }

    /** Tags of the published (non-draft) releases, newest first; empty when offline. */
    private fun releaseTags(): List<String> = runCatching {
        // GitHub answers a renamed repository with a redirect.
        val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NORMAL).build()
        val request = HttpRequest.newBuilder(URI("https://api.github.com/repos/$RELEASE_REPO/releases?per_page=30"))
            .header("User-Agent", "OpenFlux-Desktop").timeout(Duration.ofSeconds(10)).build()
        val body = http.send(request, HttpResponse.BodyHandlers.ofString()).body()
        Json.parseToJsonElement(body).jsonArray
            .map { it.jsonObject }
            .filter { it["draft"]?.jsonPrimitive?.content != "true" }
            .map { it["tag_name"]?.jsonPrimitive?.content.orEmpty() }
    }.getOrDefault(emptyList())

    private fun decodeQr(image: BufferedImage): String? {
        val pixels = IntArray(image.width * image.height)
        image.getRGB(0, 0, image.width, image.height, pixels, 0, image.width)
        val bitmap = BinaryBitmap(HybridBinarizer(RGBLuminanceSource(image.width, image.height, pixels)))
        return try {
            MultiFormatReader().decode(
                bitmap,
                mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE), DecodeHintType.TRY_HARDER to true),
            ).text
        } catch (_: NotFoundException) {
            null
        }
    }

    private fun toBuffered(image: Image): BufferedImage {
        if (image is BufferedImage) return image
        val buffered = BufferedImage(image.getWidth(null), image.getHeight(null), BufferedImage.TYPE_INT_ARGB)
        buffered.createGraphics().apply { drawImage(image, 0, 0, null); dispose() }
        return buffered
    }

    // --- JS script transports ---
    //
    // Unlike the mobile apps (gomobile, in-process), desktop has only the
    // compiled core binary: inspectTransport shells out to its
    // --inspect-script subcommand (transport/script.InspectTrust under
    // the hood - the one place that logic lives, see core/script_cli.go).
    // scriptFingerprint needs no process at all: SHA-256 of a public key
    // is safe to compute here directly, same hash every platform shows
    // the user to compare out of band.

    override val officialScriptKey: String get() = OFFICIAL_SCRIPT_KEY

    override fun inspectTransport(data: ByteArray, sig: ByteArray, pubkeyHex: String): String {
        val core = binary.bundled()
            ?: return """{"ok":false,"signature":"unverified","error":"ядро не найдено"}"""
        return runInspectScript(core, data, sig, pubkeyHex)
    }

    // The settings wizard of an installed transport: `--script-settings` (transport/script.BuildSettings).
    override fun scriptSettings(data: ByteArray, sig: ByteArray, pubkeyHex: String, valuesJson: String, lang: String): String {
        val core = binary.bundled()
            ?: return """{"ok":false,"code":"failed","error":"ядро не найдено"}"""
        return runScriptSettings(core, data, sig, pubkeyHex, valuesJson, lang)
    }

    // Script-transport updates: the core's own subcommands (transport/script/update.go),
    // the same logic the mobile apps call in-process. The installed transport is the
    // JSON the updater builds; it is turned into flags here.
    override fun checkScriptUpdate(installedJson: String, channel: String): String =
        runUpdateCore("--check-script-update", installedJson, channel, null, false)

    override fun applyScriptUpdate(installedJson: String, channel: String, dir: String, allowWireBreak: Boolean): String =
        runUpdateCore("--apply-script-update", installedJson, channel, dir, allowWireBreak)

    override fun rollbackScript(installedJson: String, dir: String): String =
        runUpdateCore("--rollback-script", installedJson, "stable", dir, false)

    private fun runUpdateCore(op: String, installedJson: String, channel: String, dir: String?, allowWireBreak: Boolean): String {
        val core = binary.bundled() ?: return """{"status":"error","code":"unsupported"}"""
        return try {
            val o = kotlinx.serialization.json.Json.parseToJsonElement(installedJson) as kotlinx.serialization.json.JsonObject
            fun str(k: String) = (o[k] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
            val urls = (o["update"] as? kotlinx.serialization.json.JsonArray)?.joinToString(",") { (it as kotlinx.serialization.json.JsonPrimitive).content }.orEmpty()
            val args = buildList {
                add(core.absolutePath); add(op)
                add("--id=${str("id")}"); add("--file=${str("file")}"); add("--version=${str("version")}")
                add("--wire=${str("wire").ifBlank { "0" }}"); add("--pubkey=${str("pubkey")}")
                add("--update=$urls"); add("--channel=$channel")
                if (dir != null) add("--dir=$dir")
                if (allowWireBreak) add("--allow-wire-break")
            }
            val process = ProcessBuilder(args).redirectErrorStream(false).start()
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor(3, TimeUnit.MINUTES)
            output.trim().ifBlank { """{"status":"error","code":"fetch_failed"}""" }
        } catch (e: Exception) {
            """{"status":"error","code":"fetch_failed"}"""
        }
    }

    override fun scriptFingerprint(pubkeyHex: String): String = runCatching {
        val key = pubkeyHex.trim().replace(" ", "")
        val bytes = ByteArray(key.length / 2) { i -> ((hexDigit(key[i * 2]) shl 4) or hexDigit(key[i * 2 + 1])).toByte() }
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }.getOrDefault("")

    private fun hexDigit(c: Char): Int = Character.digit(c, 16).also { require(it >= 0) { "bad hex digit $c" } }

    override suspend fun fetchBytes(url: String): ByteArray? = withContext(Dispatchers.IO) {
        runCatching {
            val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).followRedirects(HttpClient.Redirect.NORMAL).build()
            val request = HttpRequest.newBuilder(URI(url)).header("User-Agent", "OpenFlux-Desktop").timeout(Duration.ofSeconds(20)).build()
            val response = http.send(request, HttpResponse.BodyHandlers.ofInputStream())
            // A transport package is a few KB; an answer past the cap is not one.
            if (response.statusCode() !in 200..299) return@runCatching null
            response.body().use { body -> body.readNBytes(MAX_FETCH_BYTES + 1).takeIf { it.size <= MAX_FETCH_BYTES } }
        }.getOrNull()
    }

    override suspend fun readBytes(pathOrUri: String): ByteArray? = withContext(Dispatchers.IO) {
        runCatching { File(pathOrUri).readBytes() }.getOrNull()
    }

    /** Runs `<core> --script-settings --data=<f> [--sig=<f>] --pubkey=<hex> --values=<json> --lang=<l>`, returning its JSON stdout as-is. */
    private fun runScriptSettings(core: File, data: ByteArray, sig: ByteArray, pubkeyHex: String, valuesJson: String, lang: String): String {
        val dataFile = File.createTempFile("ofx-script-", ".bin")
        val sigFile = if (sig.isNotEmpty()) File.createTempFile("ofx-script-", ".sig") else null
        return try {
            dataFile.writeBytes(data)
            sigFile?.writeBytes(sig)
            val args = buildList {
                add(core.absolutePath); add("--script-settings"); add("--data=${dataFile.absolutePath}")
                sigFile?.let { add("--sig=${it.absolutePath}") }
                add("--pubkey=${pubkeyHex.trim()}")
                if (valuesJson.isNotBlank()) add("--values=$valuesJson")
                add("--lang=$lang")
            }
            val process = ProcessBuilder(args).redirectErrorStream(false).start()
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor(15, TimeUnit.SECONDS)
            output.trim().ifBlank { """{"ok":false,"code":"failed","error":"ядро не ответило"}""" }
        } catch (e: Exception) {
            val msg = (e.message ?: "неизвестная ошибка").replace("\\", "\\\\").replace("\"", "\\\"")
            """{"ok":false,"code":"failed","error":"$msg"}"""
        } finally {
            dataFile.delete()
            sigFile?.delete()
        }
    }

    /** Runs `<core> --inspect-script --data=<f> [--sig=<f>] [--pubkey=<hex>]`, returning its JSON stdout as-is. */
    private fun runInspectScript(core: File, data: ByteArray, sig: ByteArray, pubkeyHex: String): String {
        val dataFile = File.createTempFile("ofx-script-", ".bin")
        val sigFile = if (sig.isNotEmpty()) File.createTempFile("ofx-script-", ".sig") else null
        return try {
            dataFile.writeBytes(data)
            sigFile?.writeBytes(sig)
            val args = buildList {
                add(core.absolutePath); add("--inspect-script"); add("--data=${dataFile.absolutePath}")
                sigFile?.let { add("--sig=${it.absolutePath}") }
                if (pubkeyHex.isNotBlank()) add("--pubkey=${pubkeyHex.trim()}")
            }
            val process = ProcessBuilder(args).redirectErrorStream(false).start()
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor(10, TimeUnit.SECONDS)
            output.trim().ifBlank { """{"ok":false,"signature":"unverified","error":"ядро не ответило"}""" }
        } catch (e: Exception) {
            val msg = (e.message ?: "неизвестная ошибка").replace("\\", "\\\\").replace("\"", "\\\"")
            """{"ok":false,"signature":"unverified","error":"$msg"}"""
        } finally {
            dataFile.delete()
            sigFile?.delete()
        }
    }

    companion object {
        /** Where the desktop releases are published, tagged v1.2.3. */
        const val RELEASE_REPO = "p1neappleXpress/OpenFluxDesktop"
        const val DESKTOP_TAG_PREFIX = "v"
        /** Nightly test builds are tagged nightly-<date>-<commit>, as prereleases. */
        const val NIGHTLY_TAG_PREFIX = "nightly-"
        /** The OpenFlux project's own script-signing key - see transport/script.OfficialKeyHex. Public; safe to duplicate. */
        /** Largest answer [fetchBytes] returns; a downloaded transport is a few KB. */
        const val MAX_FETCH_BYTES = 4 * 1024 * 1024
        const val OFFICIAL_SCRIPT_KEY = "d8bf9c958b994c2faab886cade5f28213f254911f87abe5e34756a289ae91354"
    }
}

/** Passed to a copy started by restartElevated: it waits for this one to exit. */
const val RELAUNCHED_ARG = "--relaunched"
