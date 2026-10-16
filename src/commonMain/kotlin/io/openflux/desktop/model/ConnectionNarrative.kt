package io.openflux.desktop.model

/** One journal line in plain words: what it says and how important it is. */
data class Narrated(val level: LogLevel, val text: String)

/**
 * What the journal tells a person who is not reading the core's output: a link that is up is said apart from a
 * node that is proven to carry traffic, and a failure says what it means and where to look. The core's own lines
 * stay in the journal as they are; these come next to them.
 */
object ConnectionNarrative {
    /** The core says the carrier reaches the node. Not yet a proof that traffic passes. */
    fun linkUp(mode: ConnectionMode, carriers: List<String>): Narrated =
        if (mode == ConnectionMode.Exit) {
            Narrated(LogLevel.Success, "Нода запущена и ждёт клиентов")
        } else {
            val via = carriers.filter { it.isNotBlank() }.joinToString(", ")
            Narrated(LogLevel.Info, "Канал до ноды поднят${if (via.isNotEmpty()) " (через $via)" else ""}. Проверяю, что трафик идёт через неё…")
        }

    /** Android's VPN keeps this app's own traffic out of the tunnel: the app cannot prove the node, so it says how to. */
    fun linkUpUnchecked(carriers: List<String>): Narrated {
        val via = carriers.filter { it.isNotBlank() }.joinToString(", ")
        return Narrated(
            LogLevel.Success,
            "Канал до ноды поднят${if (via.isNotEmpty()) " (через $via)" else ""}. Проверить адрес из приложения нельзя: откройте в браузере api.ipify.org, там должен быть адрес ноды",
        )
    }

    /** The check through the tunnel answered: the traffic really leaves from the node. */
    fun nodeConfirmed(ip: String, millis: Long): Narrated =
        Narrated(LogLevel.Success, "Нода подтверждена: трафик идёт через неё, внешний адрес $ip (ответ за ${seconds(millis)} с)")

    /** The link is up, the check through it is not: the lines say what that means and what to look at. */
    fun nodeNotConfirmed(rawReason: String?): List<Narrated> = listOf(
        Narrated(LogLevel.Warning, "Канал до ноды поднят, но трафик через неё не проходит: ${explainCheckFailure(rawReason)}"),
        Narrated(
            LogLevel.Warning,
            "Что проверить: запущена ли нода и не закончился ли у неё срок, есть ли интернет на этом компьютере, не нужна ли " +
                "проверка (капча) в окне приложения. Если не помогает, нажмите «Отключить» и подключитесь заново",
        ),
    )

    /** A plain reason for a failed check through the tunnel; [raw] is the exception text, may be empty. */
    fun explainCheckFailure(raw: String?): String {
        val lower = raw.orEmpty().lowercase()
        return when {
            lower.isBlank() -> "нет ответа"
            "timed out" in lower || "timeout" in lower || "deadline" in lower ->
                "нет ответа за отведённое время. Нода не отвечает или канал перегружен"
            "refused" in lower || "connection reset" in lower ->
                "локальный прокси ядра закрыт или нода сбросила соединение"
            "unknownhost" in lower || "no such host" in lower || "nodename nor servname" in lower || "name resolution" in lower ->
                "не находятся адреса сайтов (DNS через туннель не работает)"
            "ssl" in lower || "certificate" in lower || "pkix" in lower || "handshake" in lower ->
                "ошибка защищённого соединения (TLS): трафик по пути могут подменять"
            "unreachable" in lower || "no route" in lower -> "нет маршрута до ноды (проверьте интернет)"
            "неожиданный ответ" in lower -> "сервис проверки адреса ответил не так, как ждали (возможно, страница-заглушка)"
            else -> raw!!.trim().take(160)
        }
    }

    /** A line of the core's output that a person can act on, said plainly; null for the ones that are only detail. */
    fun readCoreLine(line: String): Narrated? {
        val lower = line.lowercase()
        return when {
            "authenticated peer" in lower ->
                Narrated(LogLevel.Success, "Нода ответила и подтвердила ключ шифрования: канал защищён")
            "transport encryption: aes-256-gcm enabled" in lower ->
                Narrated(LogLevel.Info, "Шифрование включено (AES-256-GCM)")
            "falling back to classic" in lower ->
                Narrated(LogLevel.Warning, "Нода не ответила на новое рукопожатие, ядро перешло на классический режим. Для старой ноды это нормально; если связи нет, нода выключена или недоступна")
            "tunnel active" in lower ->
                Narrated(LogLevel.Success, "Весь трафик компьютера теперь идёт через туннель")
            "running as exit node" in lower ->
                Narrated(LogLevel.Info, "Ядро запущено как нода и ждёт клиентов")
            "running as client" in lower ->
                Narrated(LogLevel.Info, "Ядро запущено как клиент и ждёт ответа ноды")
            "captcha required" in lower ->
                Narrated(LogLevel.Warning, "Площадка просит пройти проверку (капча). Откройте окно проверки в приложении: пока её не пройти, связи не будет")
            else -> null
        }
    }

    /** A plain reason for a fatal line of the core that [CoreConnectionService.friendlyProblem] has no rule for. */
    fun explainFatal(line: String): String? {
        val lower = line.lowercase()
        if (!(lower.contains("[error]") || lower.contains("fatal"))) return null
        return when {
            "connection refused" in lower -> "Нода отказала в соединении: она выключена или её порт закрыт"
            "i/o timeout" in lower || "deadline exceeded" in lower -> "Нода не отвечает: время ожидания вышло. Проверьте, что нода запущена и есть интернет"
            "no such host" in lower -> "Не находится адрес ноды или сервиса (DNS). Проверьте интернет и адрес"
            "x509" in lower || "certificate" in lower -> "Ошибка сертификата при защищённом соединении: проверьте дату на компьютере и сеть"
            "401" in lower || "403" in lower || "unauthorized" in lower -> "Площадка отклонила вход (401/403): войдите в аккаунт площадки заново"
            else -> null
        }
    }

    private fun seconds(millis: Long): String {
        val tenths = (millis / 100).coerceAtLeast(1)
        return "${tenths / 10}.${tenths % 10}"
    }
}
