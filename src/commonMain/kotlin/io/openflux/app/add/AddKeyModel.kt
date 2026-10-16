package io.openflux.app.add

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.openflux.app.nav.Dest
import io.openflux.desktop.model.Profile
import io.openflux.desktop.model.ProfileSource
import io.openflux.desktop.model.ShareConfig
import io.openflux.desktop.model.ShareLinkCodec
import io.openflux.desktop.model.SubStatus
import io.openflux.desktop.model.SubscriptionInvite
import io.openflux.desktop.service.AppContainer
import io.openflux.desktop.service.KeyPreview
import io.openflux.desktop.model.SubscriptionException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import io.openflux.desktop.model.BotException
import io.openflux.desktop.model.OpenFluxBot
import io.openflux.desktop.service.BotAccountManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** What the pasted text turned out to be. */
sealed interface KeyResult {
    data object Empty : KeyResult
    data object Checking : KeyResult
    data class Subscription(val preview: KeyPreview) : KeyResult {
        val canAdd: Boolean get() = preview.document.status == SubStatus.ACTIVE
    }
    data class Profile(val config: ShareConfig, val link: String) : KeyResult
    /** A connection link from the OpenFlux bot: what it resolved to now. */
    data class Bot(val link: String, val connection: io.openflux.desktop.service.BotConnection) : KeyResult
    data class Invalid(val message: String) : KeyResult
}

/**
 * "Ключ, ссылка или файл" (spec 7.1): one field that reads a subscription key or an `openflux://` profile link,
 * from typing, the clipboard, a file or a QR code; a short preview; one button. Text opened from outside comes
 * through [AppContainer.pendingKey] and is taken once.
 */
@Stable
class AddKeyModel(private val container: AppContainer, private val scope: CoroutineScope) {
    var text by mutableStateOf("")
        private set
    var result by mutableStateOf<KeyResult>(KeyResult.Empty)
        private set
    var busy by mutableStateOf(false)
        private set
    private var source = ProfileSource.Link
    private var job: Job? = null

    init {
        container.pendingKey.value?.let { onText(it) }
        container.pendingKey.value = null
    }

    fun onText(value: String, from: ProfileSource = ProfileSource.Link) {
        text = value
        source = from
        job?.cancel()
        val t = firstLine(value)
        result = when {
            t.isEmpty() -> KeyResult.Empty
            t.startsWith(ShareLinkCodec.PREFIX) || SubscriptionInvite.looksLike(t) || OpenFluxBot.looksLike(t) -> KeyResult.Checking
            else -> KeyResult.Invalid("Это не ключ подписки, не ссылка openflux:// и не ссылка из бота")
        }
        if (result !is KeyResult.Checking) return
        job = scope.launch {
            delay(250) // typing, not pasting: wait for the end of it
            result = try {
                when {
                    OpenFluxBot.looksLike(t) -> KeyResult.Bot(t, container.botApi.resolve(OpenFluxBot.connectToken(t)!!))
                    SubscriptionInvite.looksLike(t) -> KeyResult.Subscription(container.subscription.previewKey(t))
                    else -> KeyResult.Profile(container.shareCodec.decode(t), t)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: SubscriptionException) {
                KeyResult.Invalid(e.message ?: "Не удалось проверить ключ")
            } catch (e: BotException) {
                KeyResult.Invalid(if (e.notFound) BotAccountManager.GONE else e.message ?: "Бот не ответил")
            } catch (e: Exception) {
                KeyResult.Invalid(e.message ?: "Не удалось прочитать ссылку")
            }
        }
    }

    fun paste() {
        val clip = container.platform.clipboardText().orEmpty().trim()
        if (clip.isEmpty()) { text = ""; result = KeyResult.Invalid("В буфере обмена пусто"); return }
        onText(clip)
    }

    fun fromFile() {
        scope.launch {
            val path = container.platform.pickFile("Файл с ключом или ссылкой", listOf("txt", "json", "conf", "openflux", "key")) ?: return@launch
            val t = container.platform.readTextFile(path)?.trim()
            if (t.isNullOrEmpty()) result = KeyResult.Invalid("Файл пустой или слишком большой") else onText(t)
        }
    }

    /** A QR code: the camera where there is one, else a picture file, else the clipboard's picture. */
    fun fromQr() {
        val platform = container.platform
        scope.launch {
            val t = try {
                if (platform.cameraScanSupported) platform.scanQr()
                else platform.pickFile("QR-код", listOf("png", "jpg", "jpeg", "bmp", "gif"))?.let(platform::qrFromFile)
                    ?: if (platform.clipboardImageSupported) platform.qrFromClipboardImage() else null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            if (t.isNullOrBlank()) result = KeyResult.Invalid("QR-код не найден") else onText(t.trim(), ProfileSource.Qr)
        }
    }

    /** Adds what was read; where to go next (the new profile, or the subscription), or null. */
    fun add(): Dest? = when (val r = result) {
        is KeyResult.Profile -> {
            val p = Profile.fromShare(r.config, container.profiles.newId(), container.platform.now(), source)
            container.profiles.upsert(p)
            container.settings.update { it.copy(selectedProfileId = p.id) }
            Dest.Profile(p.id)
        }
        is KeyResult.Bot -> {
            busy = true
            scope.launch {
                try {
                    val p = container.bot.addLink(r.link)
                    container.settings.update { it.copy(selectedProfileId = p.id) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    result = KeyResult.Invalid(e.message ?: "Не удалось добавить подключение из бота")
                } finally {
                    busy = false
                }
            }
            Dest.Account
        }
        is KeyResult.Subscription -> {
            if (!r.canAdd) null
            else {
                busy = true
                scope.launch {
                    try {
                        container.subscription.addKey(r.preview)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        result = KeyResult.Invalid(e.message ?: "Не удалось подключить подписку")
                    } finally {
                        busy = false
                    }
                }
                Dest.Account
            }
        }
        else -> null
    }

    private fun firstLine(t: String) = t.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty()
}
