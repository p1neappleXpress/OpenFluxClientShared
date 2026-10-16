package io.openflux.desktop.service

import io.openflux.desktop.model.AppAsset
import io.openflux.desktop.model.AppRelease
import io.openflux.desktop.model.ReleaseFeed
import io.openflux.desktop.model.UpdateState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The app's own updates from its releases on GitHub: looks for a newer release now and then, and, when the
 * person agrees, downloads the installer of this system, checks it against the release's SHA256SUMS.txt
 * and starts it. Nothing is installed without the checksum, and nothing without a tap on "Обновить".
 */
class AppUpdates(
    private val platform: PlatformServices,
    private val settings: SettingsRepository,
    private val scope: CoroutineScope,
    /** Pause before the first check and between checks, ms. */
    private val firstDelayMs: Long = 8_000,
    private val everyMs: Long = 6 * 60 * 60 * 1000L,
) {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state

    private val lock = Mutex()
    private var loop: Job? = null
    private var installing: Job? = null

    /** Looks for updates a few seconds after the start and every few hours, while "автоматически" is on. */
    fun start() {
        if (loop != null) return
        loop = scope.launch {
            delay(firstDelayMs)
            while (true) {
                if (settings.settings.value.autoUpdate) check(manual = false)
                delay(everyMs)
            }
        }
    }

    /**
     * Asks GitHub for the newest release. A check the person did not ask for stays silent when nothing is new,
     * when the release is the one they skipped, or when it fails.
     */
    suspend fun check(manual: Boolean) {
        val busy = _state.value
        if (busy is UpdateState.Downloading || busy is UpdateState.Installing) return
        lock.withLock {
            if (manual) _state.value = UpdateState.Checking
            val body = try {
                platform.httpText("https://api.github.com/repos/${platform.clientRepo}/releases?per_page=15")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = if (manual) UpdateState.Failed("Не удалось проверить обновления: ${e.message ?: "нет связи"}", null) else UpdateState.Idle
                return
            }
            settings.update { it.copy(updateCheckedAt = platform.now()) }
            val newer = ReleaseFeed.newer(ReleaseFeed.releases(body), platform.appVersion)
            _state.value = when {
                newer == null -> if (manual) UpdateState.UpToDate else UpdateState.Idle
                !manual && newer.tag == settings.settings.value.skippedUpdate -> UpdateState.Idle
                else -> UpdateState.Available(newer, if (platform.updateInstallSupported) platform.updateAsset(newer.assets) else null)
            }
        }
    }

    /** Downloads the installer, checks it and starts it. */
    fun install() {
        val offered = _state.value as? UpdateState.Available ?: (_state.value as? UpdateState.Failed)?.release?.let { release ->
            UpdateState.Available(release, if (platform.updateInstallSupported) platform.updateAsset(release.assets) else null)
        } ?: return
        val release = offered.release
        val asset = offered.asset
        if (asset == null) {
            // Nothing installable for this system: the page of the release.
            platform.openUrl(release.pageUrl.ifEmpty { "https://github.com/${platform.clientRepo}/releases/latest" })
            return
        }
        installing?.cancel()
        installing = scope.launch { download(release, asset) }
    }

    private suspend fun download(release: AppRelease, asset: AppAsset) {
        _state.value = UpdateState.Downloading(release, 0, asset.size)
        try {
            val sums = release.checksums ?: throw IllegalStateException("В релизе нет ${AppRelease.CHECKSUMS_NAME}: без контрольной суммы не устанавливаю")
            val expected = ReleaseFeed.checksums(platform.httpText(sums.url))[asset.name]
                ?: throw IllegalStateException("В ${AppRelease.CHECKSUMS_NAME} нет суммы для ${asset.name}")
            val path = platform.downloadUpdate(asset, expected) { done, total ->
                _state.value = UpdateState.Downloading(release, done, if (total > 0) total else asset.size)
            }
            _state.value = UpdateState.Installing(release, null)
            val result = platform.installUpdate(path)
            _state.value = if (result.started) UpdateState.Installing(release, result.message)
            else UpdateState.Failed(result.message ?: "Не удалось запустить установку", release)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.value = UpdateState.Failed(e.message ?: "Не удалось скачать обновление", release)
        }
    }

    /** "Пропустить эту версию": the banner goes and does not come back for this release. */
    fun skip() {
        val release = when (val s = _state.value) {
            is UpdateState.Available -> s.release
            is UpdateState.Failed -> s.release
            else -> null
        } ?: return
        settings.update { it.copy(skippedUpdate = release.tag) }
        _state.value = UpdateState.Idle
    }

    /** "Позже": hides the banner until the next check. */
    fun dismiss() {
        if (_state.value !is UpdateState.Downloading) _state.value = UpdateState.Idle
    }
}
