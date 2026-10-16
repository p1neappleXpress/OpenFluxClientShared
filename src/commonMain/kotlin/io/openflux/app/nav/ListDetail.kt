package io.openflux.app.nav

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.scene.Scene
import androidx.navigation3.scene.SceneStrategy
import androidx.navigation3.scene.SceneStrategyScope
import io.openflux.app.theme.AppTheme

/** Marks a screen as the list (left pane) or a detail (right pane) of its section, for wide windows. */
object ListDetail {
    internal const val PANE = "openflux.pane"
    internal const val SECTION = "openflux.section"

    fun list(section: Section): Map<String, Any> = mapOf(PANE to "list", SECTION to section)
    fun detail(section: Section): Map<String, Any> = mapOf(PANE to "detail", SECTION to section)
}

/** True for a screen shown beside its list: the list is the way back, so the screen has no back arrow. */
val LocalInPane = staticCompositionLocalOf { false }

private val NavEntry<Dest>.pane: String? get() = metadata[ListDetail.PANE] as? String
private val NavEntry<Dest>.paneSection: Section? get() = metadata[ListDetail.SECTION] as? Section

/**
 * In a wide window a section's list keeps its place at the left and the chosen item opens at the right, so
 * choosing never leaves the list (servers and their nodes, the categories of Settings). Screens without marks
 * (wizards, editors of their own) take the whole window.
 */
class ListDetailSceneStrategy(
    private val wide: Boolean,
    private val nothingChosen: @Composable (Section) -> Unit,
) : SceneStrategy<Dest> {
    override fun SceneStrategyScope<Dest>.calculateScene(entries: List<NavEntry<Dest>>): Scene<Dest>? {
        if (!wide) return null
        val top = entries.lastOrNull() ?: return null
        val section = top.paneSection ?: return null
        val list = when (top.pane) {
            "list" -> top
            "detail" -> entries.lastOrNull { it.pane == "list" && it.paneSection == section } ?: return null
            else -> return null
        }
        val detail = top.takeIf { it.pane == "detail" }
        return ListDetailScene(section, list, detail, entries.dropLast(1), nothingChosen)
    }
}

private class ListDetailScene(
    private val section: Section,
    private val list: NavEntry<Dest>,
    private val detail: NavEntry<Dest>?,
    override val previousEntries: List<NavEntry<Dest>>,
    private val nothingChosen: @Composable (Section) -> Unit,
) : Scene<Dest> {
    /** One key while the list is shown: choosing another item swaps the right side only. */
    override val key: Any = "list-detail/${section.name}"
    override val entries: List<NavEntry<Dest>> = listOfNotNull(list, detail)
    override val content: @Composable () -> Unit = {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val listWidth = (maxWidth * 0.32f).coerceIn(300.dp, 420.dp)
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.width(listWidth).fillMaxHeight()) { list.Content() }
                Box(Modifier.width(1.dp).fillMaxHeight().background(AppTheme.colors.border))
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    if (detail != null) CompositionLocalProvider(LocalInPane provides true) { detail.Content() }
                    else nothingChosen(section)
                }
            }
        }
    }
}
