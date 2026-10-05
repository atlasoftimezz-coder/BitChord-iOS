package androidx.core.graphics

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/** androidx.core ColorUtils for ported code — same formulas as AOSP, on ARGB ints. */
object ColorUtils {
    private fun r(c: Int) = (c shr 16) and 0xff
    private fun g(c: Int) = (c shr 8) and 0xff
    private fun b(c: Int) = c and 0xff
    private fun a(c: Int) = (c ushr 24) and 0xff
    private fun argb(a: Int, r: Int, g: Int, b: Int) = (a shl 24) or (r shl 16) or (g shl 8) or b

    fun setAlphaComponent(color: Int, alpha: Int): Int = (color and 0x00ffffff) or (alpha shl 24)

    fun blendARGB(color1: Int, color2: Int, ratio: Float): Int {
        val inv = 1f - ratio
        return argb(
            (a(color1) * inv + a(color2) * ratio).roundToInt(),
            (r(color1) * inv + r(color2) * ratio).roundToInt(),
            (g(color1) * inv + g(color2) * ratio).roundToInt(),
            (b(color1) * inv + b(color2) * ratio).roundToInt(),
        )
    }

    fun compositeColors(foreground: Int, background: Int): Int {
        val bgAlpha = a(background)
        val fgAlpha = a(foreground)
        val alpha = 0xff - (0xff - bgAlpha) * (0xff - fgAlpha) / 0xff
        fun comp(f: Int, b: Int) = if (alpha == 0) 0 else (0xff * f * fgAlpha + b * bgAlpha * (0xff - fgAlpha)) / (alpha * 0xff)
        return argb(alpha, comp(r(foreground), r(background)), comp(g(foreground), g(background)), comp(b(foreground), b(background)))
    }

    private fun linear(channel: Int): Double {
        val v = channel / 255.0
        return if (v < 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
    }

    fun calculateLuminance(color: Int): Double =
        0.2126 * linear(r(color)) + 0.7152 * linear(g(color)) + 0.0722 * linear(b(color))

    fun calculateContrast(foreground: Int, background: Int): Double {
        val fg = if (a(foreground) < 255) compositeColors(foreground, background) else foreground
        val l1 = calculateLuminance(fg) + 0.05
        val l2 = calculateLuminance(background) + 0.05
        return max(l1, l2) / min(l1, l2)
    }

    fun RGBToHSL(r: Int, g: Int, b: Int, outHsl: FloatArray) {
        val rf = r / 255f
        val gf = g / 255f
        val bf = b / 255f
        val maxC = max(rf, max(gf, bf))
        val minC = min(rf, min(gf, bf))
        val delta = maxC - minC
        val l = (maxC + minC) / 2f
        var h: Float
        val s: Float
        if (maxC == minC) {
            h = 0f
            s = 0f
        } else {
            h = when (maxC) {
                rf -> ((gf - bf) / delta) % 6f
                gf -> (bf - rf) / delta + 2f
                else -> (rf - gf) / delta + 4f
            }
            s = delta / (1f - abs(2f * l - 1f))
        }
        h = (h * 60f) % 360f
        if (h < 0) h += 360f
        outHsl[0] = h.coerceIn(0f, 360f)
        outHsl[1] = s.coerceIn(0f, 1f)
        outHsl[2] = l.coerceIn(0f, 1f)
    }

    fun colorToHSL(color: Int, outHsl: FloatArray) = RGBToHSL(r(color), g(color), b(color), outHsl)

    fun HSLToColor(hsl: FloatArray): Int {
        val h = hsl[0]
        val s = hsl[1]
        val l = hsl[2]
        val c = (1f - abs(2 * l - 1f)) * s
        val m = l - 0.5f * c
        val x = c * (1f - abs((h / 60f % 2f) - 1f))
        val (rf, gf, bf) = when ((h.toInt() / 60)) {
            0 -> Triple(c + m, x + m, m)
            1 -> Triple(x + m, c + m, m)
            2 -> Triple(m, c + m, x + m)
            3 -> Triple(m, x + m, c + m)
            4 -> Triple(x + m, m, c + m)
            else -> Triple(c + m, m, x + m)
        }
        return argb(
            255,
            (rf * 255).roundToInt().coerceIn(0, 255),
            (gf * 255).roundToInt().coerceIn(0, 255),
            (bf * 255).roundToInt().coerceIn(0, 255),
        )
    }
}
