package io.openflux.desktop.ui.accounts

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import io.openflux.desktop.model.AccountKind
import io.openflux.desktop.model.AuthStatus
import io.openflux.desktop.model.NodeDocuments
import io.openflux.desktop.model.Profile
import io.openflux.desktop.model.ProfileSource
import io.openflux.desktop.model.TransportType
import io.openflux.desktop.service.AccountException
import io.openflux.desktop.service.AppContainer
import io.openflux.desktop.ui.components.Tone
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** One service on the Accounts screen: its sign-in and how many profiles use it. */
data class AccountCard(val kind: AccountKind, val status: AuthStatus, val usedBy: Int)

/** A document just created, until the user puts it somewhere. */
data class CreatedDocument(val kind: AccountKind, val url: String)

class AccountsScreenModel(private val container: AppContainer) : ScreenModel {
    private val accounts = container.accounts

    val cards: StateFlow<List<AccountCard>> = combine(accounts.status, container.profiles.profiles) { status, profiles ->
        cardsOf(status, profiles)
    }.stateIn(screenModelScope, SharingStarted.Eagerly, cardsOf(accounts.status.value, container.profiles.profiles.value))

    var error by mutableStateOf<String?>(null)
    var created by mutableStateOf<CreatedDocument?>(null)

    private fun cardsOf(status: Map<AccountKind, AuthStatus>, profiles: List<Profile>) = AccountKind.entries.map { kind ->
        AccountCard(
            kind = kind,
            status = status[kind] ?: AuthStatus.SignedOut,
            usedBy = profiles.count { p -> p.carriers.any { AccountKind.of(it.type) == kind } },
        )
    }

    private fun act(block: suspend () -> Unit) {
        error = null
        screenModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: AccountException) {
                if (!e.cancelled) error = e.message
            } catch (e: Exception) {
                error = e.message ?: "Что-то пошло не так"
            }
        }
    }

    fun signIn(kind: AccountKind) = act { accounts.signIn(kind) }

    fun check(kind: AccountKind) = act { accounts.check(kind) }

    fun checkAll() = accounts.checkAllInBackground(force = true)

    fun signOut(kind: AccountKind) {
        if (created?.kind == kind) created = null
        accounts.signOut(kind)
    }

    fun createDocument(kind: AccountKind) = act {
        val name = NodeDocuments.fileName("", "", container.platform.now())
        created = CreatedDocument(kind, accounts.createDocument(kind, name))
    }

    fun cancel() = accounts.cancel()

    /** A new Session profile over the created document, with a fresh key; null when there is none. */
    fun createProfileFromDocument(): Profile? {
        val doc = created ?: return null
        val profile = Profile(
            id = container.profiles.newId(),
            name = "${doc.kind.label} ${doc.url.takeLast(6)}",
            icon = doc.kind.icon,
            // Volga is the Yandex carrier the core prefers; Mail.ru has one.
            transport = if (doc.kind == AccountKind.Mailru) TransportType.MAILRU else TransportType.VYANDEX,
            value = doc.url,
            secret = container.platform.newSecret(),
            session = true,
            source = ProfileSource.Manual,
            createdAt = container.platform.now(),
        )
        container.profiles.upsert(profile)
        created = null
        return profile
    }

    fun copyDocument() {
        created?.let { container.platform.setClipboardText(it.url) }
    }

    companion object {
        /** The status line and its colour. */
        fun statusText(status: AuthStatus, now: Long): Pair<String, Tone> = when (status) {
            AuthStatus.SignedOut -> "Вход не выполнен" to Tone.Neutral
            is AuthStatus.Checking -> "Проверяю вход…" to Tone.Accent
            is AuthStatus.SignedIn ->
                ("Вход выполнен" + who(status.login) + " · проверено " + ago(now - status.checkedAt)) to Tone.Success
            is AuthStatus.Expired -> ("Сессия истекла" + who(status.login)) to Tone.Danger
            is AuthStatus.NeedsCheck -> "Сервис просит проверку — войдите заново" to Tone.Warning
            is AuthStatus.Busy -> status.step to Tone.Accent
            is AuthStatus.Failed -> status.message to Tone.Warning
        }

        private fun who(login: String) = if (login.isBlank()) "" else ": $login"

        fun ago(millis: Long): String {
            val minutes = (millis / 60_000).coerceAtLeast(0)
            return when {
                minutes < 1 -> "только что"
                minutes < 60 -> "$minutes мин назад"
                minutes < 48 * 60 -> "${minutes / 60} ч назад"
                else -> "${minutes / (24 * 60)} дн назад"
            }
        }
    }
}
