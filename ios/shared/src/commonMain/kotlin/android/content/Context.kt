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

    /** Caches (purgeable) and Application Support, as Android's cacheDir / filesDir. */
    val cacheDir: java.io.File get() = java.io.File(com.music.bitchord.platform.FileSystem.cachesDirectory())
    val filesDir: java.io.File get() = java.io.File(com.music.bitchord.platform.FileSystem.filesDirectory())
    fun getExternalFilesDir(type: String?): java.io.File? =
        java.io.File(com.music.bitchord.platform.FileSystem.documentsDirectory()).let { if (type == null) it else java.io.File(it, type) }

    /** Plain preferences (NSUserDefaults); see [StoredPreferences]. */
    fun getSharedPreferences(name: String, mode: Int): SharedPreferences = StoredPreferences.open(name, secure = false)

    fun deleteSharedPreferences(name: String): Boolean {
        getSharedPreferences(name, MODE_PRIVATE).edit().clear().commit()
        return true
    }

    class Resources internal constructor() {
        fun getString(id: String): String = id
        fun getString(id: String, vararg formatArgs: Any): String = formatAndroid(id, formatArgs)
        fun getQuantityString(id: PluralRes, quantity: Int): String = id.forCount(quantity)
        fun getQuantityString(id: PluralRes, quantity: Int, vararg formatArgs: Any): String =
            formatAndroid(id.forCount(quantity), formatArgs)
    }

    companion object {
        val app: Context = android.app.Application()
        const val MODE_PRIVATE = 0
    }
}
