package io.openflux.desktop

import io.openflux.desktop.core.CoreBinary
import io.openflux.desktop.data.FileAccountRepository
import io.openflux.desktop.data.HttpSessionProbe
import io.openflux.desktop.model.AccountKind
import io.openflux.desktop.model.AppSettings
import io.openflux.desktop.node.CoreNodeWizard
import io.openflux.desktop.service.Accounts
import io.openflux.desktop.service.SettingsRepository
import io.openflux.desktop.web.KcefPage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import java.nio.file.Files
import io.openflux.desktop.web.KcefAccountBrowser
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test

/**
 * Opens every service's sign-in page in the real built-in browser (the
 * Chromium installed in %LOCALAPPDATA%/OpenFlux/browser) the way the
 * Accounts tab does; OPENFLUX_LIVE_BROWSER=1 runs it.
 */
class BrowserLiveTest {
    @Test
    fun signInPagesLoad() = runBlocking {
        if (System.getenv("OPENFLUX_LIVE_BROWSER") != "1") return@runBlocking
        val browser = KcefAccountBrowser()
        for (kind in AccountKind.entries.filter { it.signInUrl.startsWith("http") }) {
            browser.open(kind, kind.signInUrl, emptyMap()) { println("  step: $it") }
            val start = System.currentTimeMillis()
            while (System.currentTimeMillis() - start < 25_000 && (browser.loading || browser.url.isEmpty() || browser.url == "about:blank")) delay(250)
            delay(1500)
            val probe = withTimeoutOrNull(20_000) {
                runCatching {
                    browser.evaluate("JSON.stringify({ua: navigator.userAgent, title: document.title, text: (document.body && document.body.innerText || '').length})")
                }.getOrElse { "error: ${it.message}" }
            }
            println("$kind: ${System.currentTimeMillis() - start} ms url=${browser.url.take(90)} loading=${browser.loading}\n  $probe")
            browser.close()
        }
    }

    /** The node wizard's "create the document" step, signed out: it must show Yandex's sign-in page. */
    @Test
    fun nodeWizardDocumentPageLoads() = runBlocking {
        if (System.getenv("OPENFLUX_LIVE_BROWSER") != "1") return@runBlocking
        val accounts = Accounts(
            FileAccountRepository(Files.createTempDirectory("accounts").toFile()), KcefAccountBrowser(), HttpSessionProbe(),
            System::currentTimeMillis, CoroutineScope(SupervisorJob() + Dispatchers.Default),
        )
        val settings = object : SettingsRepository {
            override val settings = MutableStateFlow(AppSettings())
            override fun update(transform: (AppSettings) -> AppSettings) { settings.value = transform(settings.value) }
        }
        val wizard = CoreNodeWizard(settings, CoreBinary(), accounts)
        val job = async(Dispatchers.Default) { runCatching { wizard.createDocument("live-test") { println("  step: $it") } } }
        val start = System.currentTimeMillis()
        var url = ""
        while (System.currentTimeMillis() - start < 30_000) {
            val page = wizard.documentPage.value as? KcefPage
            url = page?.url.orEmpty()
            if (page != null && !page.loading && url.startsWith("https://")) break
            delay(250)
        }
        delay(1000)
        println("wizard page after ${System.currentTimeMillis() - start} ms: ${url.take(100)}")
        wizard.cancelDocument()
        println("createDocument ended: ${job.await().exceptionOrNull()?.message}")
        wizard.close()
    }
}
