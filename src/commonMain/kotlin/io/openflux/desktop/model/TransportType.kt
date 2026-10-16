package io.openflux.desktop.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** What a transport's value field holds. */
enum class ValueKind { DocumentUrl, Address, Token }

/**
 * The carriers the OpenFlux core supports, named as its CLI and `openflux://`
 * links name them.
 */
@Serializable
enum class TransportType(
    val cliName: String,
    val label: String,
    val shortLabel: String,
    val kind: ValueKind,
    val valueHint: String,
    val icon: String,
) {
    @SerialName("vyandex")
    VYANDEX("vyandex", "Yandex Docs (Volga)", "Volga", ValueKind.DocumentUrl,
        "https://docs.yandex.ru/edit/d/…", "ic_yandex"),

    @SerialName("yandex")
    YANDEX("yandex", "Yandex Docs", "Yandex", ValueKind.DocumentUrl,
        "https://disk.yandex.ru/i/…", "ic_yandex"),

    @SerialName("boards")
    BOARDS("boards", "Yandex Board", "Board", ValueKind.DocumentUrl,
        "https://boards.yandex.ru/whiteboard/?hash=…", "ic_yandex"),

    @SerialName("mailru")
    MAILRU("mailru", "Mail.ru Docs", "Mail.ru", ValueKind.DocumentUrl,
        "https://cloud.mail.ru/public/…", "ic_mailru"),

    @SerialName("cupsonline")
    CUPSONLINE("cupsonline", "Cups.online", "Cups", ValueKind.DocumentUrl,
        "Код комнат от ноды", "ic_code"),

    /**
     * A Yandex Telemost conference: the tunnel rides as video through its SFU, striped over several
     * participants. Its media goes over WebRTC's own UDP sockets, which the desktop's full tunnel would
     * swallow, so the core refuses it there (see [CoreConfig]).
     */
    @SerialName("telemost")
    TELEMOST("telemost", "Яндекс Телемост", "Телемост", ValueKind.DocumentUrl,
        "https://telemost.yandex.ru/j/…", "ic_yandex"),

    /** Avito Code rooms: like cups.online, the node makes them and the link carries their packed list. */
    @SerialName("avito")
    AVITO("avito", "Avito Code", "Avito", ValueKind.DocumentUrl,
        "Код комнат от ноды", "ic_code"),

    @SerialName("oneme")
    ONEME("oneme", "MAX (OneMe)", "MAX", ValueKind.Token,
        "Токен MAX Web", "ic_max"),

    @SerialName("direct")
    DIRECT("direct", "Direct (TCP до ноды)", "Direct", ValueKind.Address,
        "host:port", "ic_link"),

    /**
     * A JS (goja) script transport. One enum member stands for every installed
     * script; which one a carrier uses is its [ExtraTransport.scriptId]. Runs
     * through the Session path so its on-disk path and pinned author key reach
     * the core as carrier params. The value field holds the script's primary
     * input (its first info().param, usually a document URL).
     */
    @SerialName("script")
    SCRIPT("script", "JS-транспорт", "JS", ValueKind.DocumentUrl,
        "см. параметры транспорта", "ic_code");

    /** Whether an `openflux://` link can carry it (MAX tokens are per account). */
    val shareable: Boolean get() = this != ONEME && this != SCRIPT

    /** The types this build can run from a link: what it tells the subscription service (X-OpenFlux-Transports). */
    val linkable: Boolean get() = shareable && this != DIRECT

    /** Needs an authenticated Session: no document to meet in, or (script) params ride the Session specs. */
    val sessionOnly: Boolean get() = this == DIRECT || this == SCRIPT

    companion object {
        fun fromCli(name: String): TransportType? = entries.firstOrNull { it.cliName == name }

        /** A Session carrier's name as the core reports it ("boards", "boards-2") for the UI: "Board", "Board 2". */
        fun carrierLabel(name: String): String {
            val type = fromCli(name.substringBefore('-')) ?: return name
            val n = name.substringAfter('-', "")
            return if (n.isEmpty()) type.shortLabel else "${type.shortLabel} $n"
        }
    }
}

@Serializable
enum class Codec(val cliName: String, val label: String) {
    @SerialName("batched") BATCHED("batched", "Быстрый (zstd)"),
    @SerialName("legacy") LEGACY("legacy", "Совместимый (LZ4)"),
}
