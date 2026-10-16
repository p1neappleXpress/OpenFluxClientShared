package io.openflux.app.nav

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** The places of the app (spec 4.1); ＋ is a menu, not a place. */
@Serializable
enum class Section(val title: String) {
    Home("Главная"),
    Servers("Серверы"),
    Share("Поделиться"),
    Settings("Настройки"),
}

/**
 * A screen. Every screen belongs to one section (spec 4.2): opening it from anywhere goes there, so "back"
 * always walks the same way. Parameters are ids, never objects: the stacks are saved and restored.
 */
@Serializable
sealed interface Dest {
    val owner: Section

    /** True for the first screen of a section. */
    val isRoot: Boolean get() = false

    /** The profile this screen shows; a screen whose profile is gone is dropped. */
    val profileId: String? get() = null

    /** The server ("host:port:user") this screen shows; a screen of a forgotten server is dropped. */
    val serverId: String? get() = null

    @Serializable @SerialName("home")
    data object Home : Dest {
        override val owner get() = Section.Home
        override val isRoot get() = true
    }

    @Serializable @SerialName("servers")
    data object Servers : Dest {
        override val owner get() = Section.Servers
        override val isRoot get() = true
    }

    @Serializable @SerialName("share")
    data object Share : Dest {
        override val owner get() = Section.Share
        override val isRoot get() = true
    }

    @Serializable @SerialName("settings")
    data object Settings : Dest {
        override val owner get() = Section.Settings
        override val isRoot get() = true
    }

    /** A profile's details (spec 6.2). */
    @Serializable @SerialName("profile")
    data class Profile(val id: String) : Dest {
        override val owner get() = Section.Home
        override val profileId get() = id
    }

    /** A profile's fields; [id] null for a new one (spec 6.3). */
    @Serializable @SerialName("profile-edit")
    data class ProfileEdit(val id: String? = null) : Dest {
        override val owner get() = Section.Home
        override val profileId get() = id
    }

    /**
     * A key, a link or a file to add (spec 7.1). No text here: the stacks are saved to the settings file, and a key
     * or a link carries a secret. Text from outside comes through AppContainer.pendingKey, in memory.
     */
    @Serializable @SerialName("add-key")
    data object AddKey : Dest {
        override val owner get() = Section.Home
    }

    /** The same as [AddKey], starting with the camera (spec 7.1, "Сканировать QR"). */
    @Serializable @SerialName("scan")
    data object Scan : Dest {
        override val owner get() = Section.Home
    }

    /** Signing in to the OpenFlux account (spec 8.1). */
    @Serializable @SerialName("auth")
    data object Auth : Dest {
        override val owner get() = Section.Settings
    }

    /** The subscription (spec 8.2). */
    @Serializable @SerialName("account")
    data object Account : Dest {
        override val owner get() = Section.Settings
    }

    /** One of the person's servers (spec 5.2). */
    @Serializable @SerialName("server")
    data class Server(val id: String) : Dest {
        override val owner get() = Section.Servers
        override val serverId get() = id
    }

    /** A node on a server (spec 5.3). */
    @Serializable @SerialName("node")
    data class Node(val server: String, val channel: String) : Dest {
        override val owner get() = Section.Servers
        override val serverId get() = server
    }

    /** Connecting to a server not yet in the list (spec 5.1). No serverId: it has none until it connects. */
    @Serializable @SerialName("add-server")
    data object AddServer : Dest {
        override val owner get() = Section.Servers
    }

    /** A new node on a connected server; [quick] installs a direct-only node at once (spec 5.1). */
    @Serializable @SerialName("new-node")
    data class NewNode(val server: String, val quick: Boolean = false) : Dest {
        override val owner get() = Section.Servers
        override val serverId get() = server
    }

    /** A node on an ordinary web hosting, made without a server (spec 5.4). */
    @Serializable @SerialName("new-hosting")
    data object NewHosting : Dest {
        override val owner get() = Section.Servers
    }

    /** A hosting node, by its profile (spec 5.4). */
    @Serializable @SerialName("hosting")
    data class Hosting(val profile: String) : Dest {
        override val owner get() = Section.Servers
        override val profileId get() = profile
    }

    /** This device as an exit node for others (spec 5.5). */
    @Serializable @SerialName("this-device")
    data object ThisDevice : Dest {
        override val owner get() = Section.Servers
    }

    /** The catalogue of JS transports (spec 8.5). */
    @Serializable @SerialName("scripts")
    data object Scripts : Dest {
        override val owner get() = Section.Settings
    }

    /** The journal (spec 8.4). */
    @Serializable @SerialName("logs")
    data object Logs : Dest {
        override val owner get() = Section.Settings
    }

    /** Giving one profile away: QR and link (spec 7.2). */
    @Serializable @SerialName("give")
    data class Give(val profile: String) : Dest {
        override val owner get() = Section.Share
        override val profileId get() = profile
    }

    /** A page of Settings by its name (spec 8.3). */
    @Serializable @SerialName("settings-page")
    data class SettingsPage(val page: String) : Dest {
        override val owner get() = Section.Settings
    }
}

fun rootOf(section: Section): Dest = when (section) {
    Section.Home -> Dest.Home
    Section.Servers -> Dest.Servers
    Section.Share -> Dest.Share
    Section.Settings -> Dest.Settings
}

private val json = Json { ignoreUnknownKeys = true; classDiscriminator = "type" }

/** Where the person is, saved: the section and each section's screens, first to last. */
@Serializable
data class NavState(
    val section: Section = Section.Home,
    val stacks: Map<Section, List<Dest>> = Section.entries.associateWith { listOf(rootOf(it)) },
) {
    fun encode(): String = json.encodeToString(serializer(), this)

    companion object {
        /** Null for nothing saved, a broken string, or a screen this version does not know. */
        fun decode(text: String?): NavState? =
            if (text.isNullOrBlank()) null
            else runCatching { json.decodeFromString(serializer(), text) }.getOrNull()
    }
}
