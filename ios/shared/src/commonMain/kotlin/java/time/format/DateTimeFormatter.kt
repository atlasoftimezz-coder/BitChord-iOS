package java.time.format

import java.time.LocalDate
import java.time.ZonedDateTime
import java.util.Locale

/**
 * DateTimeFormatter.ofPattern for the patterns the ported code uses:
 * y, M/MM/MMM/MMMM, d/dd, E/EEE/EEEE, H/HH, h/hh, m/mm, s/ss, a, and 'quoted' text.
 * Month and day names are English.
 */
class DateTimeFormatter private constructor(private val pattern: String) {

    fun withLocale(locale: Locale): DateTimeFormatter = this

    fun format(temporal: Any): String {
        val date: LocalDate
        var hour = 0
        var minute = 0
        var second = 0
        when (temporal) {
            is LocalDate -> date = temporal
            is ZonedDateTime -> {
                date = temporal.toLocalDate()
                hour = temporal.hour
                minute = temporal.minute
                second = temporal.second
            }
            else -> return temporal.toString()
        }
        val out = StringBuilder()
        var i = 0
        while (i < pattern.length) {
            val c = pattern[i]
            if (c == '\'') {
                val end = pattern.indexOf('\'', i + 1).takeIf { it > i } ?: pattern.length
                out.append(pattern, i + 1, end)
                i = end + 1
                continue
            }
            if (!c.isLetter()) {
                out.append(c)
                i++
                continue
            }
            var n = 1
            while (i + n < pattern.length && pattern[i + n] == c) n++
            out.append(
                when (c) {
                    'y', 'u' -> if (n == 2) (date.year % 100).toString().padStart(2, '0') else date.year.toString()
                    'M', 'L' -> when {
                        n >= 4 -> MONTHS[date.monthValue - 1]
                        n == 3 -> MONTHS[date.monthValue - 1].take(3)
                        else -> date.monthValue.toString().padStart(n, '0')
                    }
                    'd' -> date.dayOfMonth.toString().padStart(n, '0')
                    'E' -> if (n >= 4) DAYS[date.dayOfWeek.ordinal] else DAYS[date.dayOfWeek.ordinal].take(3)
                    'H' -> hour.toString().padStart(n, '0')
                    'h' -> (if (hour % 12 == 0) 12 else hour % 12).toString().padStart(n, '0')
                    'm' -> minute.toString().padStart(n, '0')
                    's' -> second.toString().padStart(n, '0')
                    'a' -> if (hour < 12) "AM" else "PM"
                    else -> c.toString().repeat(n)
                },
            )
            i += n
        }
        return out.toString()
    }

    companion object {
        private val MONTHS = listOf(
            "January", "February", "March", "April", "May", "June",
            "July", "August", "September", "October", "November", "December",
        )
        private val DAYS = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")

        fun ofPattern(pattern: String): DateTimeFormatter = DateTimeFormatter(pattern)
        fun ofPattern(pattern: String, locale: Locale): DateTimeFormatter = DateTimeFormatter(pattern)
        val ISO_LOCAL_DATE: DateTimeFormatter = DateTimeFormatter("yyyy-MM-dd")
    }
}
