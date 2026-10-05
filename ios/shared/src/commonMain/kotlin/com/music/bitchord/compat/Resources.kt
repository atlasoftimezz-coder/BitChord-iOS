package com.music.bitchord.compat

/** A `<plurals>` entry: the "one" and "other" forms (English needs no more). */
class PluralRes(val one: String, val other: String) {
    fun forCount(count: Int): String = if (count == 1) one else other
}

private val SPECIFIER = Regex("%(?:(\\d+)\\$)?([-#+ 0,(]*)(\\d+)?(?:\\.(\\d+))?([sdfx%])")

/**
 * Android's `getString(id, args)` formatting for the specifiers its strings
 * use: `%s`, `%d`, `%f`, `%x`, `%%`, positional `%1$s`, width/zero-pad and
 * precision. Kotlin common has no String.format.
 */
fun formatAndroid(template: String, args: Array<out Any?>): String {
    if ('%' !in template) return template
    var next = 0
    return SPECIFIER.replace(template) { m ->
        val conversion = m.groupValues[5]
        if (conversion == "%") return@replace "%"
        val index = m.groupValues[1].toIntOrNull()?.minus(1) ?: next++
        val arg = args.getOrNull(index)
        val flags = m.groupValues[2]
        val width = m.groupValues[3].toIntOrNull() ?: 0
        val precision = m.groupValues[4].toIntOrNull()
        val body = when (conversion) {
            "d" -> {
                val n = (arg as? Number)?.toLong() ?: arg.toString().toLongOrNull() ?: 0L
                if (',' in flags) groupThousands(n) else n.toString()
            }
            "f" -> formatFixed((arg as? Number)?.toDouble() ?: 0.0, precision ?: 6)
            "x" -> ((arg as? Number)?.toLong() ?: 0L).toString(16)
            else -> arg.toString()
        }
        when {
            body.length >= width -> body
            '-' in flags -> body.padEnd(width)
            '0' in flags && conversion != "s" -> {
                if (body.startsWith('-')) "-" + body.drop(1).padStart(width - 1, '0') else body.padStart(width, '0')
            }
            else -> body.padStart(width)
        }
    }
}

private fun groupThousands(n: Long): String {
    val digits = kotlin.math.abs(n).toString()
    val grouped = digits.reversed().chunked(3).joinToString(",").reversed()
    return if (n < 0) "-$grouped" else grouped
}

/** Fixed-point formatting with [decimals] places, rounded half-up. */
fun formatFixed(value: Double, decimals: Int): String {
    if (value.isNaN() || value.isInfinite()) return value.toString()
    var factor = 1.0
    repeat(decimals) { factor *= 10 }
    val negative = value < 0
    val scaled = kotlin.math.round(kotlin.math.abs(value) * factor).toLong()
    val whole = scaled / factor.toLong()
    val frac = scaled % factor.toLong()
    val text = if (decimals == 0) whole.toString() else "$whole." + frac.toString().padStart(decimals, '0')
    return if (negative && scaled != 0L) "-$text" else text
}

/** JVM `"fmt".format(args)` for ported code. */
fun String.format(vararg args: Any?): String = formatAndroid(this, args)

/** JVM `String.format(fmt, args)` for ported code. */
fun String.Companion.format(format: String, vararg args: Any?): String = formatAndroid(format, args)
