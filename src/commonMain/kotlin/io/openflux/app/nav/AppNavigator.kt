package io.openflux.app.nav

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList

/**
 * Where the person is: a section and a stack of screens in each (spec 4.2). Every screen has one owner
 * section; opening it switches there. Back pops; from a section's first screen it goes Home; on Home it
 * is not handled, so the system leaves the app.
 */
@Stable
class AppNavigator(initial: NavState = NavState()) {
    var section by mutableStateOf(initial.section)
        private set

    private val stacks: Map<Section, SnapshotStateList<Dest>> = Section.entries.associateWith { s ->
        mutableStateListOf<Dest>().apply { addAll(initial.stacks[s].orEmpty().ifEmpty { listOf(rootOf(s)) }) }
    }

    /** The screens of [section], first (its root) to last. */
    fun stack(section: Section): List<Dest> = stacks.getValue(section)

    /** What is on screen now. */
    val top: Dest get() = stacks.getValue(section).last()

    /** True when back has somewhere to go inside the app. */
    val canGoBack: Boolean get() = stacks.getValue(section).size > 1 || section != Section.Home

    /** Opens [dest] in its owner section: a root pops that section to it, any other screen goes on top (not twice in a row). */
    fun open(dest: Dest) {
        section = dest.owner
        val stack = stacks.getValue(dest.owner)
        if (dest.isRoot) popToRoot(stack) else if (stack.last() != dest) stack.add(dest)
    }

    /** A tap on a section: the section where the person left it; tapped again while in it, its first screen. */
    fun select(target: Section) {
        if (target == section) popToRoot(stacks.getValue(target)) else section = target
    }

    fun back(): Boolean {
        val stack = stacks.getValue(section)
        return when {
            stack.size > 1 -> { stack.removeAt(stack.lastIndex); true }
            section != Section.Home -> { section = Section.Home; true }
            else -> false
        }
    }

    /** Replaces the top screen of [dest]'s section (a finished wizard becomes the node it made). */
    fun replaceTop(dest: Dest) {
        val stack = stacks.getValue(dest.owner)
        if (stack.size > 1) stack.removeAt(stack.lastIndex)
        open(dest)
    }

    /** Drops every screen [predicate] matches from every stack (its profile was deleted); roots stay. */
    fun forget(predicate: (Dest) -> Boolean) {
        for (stack in stacks.values) stack.removeAll { !it.isRoot && predicate(it) }
    }

    fun snapshot(): NavState = NavState(section, stacks.mapValues { it.value.toList() })

    private fun popToRoot(stack: SnapshotStateList<Dest>) {
        while (stack.size > 1) stack.removeAt(stack.lastIndex)
    }

    companion object {
        /**
         * [state] as it was, but each stack starts at its root, holds only its own section's screens, and stops
         * before the first screen [keep] rejects: what was above a gone screen made no sense without it.
         */
        fun restore(state: NavState?, keep: (Dest) -> Boolean): AppNavigator {
            val s = state ?: return AppNavigator()
            val stacks = Section.entries.associateWith { section ->
                val above = s.stacks[section].orEmpty().filter { it.owner == section && !it.isRoot }
                listOf(rootOf(section)) + above.takeWhile(keep)
            }
            return AppNavigator(NavState(s.section, stacks))
        }
    }
}
