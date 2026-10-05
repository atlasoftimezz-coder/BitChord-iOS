package com.music.bitchord.platform

import platform.Foundation.NSUserDefaults

actual object KeyValueStore {
    private val defaults get() = NSUserDefaults.standardUserDefaults

    private fun has(key: String) = defaults.objectForKey(key) != null

    actual fun getBoolean(key: String): Boolean? = if (has(key)) defaults.boolForKey(key) else null
    actual fun putBoolean(key: String, value: Boolean) = defaults.setBool(value, forKey = key)

    actual fun getInt(key: String): Int? = if (has(key)) defaults.integerForKey(key).toInt() else null
    actual fun putInt(key: String, value: Int) = defaults.setInteger(value.toLong(), forKey = key)

    actual fun getLong(key: String): Long? = if (has(key)) defaults.integerForKey(key) else null
    actual fun putLong(key: String, value: Long) = defaults.setInteger(value, forKey = key)

    actual fun getString(key: String): String? = defaults.stringForKey(key)
    actual fun putString(key: String, value: String?) {
        if (value == null) defaults.removeObjectForKey(key) else defaults.setObject(value, forKey = key)
    }
}
