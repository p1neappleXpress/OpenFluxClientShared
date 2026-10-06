package io.openflux.desktop.core

import java.io.File

/**
 * What the core's L3 exit backend needs from the computer it runs on, said
 * before the core fails on its own: Windows needs administrator rights and
 * WinDivert (the DLL and its driver) beside the core, Linux needs root, and
 * macOS has no L3 exit in the core.
 */
internal object ExitL3 {
    val WINDIVERT_FILES = listOf("WinDivert.dll", "WinDivert64.sys")

    fun isRoot(): Boolean = System.getProperty("user.name") == "root"

    /** Why an L3 exit cannot start with the core at [core], in the user's words; null when it can. */
    fun problem(core: File, os: String = System.getProperty("os.name"), root: Boolean = isRoot()): String? {
        val name = os.lowercase()
        return when {
            name.contains("win") -> WINDIVERT_FILES.firstOrNull { !File(core.parentFile, it).isFile }?.let {
                "Рядом с ядром нет $it (${core.parentFile}): он нужен ноде L3. Выберите L4 или положите WinDivert рядом с ядром"
            }
            name.contains("linux") -> if (root) null else "Нода L3 на Linux работает только с правами root: запустите OpenFlux от root или выберите L4"
            else -> "Нода L3 на этой системе пока не реализована: выберите L4"
        }
    }

    /** Windows asks UAC for the core alone, as the full tunnel does, unless OpenFlux is elevated itself. */
    fun needsUac(os: String, appElevated: Boolean): Boolean = os.lowercase().contains("win") && !appElevated
}
