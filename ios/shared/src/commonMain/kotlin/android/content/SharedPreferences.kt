package android.content

import com.music.bitchord.platform.PlatformLock
import com.music.bitchord.platform.SecureStore
import com.music.bitchord.platform.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * android.content.SharedPreferences for ported code. Each named file is kept
 * as one JSON blob — in the Keychain when [secure] (what the Android app keeps
 * in EncryptedSharedPreferences: cookies, tokens, passwords), otherwise in
 * NSUserDefaults. Values keep their types, so getInt reads back an Int.
 */
interface SharedPreferences {
    fun getString(key: String, defValue: String?): String?
    fun getStringSet(key: String, defValues: Set<String>?): Set<String>?
    fun getInt(key: String, defValue: Int): Int
    fun getLong(key: String, defValue: Long): Long
    fun getFloat(key: String, defValue: Float): Float
    fun getBoolean(key: String, defValue: Boolean): Boolean
    fun contains(key: String): Boolean
    val all: Map<String, Any?>
    fun edit(): Editor

    interface Editor {
        fun putString(key: String, value: String?): Editor
        fun putStringSet(key: String, values: Set<String>?): Editor
        fun putInt(key: String, value: Int): Editor
        fun putLong(key: String, value: Long): Editor
        fun putFloat(key: String, value: Float): Editor
        fun putBoolean(key: String, value: Boolean): Editor
        fun remove(key: String): Editor
        fun clear(): Editor
        fun commit(): Boolean
        fun apply()
    }
}

internal class StoredPreferences(private val name: String, private val secure: Boolean) : SharedPreferences {
    private val lock = PlatformLock()
    private val values: MutableMap<String, Any?> = load()

    private fun storageKey() = "prefs.$name"

    private fun load(): MutableMap<String, Any?> {
        val raw = if (secure) SecureStore.read(storageKey()) else com.music.bitchord.platform.KeyValueStore.getString(storageKey())
        val obj = raw?.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() } ?: return mutableMapOf()
        return obj.mapValuesTo(LinkedHashMap()) { (_, v) -> decode(v) }
    }

    private fun save() {
        val json = buildJsonObject {
            values.forEach { (k, v) -> put(k, encode(v)) }
        }.toString()
        if (secure) SecureStore.write(storageKey(), json)
        else com.music.bitchord.platform.KeyValueStore.putString(storageKey(), json)
    }

    // Typed encoding: {"t":"i","v":3}
    private fun encode(v: Any?): JsonObject = buildJsonObject {
        when (v) {
            is String -> { put("t", "s"); put("v", v) }
            is Int -> { put("t", "i"); put("v", v) }
            is Long -> { put("t", "l"); put("v", v) }
            is Float -> { put("t", "f"); put("v", v) }
            is Boolean -> { put("t", "b"); put("v", v) }
            is Set<*> -> { put("t", "ss"); put("v", JsonArray(v.map { JsonPrimitive(it.toString()) })) }
            else -> put("t", "n")
        }
    }

    private fun decode(e: kotlinx.serialization.json.JsonElement): Any? {
        val o = e as? JsonObject ?: return null
        val v = o["v"]
        return when (o["t"]?.jsonPrimitive?.contentOrNull) {
            "s" -> v?.jsonPrimitive?.contentOrNull
            "i" -> v?.jsonPrimitive?.contentOrNull?.toIntOrNull()
            "l" -> v?.jsonPrimitive?.contentOrNull?.toLongOrNull()
            "f" -> v?.jsonPrimitive?.contentOrNull?.toFloatOrNull()
            "b" -> v?.jsonPrimitive?.booleanOrNull
            "ss" -> v?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }?.toSet()
            else -> null
        }
    }

    override fun getString(key: String, defValue: String?) = lock.withLock { values[key] as? String ?: defValue }
    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String, defValues: Set<String>?) =
        lock.withLock { values[key] as? Set<String> ?: defValues }
    override fun getInt(key: String, defValue: Int) = lock.withLock { (values[key] as? Number)?.toInt() ?: defValue }
    override fun getLong(key: String, defValue: Long) = lock.withLock { (values[key] as? Number)?.toLong() ?: defValue }
    override fun getFloat(key: String, defValue: Float) = lock.withLock { (values[key] as? Number)?.toFloat() ?: defValue }
    override fun getBoolean(key: String, defValue: Boolean) = lock.withLock { values[key] as? Boolean ?: defValue }
    override fun contains(key: String) = lock.withLock { values.containsKey(key) }
    override val all: Map<String, Any?> get() = lock.withLock { values.toMap() }

    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        private val changes = mutableMapOf<String, Any?>()
        private val removals = mutableSetOf<String>()
        private var clearAll = false

        override fun putString(key: String, value: String?) = apply { if (value == null) remove(key) else changes[key] = value }
        override fun putStringSet(key: String, values: Set<String>?) = apply { if (values == null) remove(key) else changes[key] = values.toSet() }
        override fun putInt(key: String, value: Int) = apply { changes[key] = value }
        override fun putLong(key: String, value: Long) = apply { changes[key] = value }
        override fun putFloat(key: String, value: Float) = apply { changes[key] = value }
        override fun putBoolean(key: String, value: Boolean) = apply { changes[key] = value }
        override fun remove(key: String) = apply { removals += key; changes.remove(key) }
        override fun clear() = apply { clearAll = true }

        override fun commit(): Boolean {
            lock.withLock {
                if (clearAll) values.clear()
                removals.forEach { values.remove(it) }
                values.putAll(changes)
                save()
            }
            return true
        }

        override fun apply() {
            commit()
        }
    }

    companion object {
        private val opened = mutableMapOf<String, StoredPreferences>()
        private val registry = PlatformLock()

        fun open(name: String, secure: Boolean): SharedPreferences = registry.withLock {
            opened.getOrPut("$secure:$name") { StoredPreferences(name, secure) }
        }
    }
}
