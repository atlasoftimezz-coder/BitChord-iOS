package java.util.concurrent

/** Stand-in for the JDK enum so ported `callTimeout(8, TimeUnit.SECONDS)` lines compile. */
enum class TimeUnit(private val millis: Double) {
    NANOSECONDS(1e-6),
    MICROSECONDS(1e-3),
    MILLISECONDS(1.0),
    SECONDS(1_000.0),
    MINUTES(60_000.0),
    HOURS(3_600_000.0),
    DAYS(86_400_000.0);

    fun toMillis(duration: Long): Long = (duration * millis).toLong()
    fun toSeconds(duration: Long): Long = (duration * millis / 1_000).toLong()
}
