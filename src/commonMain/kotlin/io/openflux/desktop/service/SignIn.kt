package io.openflux.desktop.service

import io.openflux.desktop.model.AuthAttempt
import io.openflux.desktop.model.BotException
import io.openflux.desktop.model.OpenFluxId

/**
 * What a platform gives the sign-in on the OpenFlux page: its Keycloak client, a way back from the browser and a way
 * to show the page (a Custom Tab on Android, the default browser on a computer; never a WebView).
 */
interface SignInSupport {
    /** This build's Keycloak client; blank: the build cannot sign in. */
    val clientId: String
    val issuer: String get() = OpenFluxId.ISSUER
    fun newAttempt(): AuthAttempt
    /**
     * Readies the way back for the attempt with this [state] and ends any earlier one (its [SignInWay.await] fails).
     * BotException("no_return") when there is none.
     */
    suspend fun begin(state: String): SignInWay
    fun openPage(url: String)
}

/** One attempt's way back from the browser. Each attempt owns its own: closing one never touches another. */
interface SignInWay {
    /** The redirect_uri to send. */
    val redirectUri: String
    /**
     * Waits for the browser to come back with this attempt's state: the full address with its query.
     * Fails with BotException("superseded") when a newer sign-in began, "closed" when this way was closed.
     */
    suspend fun await(): String
    /** Ends this way back only (a computer stops listening); harmless twice. */
    fun close()
}

object NoSignIn : SignInSupport {
    private fun unsupported(): Nothing = throw BotException("unsupported", "Вход здесь не поддерживается")
    override val clientId = ""
    override fun newAttempt() = unsupported()
    override suspend fun begin(state: String) = unsupported()
    override fun openPage(url: String) = Unit
}
