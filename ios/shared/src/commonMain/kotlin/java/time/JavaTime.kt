package java.time

import com.music.bitchord.platform.epochMillis
import com.music.bitchord.platform.utcOffsetSeconds
import java.time.format.DateTimeFormatter

/*
 * The slice of java.time the ported stats/Replay code uses, on plain epoch
 * arithmetic (Howard Hinnant's civil-from-days) plus the device's UTC offset.
 */

enum class Month {
    JANUARY, FEBRUARY, MARCH, APRIL, MAY, JUNE, JULY, AUGUST, SEPTEMBER, OCTOBER, NOVEMBER, DECEMBER;

    val value: Int get() = ordinal + 1

    fun length(leapYear: Boolean): Int = when (this) {
        FEBRUARY -> if (leapYear) 29 else 28
        APRIL, JUNE, SEPTEMBER, NOVEMBER -> 30
        else -> 31
    }

    companion object {
        fun of(month: Int): Month = entries[month - 1]
    }
}

enum class DayOfWeek {
    MONDAY, TUESDAY, WEDNESDAY, THURSDAY, FRIDAY, SATURDAY, SUNDAY;

    val value: Int get() = ordinal + 1

    companion object {
        fun of(dayOfWeek: Int): DayOfWeek = entries[dayOfWeek - 1]
    }
}

private fun isLeap(year: Int) = (year % 4 == 0 && year % 100 != 0) || year % 400 == 0

private fun daysFromCivil(y0: Int, m: Int, d: Int): Long {
    val y = if (m <= 2) y0 - 1 else y0
    val era = (if (y >= 0) y else y - 399) / 400
    val yoe = y - era * 400
    val doy = (153 * (if (m > 2) m - 3 else m + 9) + 2) / 5 + d - 1
    val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
    return era.toLong() * 146097 + doe - 719468
}

private fun civilFromDays(days: Long): Triple<Int, Int, Int> {
    val z = days + 719468
    val era = (if (z >= 0) z else z - 146096) / 146097
    val doe = z - era * 146097
    val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
    val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
    val mp = (5 * doy + 2) / 153
    val d = (doy - (153 * mp + 2) / 5 + 1).toInt()
    val m = (if (mp < 10) mp + 3 else mp - 9).toInt()
    val y = (yoe + era * 400 + (if (m <= 2) 1 else 0)).toInt()
    return Triple(y, m, d)
}

private fun pad(value: Int, width: Int) = value.toString().padStart(width, '0')

class LocalDate private constructor(val year: Int, val monthValue: Int, val dayOfMonth: Int) : Comparable<LocalDate> {
    val month: Month get() = Month.of(monthValue)
    val dayOfWeek: DayOfWeek get() = DayOfWeek.of(((toEpochDay() + 3).mod(7L)).toInt() + 1)
    val dayOfYear: Int get() = (toEpochDay() - daysFromCivil(year, 1, 1)).toInt() + 1

    fun toEpochDay(): Long = daysFromCivil(year, monthValue, dayOfMonth)
    fun lengthOfMonth(): Int = month.length(isLeap(year))
    fun isLeapYear(): Boolean = isLeap(year)

    fun plusDays(days: Long): LocalDate = ofEpochDay(toEpochDay() + days)
    fun minusDays(days: Long): LocalDate = plusDays(-days)
    fun plusWeeks(weeks: Long): LocalDate = plusDays(weeks * 7)
    fun minusWeeks(weeks: Long): LocalDate = plusDays(-weeks * 7)
    fun plusMonths(months: Long): LocalDate {
        val total = year * 12L + (monthValue - 1) + months
        val y = total.floorDiv(12L).toInt()
        val m = total.mod(12L).toInt() + 1
        return of(y, m, minOf(dayOfMonth, Month.of(m).length(isLeap(y))))
    }
    fun minusMonths(months: Long): LocalDate = plusMonths(-months)
    fun plusYears(years: Long): LocalDate = plusMonths(years * 12)
    fun minusYears(years: Long): LocalDate = plusMonths(-years * 12)
    fun withDayOfMonth(day: Int): LocalDate = of(year, monthValue, day)

    fun isBefore(other: LocalDate) = this < other
    fun isAfter(other: LocalDate) = this > other
    fun isEqual(other: LocalDate) = this == other

    fun format(formatter: DateTimeFormatter): String = formatter.format(this)

    override fun compareTo(other: LocalDate): Int = toEpochDay().compareTo(other.toEpochDay())
    override fun equals(other: Any?) = other is LocalDate && other.toEpochDay() == toEpochDay()
    override fun hashCode() = toEpochDay().hashCode()
    override fun toString() = "${pad(year, 4)}-${pad(monthValue, 2)}-${pad(dayOfMonth, 2)}"

    companion object {
        fun of(year: Int, month: Int, dayOfMonth: Int) = LocalDate(year, month, dayOfMonth)
        fun of(year: Int, month: Month, dayOfMonth: Int) = LocalDate(year, month.value, dayOfMonth)
        fun ofEpochDay(epochDay: Long): LocalDate = civilFromDays(epochDay).let { (y, m, d) -> LocalDate(y, m, d) }
        fun now(): LocalDate = Instant.now().atZone(ZoneId.systemDefault()).toLocalDate()
        fun now(zone: ZoneId): LocalDate = Instant.now().atZone(zone).toLocalDate()
        fun parse(text: CharSequence): LocalDate {
            val parts = text.toString().trim().split('-')
            require(parts.size == 3) { "Not an ISO date: $text" }
            return LocalDate(parts[0].toInt(), parts[1].toInt(), parts[2].toInt())
        }
    }
}

class YearMonth private constructor(val year: Int, val monthValue: Int) : Comparable<YearMonth> {
    val month: Month get() = Month.of(monthValue)
    fun lengthOfMonth(): Int = month.length(isLeap(year))
    fun atDay(day: Int): LocalDate = LocalDate.of(year, monthValue, day)
    fun atEndOfMonth(): LocalDate = atDay(lengthOfMonth())
    fun plusMonths(months: Long): YearMonth {
        val total = year * 12L + (monthValue - 1) + months
        return YearMonth(total.floorDiv(12L).toInt(), total.mod(12L).toInt() + 1)
    }
    fun minusMonths(months: Long): YearMonth = plusMonths(-months)
    fun plusYears(years: Long): YearMonth = plusMonths(years * 12)
    fun minusYears(years: Long): YearMonth = plusMonths(-years * 12)
    fun isBefore(other: YearMonth) = this < other
    fun isAfter(other: YearMonth) = this > other
    fun format(formatter: DateTimeFormatter): String = formatter.format(atDay(1))

    override fun compareTo(other: YearMonth): Int = (year * 12 + monthValue).compareTo(other.year * 12 + other.monthValue)
    override fun equals(other: Any?) = other is YearMonth && other.year == year && other.monthValue == monthValue
    override fun hashCode() = year * 12 + monthValue
    override fun toString() = "${pad(year, 4)}-${pad(monthValue, 2)}"

    companion object {
        fun of(year: Int, month: Int) = YearMonth(year, month)
        fun of(year: Int, month: Month) = YearMonth(year, month.value)
        fun now(): YearMonth = from(LocalDate.now())
        fun from(date: LocalDate): YearMonth = YearMonth(date.year, date.monthValue)
        fun from(dateTime: ZonedDateTime): YearMonth = from(dateTime.toLocalDate())
        fun parse(text: CharSequence): YearMonth {
            val parts = text.toString().trim().split('-')
            require(parts.size == 2) { "Not a year-month: $text" }
            val m = parts[1].toInt()
            require(m in 1..12) { "Bad month: $text" }
            return YearMonth(parts[0].toInt(), m)
        }
    }
}

class ZoneId private constructor(val id: String, internal val fixedOffsetSeconds: Int?) {
    internal fun offsetAt(epochMs: Long): Int = fixedOffsetSeconds ?: utcOffsetSeconds(epochMs)
    override fun toString() = id

    companion object {
        fun systemDefault(): ZoneId = ZoneId("system", null)
        fun of(id: String): ZoneId = if (id == "UTC" || id == "Z") UTC else systemDefault()
        val UTC = ZoneId("UTC", 0)
    }
}

object ZoneOffset {
    val UTC: ZoneId = ZoneId.UTC
}

class Instant private constructor(private val epochMs: Long) : Comparable<Instant> {
    fun toEpochMilli(): Long = epochMs
    val epochSecond: Long get() = epochMs.floorDiv(1000L)
    fun atZone(zone: ZoneId): ZonedDateTime = ZonedDateTime(epochMs, zone)
    fun plusMillis(ms: Long) = Instant(epochMs + ms)
    fun minusMillis(ms: Long) = Instant(epochMs - ms)
    fun plusSeconds(s: Long) = Instant(epochMs + s * 1000)
    fun isBefore(other: Instant) = epochMs < other.epochMs
    fun isAfter(other: Instant) = epochMs > other.epochMs
    override fun compareTo(other: Instant) = epochMs.compareTo(other.epochMs)
    override fun equals(other: Any?) = other is Instant && other.epochMs == epochMs
    override fun hashCode() = epochMs.hashCode()

    /** ISO-8601 in UTC, e.g. 2026-10-05T07:41:03.123Z. */
    override fun toString(): String {
        val z = atZone(ZoneId.UTC)
        val ms = epochMs.mod(1000L).toInt()
        return "${z.toLocalDate()}T${pad(z.hour, 2)}:${pad(z.minute, 2)}:${pad(z.second, 2)}" +
            (if (ms != 0) ".${pad(ms, 3)}" else "") + "Z"
    }

    companion object {
        fun now(): Instant = Instant(epochMillis())
        fun ofEpochMilli(epochMilli: Long) = Instant(epochMilli)
        fun ofEpochSecond(epochSecond: Long) = Instant(epochSecond * 1000)
        val EPOCH = Instant(0)
        fun parse(text: CharSequence): Instant {
            val s = text.toString().trim()
            val date = LocalDate.parse(s.substring(0, 10))
            val time = s.substring(11).removeSuffix("Z").split(':')
            val secs = time.getOrNull(2)?.toDoubleOrNull() ?: 0.0
            val ms = date.toEpochDay() * 86_400_000L + (time[0].toInt() * 3600 + time[1].toInt() * 60) * 1000L +
                (secs * 1000).toLong()
            return Instant(ms)
        }
    }
}

class ZonedDateTime internal constructor(private val epochMs: Long, val zone: ZoneId) {
    private val localMs: Long = epochMs + zone.offsetAt(epochMs) * 1000L
    private val date: LocalDate = LocalDate.ofEpochDay(localMs.floorDiv(86_400_000L))
    private val msOfDay: Long = localMs.mod(86_400_000L)

    val year: Int get() = date.year
    val monthValue: Int get() = date.monthValue
    val month: Month get() = date.month
    val dayOfMonth: Int get() = date.dayOfMonth
    val dayOfWeek: DayOfWeek get() = date.dayOfWeek
    val dayOfYear: Int get() = date.dayOfYear
    val hour: Int get() = (msOfDay / 3_600_000).toInt()
    val minute: Int get() = ((msOfDay / 60_000) % 60).toInt()
    val second: Int get() = ((msOfDay / 1000) % 60).toInt()

    fun toLocalDate(): LocalDate = date
    fun toInstant(): Instant = Instant.ofEpochMilli(epochMs)
    fun toEpochSecond(): Long = epochMs.floorDiv(1000L)
    fun format(formatter: DateTimeFormatter): String = formatter.format(this)
}
