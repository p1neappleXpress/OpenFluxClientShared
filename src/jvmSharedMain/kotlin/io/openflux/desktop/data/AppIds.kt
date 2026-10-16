package io.openflux.desktop.data

import io.openflux.desktop.model.OpenFluxBot
import io.openflux.desktop.model.OpenFluxId
import java.net.URI

/**
 * What the build says about itself to the OpenFlux service: its app id (X-App-Id; an identifier and a switch for the
 * service, not a secret) and, for development, a local stand-in for the service's address.
 */
object AppIds {
    private const val RESOURCE = "/openflux-appid.txt"
    /** ofa_ is the name now; the service has issued ids as ofk_ so far and may keep doing so until it renames them. */
    private val SHAPE = Regex("^of[ak]_[A-Za-z0-9_-]{8,128}$")

    /** The app id if [text] has its shape, else blank. */
    fun valid(text: String?): String = text?.trim()?.takeIf(SHAPE::matches).orEmpty()

    /** The desktop build's app id: -Dopenflux.appId, then OPENFLUX_APP_ID, then the resource the build wrote; blank when none. */
    fun desktop(): String =
        valid(System.getProperty("openflux.appId"))
            .ifEmpty { valid(System.getenv("OPENFLUX_APP_ID")) }
            .ifEmpty { valid(AppIds::class.java.getResourceAsStream(RESOURCE)?.use { it.readBytes().decodeToString() }) }

    /** The service's address; OPENFLUX_API_BASE moves it, but only to this machine (a stand-in for tests). */
    fun apiBase(): String = pickBase(System.getenv("OPENFLUX_API_BASE"))

    internal fun pickBase(override: String?): String {
        val uri = runCatching { URI(override.orEmpty().trim()) }.getOrNull() ?: return OpenFluxBot.API
        val local = uri.scheme == "http" && (uri.host == "127.0.0.1" || uri.host == "localhost")
        return if (local) override!!.trim().trimEnd('/') else OpenFluxBot.API
    }

    /** The desktop build's Keycloak client: -Dopenflux.clientId, then OPENFLUX_CLIENT_ID (a stand), else ours. */
    fun desktopClientId(): String =
        System.getProperty("openflux.clientId")?.trim()?.takeIf { it.isNotEmpty() }
            ?: System.getenv("OPENFLUX_CLIENT_ID")?.trim()?.takeIf { it.isNotEmpty() }
            ?: OpenFluxId.DESKTOP_CLIENT

    /** The sign-in page's issuer; OPENFLUX_ID_ISSUER moves it, but only to this machine (a stand-in for tests). */
    fun idIssuer(): String = pickIssuer(System.getenv("OPENFLUX_ID_ISSUER"))

    internal fun pickIssuer(override: String?): String {
        val uri = runCatching { URI(override.orEmpty().trim()) }.getOrNull() ?: return OpenFluxId.ISSUER
        val local = uri.scheme == "http" && (uri.host == "127.0.0.1" || uri.host == "localhost")
        return if (local) override!!.trim().trimEnd('/') else OpenFluxId.ISSUER
    }
}
