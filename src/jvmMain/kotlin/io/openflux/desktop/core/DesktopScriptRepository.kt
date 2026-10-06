package io.openflux.desktop.core

import io.openflux.desktop.data.AppDirs
import io.openflux.desktop.data.FileScriptRepository
import io.openflux.desktop.model.ScriptCarrierLookup
import io.openflux.desktop.service.PlatformServices
import java.io.File

/**
 * The desktop's registry of installed JS script transports. The core is only
 * a binary here, so reading a transport shells out to its `--inspect-script`
 * ([PlatformServices.inspectTransport], see JvmPlatformServices).
 */
class DesktopScriptRepository(platform: PlatformServices) :
    FileScriptRepository(File(AppDirs.config, "scripts"), platform::inspectTransport) {

    /** The on-disk file + pinned key for a carrier, resolved for CoreConfig; null when the script is gone. */
    fun carrier(id: String): ScriptCarrierLookup? = byId(id)?.let {
        ScriptCarrierLookup(path = File(dir, it.fileName).absolutePath, pubkeyHex = it.pubkeyHex, name = it.id, primaryParamKey = it.primaryParam?.key)
    }
}
