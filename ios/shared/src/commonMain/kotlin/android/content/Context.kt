package android.content

import com.music.bitchord.compat.PluralRes
import com.music.bitchord.compat.formatAndroid

/**
 * The bits of android.content.Context that ported code touches: string lookup
 * and handing itself on as `applicationContext`. There is one per app, obtained
 * from `LocalContext.current` as on Android.
 */
open class Context internal constructor() {
    open val applicationContext: Context get() = this

    fun getString(id: String): String = id
    fun getString(id: String, vararg formatArgs: Any): String = formatAndroid(id, formatArgs)

    val resources: Resources = Resources()

    class Resources internal constructor() {
        fun getString(id: String): String = id
        fun getString(id: String, vararg formatArgs: Any): String = formatAndroid(id, formatArgs)
        fun getQuantityString(id: PluralRes, quantity: Int): String = id.forCount(quantity)
        fun getQuantityString(id: PluralRes, quantity: Int, vararg formatArgs: Any): String =
            formatAndroid(id.forCount(quantity), formatArgs)
    }

    companion object {
        val app: Context = Context()
    }
}
