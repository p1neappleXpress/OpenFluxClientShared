package io.openflux.app.nav

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.rememberDecoratedNavEntries
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.scene.SinglePaneSceneStrategy
import androidx.navigation3.ui.NavDisplay
import io.openflux.app.theme.AppTheme

/**
 * Shows the screens [navigator] holds. Every section's stack is decorated on its own and always, so a section
 * out of sight keeps its screens' saved state and models (a server's SSH session survives a look at Settings);
 * a screen taken off its stack loses them. The display gets Home's screens and, above them, the current
 * section's: back from a section's first screen lands on Home, and the predictive back gesture shows it.
 */
@Composable
fun AppNavHost(
    navigator: AppNavigator,
    wide: Boolean,
    modifier: Modifier = Modifier,
    nothingChosen: @Composable (Section) -> Unit = {},
    entry: (Dest) -> NavEntry<Dest>,
) {
    val decorators = listOf(
        rememberSaveableStateHolderNavEntryDecorator<Dest>(),
        rememberViewModelStoreNavEntryDecorator<Dest>(),
    )
    val decorated = Section.entries.associateWith { s ->
        key(s) { rememberDecoratedNavEntries(navigator.stack(s), decorators, entry) }
    }
    val home = decorated.getValue(Section.Home)
    val entries = if (navigator.section == Section.Home) home else home + decorated.getValue(navigator.section)
    NavDisplay(
        entries = entries,
        modifier = modifier,
        sceneStrategies = listOf(ListDetailSceneStrategy(wide, nothingChosen), SinglePaneSceneStrategy()),
        onBack = { navigator.back() },
        transitionSpec = { forward() },
        popTransitionSpec = { backward() },
        predictivePopTransitionSpec = { backward() },
    )
}

// A screen moves a little and fades: the one leaving fades out quickly, the one arriving fades in after it, so the
// two never show through each other (the stock cross-fade drew both at half strength, their texts on top of each
// other). It works with the opaque page [destEntry] gives every screen.
private const val SHIFT = 8
private fun forward(): ContentTransform =
    (slideInHorizontally(tween(260)) { it / SHIFT } + fadeIn(tween(180, delayMillis = 70))) togetherWith
        (slideOutHorizontally(tween(260)) { -it / SHIFT } + fadeOut(tween(70)))

private fun backward(): ContentTransform =
    (slideInHorizontally(tween(260)) { -it / SHIFT } + fadeIn(tween(180, delayMillis = 70))) togetherWith
        (slideOutHorizontally(tween(260)) { it / SHIFT } + fadeOut(tween(70)))

/** A screen's entry; the section is part of its key, so the same screen kind in two sections never shares state. */
fun destEntry(dest: Dest, metadata: Map<String, Any> = emptyMap(), content: @Composable () -> Unit): NavEntry<Dest> =
    NavEntry(dest, contentKey = "${dest.owner.name}/$dest", metadata = metadata) {
        // Opaque: a screen leaving or arriving must not show the one under it.
        Box(Modifier.fillMaxSize().background(AppTheme.colors.background)) { content() }
    }
