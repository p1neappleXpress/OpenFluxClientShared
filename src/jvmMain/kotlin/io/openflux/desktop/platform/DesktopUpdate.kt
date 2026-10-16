package io.openflux.desktop.platform

import io.openflux.desktop.model.AppAsset

/** Which file of a release this computer installs, and how the installer is started. */
object DesktopUpdate {
    /**
     * The installer of a release for [os] (the `os.name` property, lower-cased) and [arch] (`os.arch`).
     * Windows: the setup program. macOS: the disk image of the CPU. Linux: the .deb, where dpkg is there;
     * anything else gets no file, and the release's page is opened.
     */
    fun pick(assets: List<AppAsset>, os: String, arch: String, hasDpkg: Boolean): AppAsset? {
        val suffix = when {
            os.contains("win") -> "-windows-x64-setup.exe"
            os.contains("mac") -> if (arch.contains("aarch64") || arch.contains("arm64")) "-macos-arm64.dmg" else "-macos-x64.dmg"
            os.contains("linux") -> if (hasDpkg && (arch.contains("amd64") || arch.contains("x86_64"))) "-linux-x64.deb" else return null
            else -> return null
        }
        return assets.firstOrNull { it.name.startsWith("OpenFlux-") && it.name.endsWith(suffix) }
    }

    /** What the person does after the installer opens, for the systems where the app does not quit by itself. */
    fun afterOpen(os: String): String? = when {
        os.contains("win") -> null
        os.contains("mac") -> "Откройте образ и перетащите OpenFlux в «Программы», затем запустите заново."
        else -> "Откроется установщик пакетов. После установки запустите OpenFlux заново."
    }
}
