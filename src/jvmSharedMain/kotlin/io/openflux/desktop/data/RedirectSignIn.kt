package io.openflux.desktop.data

import io.openflux.desktop.model.AuthAnswer
import io.openflux.desktop.model.BotException
import io.openflux.desktop.model.OpenFluxId
import io.openflux.desktop.service.SignInSupport
import io.openflux.desktop.service.SignInWay
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException

/**
 * The way back on Android: the sign-in page returns to [redirectUri] (the app's own scheme), the system hands the
 * address to the activity, and the activity gives it to [deliver]. Only a return carrying the waiting attempt's state
 * is handed over.
 */
class RedirectSignIn(
    override val clientId: String,
    private val redirectUri: String,
    override val issuer: String = OpenFluxId.ISSUER,
    private val open: (String) -> Unit,
) : SignInSupport {
    private var current: Way? = null

    override fun newAttempt() = Pkce.newAttempt()

    override suspend fun begin(state: String): SignInWay {
        val way = Way(state)
        val older = synchronized(this) { current.also { current = way } }
        older?.close()
        return way
    }

    override fun openPage(url: String) = open(url)

    private inner class Way(val state: String) : SignInWay {
        val arrivals = Channel<String>(Channel.CONFLATED)
        override val redirectUri: String get() = this@RedirectSignIn.redirectUri

        override suspend fun await(): String = try {
            arrivals.receive()
        } catch (e: ClosedReceiveChannelException) {
            throw BotException("superseded", "Вход начат заново, этот закрыт. Повторите вход")
        }

        override fun close() {
            arrivals.close()
            synchronized(this@RedirectSignIn) { if (current === this) current = null }
        }
    }

    /** The activity got [url]: true when it is the sign-in's way back (then nothing else should take it). */
    fun deliver(url: String): Boolean {
        val mine = url == redirectUri || url.startsWith("$redirectUri?") || url.startsWith("$redirectUri#")
        if (!mine) return false
        val way = synchronized(this) { current } ?: return true
        if (OpenFluxId.readReturn(url, way.state) !is AuthAnswer.Foreign) way.arrivals.trySend(url)
        return true
    }
}
