package io.openflux.app.servers

import io.openflux.desktop.model.NodeMode
import io.openflux.desktop.model.TransportType

/** How the new interface names a transport: in Russian, the same everywhere. */
val TransportType.title: String
    get() = when (this) {
        TransportType.VYANDEX -> "Яндекс Документ"
        TransportType.YANDEX -> "Яндекс Документ, старый способ"
        TransportType.MAILRU -> "Документ Mail.ru"
        TransportType.BOARDS -> "Яндекс Доска"
        TransportType.CUPSONLINE -> "Cups.online"
        TransportType.TELEMOST -> "Яндекс Телемост"
        TransportType.AVITO -> "Avito Code"
        TransportType.ONEME -> "MAX"
        TransportType.DIRECT -> "Прямое подключение"
        TransportType.SCRIPT -> "JS-транспорт"
    }

/** A node's transports in a few words: "Яндекс Документ, Mail.ru + прямое", or "только прямое". */
fun transportsLine(types: List<TransportType>): String {
    val names = types.filter { it != TransportType.DIRECT }.map {
        when (it) {
            TransportType.MAILRU -> "Mail.ru"
            TransportType.BOARDS -> "Доска"
            else -> it.title
        }
    }
    return if (names.isEmpty()) "только прямое" else names.joinToString(", ") + " + прямое"
}

/** What a node's mode means for the person, in one line. */
val NodeMode.short: String
    get() = when (this) {
        NodeMode.L4 -> "Подходит любому серверу, ничего от него не требует."
        NodeMode.L3 -> "Быстрее, пропускает UDP. Нужны iptables и systemd 231+. Пока нода работает, сервер не отправляет TCP-RST — это заметят другие сервисы на нём."
    }
