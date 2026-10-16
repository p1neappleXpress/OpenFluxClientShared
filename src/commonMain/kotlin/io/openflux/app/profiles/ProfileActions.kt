package io.openflux.app.profiles

import io.openflux.desktop.model.Profile
import io.openflux.desktop.model.ProfileSource
import io.openflux.desktop.model.isActive
import io.openflux.desktop.model.managed
import io.openflux.desktop.model.profile
import io.openflux.desktop.service.AppContainer
import kotlinx.coroutines.CancellationException

/** What can be done with a profile from anywhere: the drawer, its screen, Share. */
class ProfileActions(private val container: AppContainer) {
    private val profiles = container.profiles
    private val connection = container.connection
    private val settings = container.settings

    /** A copy as a manual profile; null for a subscription's location. */
    fun duplicate(p: Profile): Profile? {
        if (p.managed) return null
        val copy = p.copy(id = profiles.newId(), name = "${p.name} (копия)", createdAt = container.platform.now(), source = ProfileSource.Manual)
        profiles.upsert(copy)
        return copy
    }

    /** Deletes [p] (not a subscription's), disconnecting first when it is the one connected. */
    fun delete(p: Profile) {
        if (p.managed) return
        val s = connection.state.value
        if (s.isActive && s.profile?.id == p.id) connection.disconnect()
        profiles.delete(p.id)
        if (settings.settings.value.selectedProfileId == p.id) settings.update { it.copy(selectedProfileId = null) }
    }

    fun connect(p: Profile) = io.openflux.app.shell.connectAsClient(container, p)

    /** The person runs this node: it is listed with their servers from now on. */
    fun markOwn(p: Profile) {
        if (p.managed) return
        profiles.upsert(p.copy(source = ProfileSource.Node))
    }

    /** Whether [p] can be given away by a link: not a subscription's, and every transport can be shared. */
    fun canShare(p: Profile): Boolean = !p.managed && p.transport.shareable

    /** The link the core makes for [p], or why it cannot be shared. */
    suspend fun link(p: Profile): Result<String> {
        if (p.managed) return Result.failure(IllegalStateException("Локациями подписки делиться нельзя"))
        val config = p.toShare().getOrElse { return Result.failure(it) }
        return try {
            Result.success(container.shareCodec.encode(config))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
