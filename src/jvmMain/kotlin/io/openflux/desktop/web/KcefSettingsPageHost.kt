package io.openflux.desktop.web

import io.openflux.desktop.service.SettingsPageHost
import io.openflux.desktop.ui.BrowserPage

/** Shows a script's settings wizard in the built-in browser (KCEF), answered on its own channel. */
object KcefSettingsPageHost : SettingsPageHost {
    override suspend fun open(html: String, onStep: (String) -> Unit, onSubmit: (String) -> Unit): BrowserPage =
        BuiltInBrowser.openHtml(html, onStep, onSubmit)

    override fun close(page: BrowserPage) {
        (page as? KcefPage)?.close()
    }
}
