package io.openflux.desktop.service

import io.openflux.desktop.model.AccountKind
import io.openflux.desktop.ui.BrowserPage
import kotlinx.coroutines.flow.StateFlow

/** What a request made with a saved session says about it. */
enum class ProbeResult { SignedIn, Expired, NeedsCheck, Offline }

/** Asks the service, without a browser, whether a saved session still works. */
interface SessionProbe {
    suspend fun probe(kind: AccountKind, cookies: Map<String, String>): ProbeResult
}

/**
 * The built-in browser (desktop) or a WebView (Android), as [Accounts]
 * needs it: one page at a time, its cookies wiped when it closes.
 */
interface AccountBrowser {
    val page: StateFlow<BrowserPage?>
    val url: String
    val loading: Boolean
    val closed: Boolean

    /** Opens [url] in a fresh browser with only [cookies] set for [kind]'s domain. */
    suspend fun open(kind: AccountKind, url: String, cookies: Map<String, String>, onStep: (String) -> Unit)

    suspend fun evaluate(script: String): String

    /** Sends the open page to [url]. */
    fun load(url: String)

    /** Every cookie of [kind] the browser holds now, the most specific domain winning. */
    suspend fun cookies(kind: AccountKind): Map<String, String>

    /** Closes the page and wipes the browser's cookies. */
    fun close()
}

/**
 * A failed sign-in or document; [expired] means the saved session no
 * longer works, [cancelled] that the user closed the page.
 */
class AccountException(message: String, val expired: Boolean = false, val cancelled: Boolean = false) : Exception(message)
