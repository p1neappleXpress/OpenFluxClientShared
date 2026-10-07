package io.openflux.desktop.data

/**
 * Orders script versions the way the core does (transport/script CompareVersions):
 * major.minor.patch by number ("1.10.0" is newer than "1.9.0"), a pre-release ("1.3.0-beta.1")
 * is older than its release, numeric pre-release identifiers sort below alphanumeric
 * ones, and what does not read as a version is older than anything that does.
 */
object ScriptVersions {
    /** Negative when [a] is older than [b], zero when they are the same, positive when [a] is newer. */
    fun compare(a: String, b: String): Int {
        val va = parse(a)
        val vb = parse(b)
        if (va == null || vb == null) return if (va == null && vb == null) 0 else if (va == null) -1 else 1
        val (coreA, preA) = va
        val (coreB, preB) = vb
        for (i in 0 until maxOf(coreA.size, coreB.size)) {
            val d = coreA.getOrElse(i) { 0 }.compareTo(coreB.getOrElse(i) { 0 })
            if (d != 0) return d
        }
        if (preA.isEmpty() && preB.isEmpty()) return 0
        if (preA.isEmpty()) return 1
        if (preB.isEmpty()) return -1
        for (i in 0 until minOf(preA.size, preB.size)) {
            val na = preA[i].toIntOrNull()
            val nb = preB[i].toIntOrNull()
            val c = when {
                na != null && nb != null -> na.compareTo(nb)
                na != null -> -1
                nb != null -> 1
                else -> preA[i].compareTo(preB[i])
            }
            if (c != 0) return c
        }
        return preA.size.compareTo(preB.size)
    }

    private fun parse(version: String): Pair<List<Int>, List<String>>? {
        val v = version.trim().removePrefix("v").substringBefore('+')
        if (v.isEmpty()) return null
        val core = v.substringBefore('-').split('.').map { it.toIntOrNull()?.takeIf { n -> n >= 0 } ?: return null }
        if (core.size != 3) return null
        val pre = if ('-' in v) v.substringAfter('-').split('.') else emptyList()
        return core to pre
    }
}
