package java.text

import com.music.bitchord.platform.unicodeNormalize

/** java.text.Normalizer for ported code (diacritic stripping uses NFD). */
object Normalizer {
    enum class Form { NFD, NFC, NFKD, NFKC }

    fun normalize(src: CharSequence, form: Form): String = unicodeNormalize(src.toString(), form.name)

    fun isNormalized(src: CharSequence, form: Form): Boolean = normalize(src, form) == src.toString()
}
