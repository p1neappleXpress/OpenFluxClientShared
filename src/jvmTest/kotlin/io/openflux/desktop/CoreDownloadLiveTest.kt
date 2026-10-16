package io.openflux.desktop

import io.openflux.desktop.core.CoreBinary
import io.openflux.desktop.model.AppSettings
import io.openflux.desktop.model.CoreSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** Downloads the real core release; OPENFLUX_LIVE=1 runs it. */
class CoreDownloadLiveTest {
    @Test
    fun downloadsTheCore() {
        if (System.getenv("OPENFLUX_LIVE") != "1") return
        val binary = CoreBinary()
        for (source in listOf(CoreSource.Release)) {
            val tag = binary.download(source)
            println("$source: $tag -> ${binary.downloaded(source)}")
            assertEquals(tag, binary.downloadedTag(source))
            val core = assertNotNull(binary.resolve(AppSettings(coreSource = source)))
            val out = ProcessBuilder(core.absolutePath, "--help").redirectErrorStream(true).start()
                .inputStream.bufferedReader().readText()
            println(out.lineSequence().take(2).joinToString(" | "))
        }
    }
}
