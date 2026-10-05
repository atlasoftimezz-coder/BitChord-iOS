package androidx.annotation

// androidx.annotation for ported code: documentation-only on iOS.

@Target(AnnotationTarget.VALUE_PARAMETER, AnnotationTarget.FIELD, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY, AnnotationTarget.TYPE, AnnotationTarget.LOCAL_VARIABLE, AnnotationTarget.PROPERTY_GETTER)
annotation class FloatRange(
    val from: Double = Double.NEGATIVE_INFINITY,
    val to: Double = Double.POSITIVE_INFINITY,
    val fromInclusive: Boolean = true,
    val toInclusive: Boolean = true,
)

@Target(AnnotationTarget.VALUE_PARAMETER, AnnotationTarget.FIELD, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY, AnnotationTarget.TYPE, AnnotationTarget.LOCAL_VARIABLE)
annotation class IntRange(val from: Long = Long.MIN_VALUE, val to: Long = Long.MAX_VALUE)

annotation class RequiresApi(val value: Int = 1, val api: Int = 1)
annotation class ChecksSdkIntAtLeast(val api: Int = -1, val codename: String = "", val parameter: Int = -1, val lambda: Int = -1)
annotation class ColorInt
annotation class DrawableRes
annotation class StringRes
annotation class MainThread
annotation class WorkerThread
annotation class VisibleForTesting
annotation class Keep
annotation class OptIn(vararg val markerClass: kotlin.reflect.KClass<out Annotation>)
