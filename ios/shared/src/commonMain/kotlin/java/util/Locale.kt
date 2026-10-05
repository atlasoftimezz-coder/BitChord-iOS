package java.util

import com.music.bitchord.platform.deviceLanguage
import com.music.bitchord.platform.deviceRegion
import com.music.bitchord.platform.languageDisplayName

/**
 * java.util.Locale for ported code: language/country tags and the device
 * default. Case conversion in Kotlin common is locale-independent, so the
 * `lowercase(Locale.ROOT)` calls are rewritten to `lowercase()` by the port
 * fixer (ios/tools/fix_ported.py); what remains here is the data side.
 */
class Locale(val language: String, val country: String = "", val variant: String = "") {

    fun toLanguageTag(): String = if (country.isEmpty()) language else "$language-$country"

    fun getDisplayName(inLocale: Locale = getDefault()): String =
        languageDisplayName(language, inLocale.language) ?: language

    fun getDisplayLanguage(inLocale: Locale = getDefault()): String = getDisplayName(inLocale)

    val displayName: String get() = getDisplayName()
    val displayLanguage: String get() = getDisplayName()

    override fun toString(): String = if (country.isEmpty()) language else "${language}_$country"
    override fun equals(other: Any?): Boolean =
        other is Locale && other.language == language && other.country == country
    override fun hashCode(): Int = language.hashCode() * 31 + country.hashCode()

    companion object {
        val ROOT = Locale("")
        val US = Locale("en", "US")
        val UK = Locale("en", "GB")
        val ENGLISH = Locale("en")
        val JAPAN = Locale("ja", "JP")
        val JAPANESE = Locale("ja")

        fun getDefault(): Locale = Locale(deviceLanguage(), deviceRegion())

        fun forLanguageTag(tag: String): Locale {
            val parts = tag.replace('_', '-').split('-')
            val language = parts.getOrElse(0) { "" }.lowercase()
            // The region is the 2-letter (or 3-digit) subtag; a 4-letter one is a script.
            val country = parts.drop(1).firstOrNull { it.length == 2 || (it.length == 3 && it.all(Char::isDigit)) }
                ?.uppercase().orEmpty()
            return Locale(language, country)
        }

        fun getISOCountries(): Array<String> = emptyArray()
    }
}
