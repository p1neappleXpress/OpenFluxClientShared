package io.openflux.desktop.core

/**
 * What the core's L3 exit backend needs from the computer it runs on, said
 * before the core fails on its own: Windows needs administrator rights (asked
 * through UAC) and WinDivert, which [WinDivertFiles] fetches when it is not
 * beside the core; Linux needs root, and macOS has no L3 exit in the core.
 */
internal object ExitL3 {
    fun isRoot(): Boolean = System.getProperty("user.name") == "root"

    /** Why an L3 exit cannot start here, in the user's words; null when it can. */
    fun problem(os: String = System.getProperty("os.name"), root: Boolean = isRoot()): String? {
        val name = os.lowercase()
        return when {
            name.contains("win") -> null
            name.contains("linux") -> if (root) null else "Нода L3 на Linux работает только с правами root: запустите OpenFlux от root или выберите L4"
            else -> "Нода L3 на этой системе пока не реализована: выберите L4"
        }
    }

    /** Windows asks UAC for the core alone, as the full tunnel does, unless OpenFlux is elevated itself. */
    fun needsUac(os: String, appElevated: Boolean): Boolean = os.lowercase().contains("win") && !appElevated
}
