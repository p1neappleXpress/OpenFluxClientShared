package io.openflux.app.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.openflux.app.servers.ServerLink
import kotlinx.coroutines.CoroutineScope

/** Opens the ＋ menu (spec 7.1) from any screen. */
val LocalOpenAdd = staticCompositionLocalOf<() -> Unit> { {} }

/** The app's one SSH link to the person's servers. */
val LocalServerLink = staticCompositionLocalOf<ServerLink> { error("ServerLink is not provided") }

/** A screen's model that lives as long as the screen's entry in its stack; [close] when it leaves. */
class ScopedModel<T : Any>(make: (CoroutineScope) -> T) : ViewModel() {
    val value: T = make(viewModelScope)

    override fun onCleared() {
        (value as? AutoCloseable)?.close()
    }
}

/**
 * The model of the screen being shown, made once per entry (Navigation 3 scopes the store to the entry). Keyed by the
 * model's type: every ScopedModel is one class to the store, so a screen with two models would get the first twice.
 */
@Composable
inline fun <reified T : Any> rememberScreenModel(noinline make: (CoroutineScope) -> T): T =
    viewModel<ScopedModel<T>>(key = "screen-model:" + (T::class.qualifiedName ?: T::class.simpleName), initializer = { ScopedModel(make) }).value
