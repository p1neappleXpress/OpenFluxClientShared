package io.openflux.desktop.core

import io.openflux.desktop.data.AppDirs
import io.openflux.desktop.data.FileScriptRepository
import io.openflux.desktop.data.ShippedScript
import io.openflux.desktop.model.ScriptCarrierLookup
import io.openflux.desktop.service.PlatformServices
import java.io.File

/**
 * The desktop's registry of installed JS script transports. The core is only
 * a binary here, so reading a transport shells out to its `--inspect-script`
 * ([PlatformServices.inspectTransport], see JvmPlatformServices).
 */
class DesktopScriptRepository(private val platform: PlatformServices) :
    FileScriptRepository(File(AppDirs.config, "scripts"), platform::inspectTransport) {

    /** The on-disk file + pinned key for a carrier, resolved for CoreConfig; null when the script is gone. */
    fun carrier(id: String): ScriptCarrierLookup? = byId(id)?.let {
        ScriptCarrierLookup(path = File(dir, it.fileName).absolutePath, pubkeyHex = it.pubkeyHex, name = it.id, primaryParamKey = it.primaryParam?.key)
    }

    /**
     * Installs the official transports shipped with the app (resources/scripts, signed .flux
     * packages from OpenFluxTransports) or upgrades the installed copies of them. Not at
     * construction: it runs the core once per package, so it waits for the user to turn the
     * experimental features on.
     */
    fun syncBundled() {
        syncShipped(shipped(), platform.officialScriptKey)
    }

    /** The packages shipped next to the bundled core, in the app's resources folder. */
    private fun shipped(): List<ShippedScript> {
        val dirs = listOfNotNull(
            System.getProperty("compose.application.resources.dir")?.let { File(it, "scripts") },
            File(System.getProperty("user.dir"), "resources/common/scripts"),
            File(System.getProperty("user.dir"), "desktopApp/resources/common/scripts"),
        )
        val folder = dirs.firstOrNull { it.isDirectory } ?: return emptyList()
        return folder.listFiles { f -> f.isFile && f.name.endsWith(".flux") }.orEmpty().sortedBy { it.name }
            .mapNotNull { f -> runCatching { ShippedScript(f.name, f.readBytes()) }.getOrNull() }
    }
}
