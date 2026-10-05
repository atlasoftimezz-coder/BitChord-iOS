package org.json

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray as KArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject as KObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/*
 * The parts of Android's org.json that the ported sources call, over
 * kotlinx.serialization. Mutable like the original; toString() serialises.
 */

class JSONException(message: String) : RuntimeException(message)

object JSONObjectNull {
    override fun toString() = "null"
}

private fun wrap(element: JsonElement?): Any? = when (element) {
    null, JsonNull -> null
    is KObject -> JSONObject(element)
    is KArray -> JSONArray(element)
    is JsonPrimitive -> when {
        element.isString -> element.content
        element.booleanOrNull != null -> element.booleanOrNull
        element.longOrNull != null -> element.longOrNull
        else -> element.doubleOrNull ?: element.content
    }
}

private fun unwrap(value: Any?): JsonElement = when (value) {
    null, JSONObjectNull -> JsonNull
    is JSONObject -> value.toElement()
    is JSONArray -> value.toElement()
    is String -> JsonPrimitive(value)
    is Boolean -> JsonPrimitive(value)
    is Number -> JsonPrimitive(value)
    is JsonElement -> value
    else -> JsonPrimitive(value.toString())
}

class JSONObject() {
    private val map = LinkedHashMap<String, Any?>()

    constructor(json: String) : this() {
        val parsed = runCatching { Json.parseToJsonElement(json) }.getOrNull() as? KObject
            ?: throw JSONException("Not a JSON object")
        parsed.forEach { (k, v) -> map[k] = wrap(v) }
    }

    internal constructor(element: KObject) : this() {
        element.forEach { (k, v) -> map[k] = wrap(v) }
    }

    fun put(name: String, value: Any?): JSONObject = apply { map[name] = value }
    fun remove(name: String): Any? = map.remove(name)
    fun has(name: String): Boolean = map.containsKey(name)
    fun isNull(name: String): Boolean = map[name] == null || map[name] == JSONObjectNull
    fun length(): Int = map.size
    fun keys(): Iterator<String> = map.keys.iterator()

    fun opt(name: String): Any? = map[name]
    fun get(name: String): Any = map[name] ?: throw JSONException("No value for $name")

    fun optString(name: String, fallback: String = ""): String = when (val v = map[name]) {
        null, JSONObjectNull -> fallback
        is String -> v
        else -> v.toString()
    }
    fun getString(name: String): String = map[name]?.toString() ?: throw JSONException("No value for $name")

    fun optInt(name: String, fallback: Int = 0): Int = number(name)?.toInt() ?: fallback
    fun getInt(name: String): Int = number(name)?.toInt() ?: throw JSONException("No int for $name")
    fun optLong(name: String, fallback: Long = 0L): Long = number(name)?.toLong() ?: fallback
    fun getLong(name: String): Long = number(name)?.toLong() ?: throw JSONException("No long for $name")
    fun optDouble(name: String, fallback: Double = Double.NaN): Double = number(name)?.toDouble() ?: fallback
    fun getDouble(name: String): Double = number(name)?.toDouble() ?: throw JSONException("No double for $name")
    fun optBoolean(name: String, fallback: Boolean = false): Boolean = when (val v = map[name]) {
        is Boolean -> v
        is String -> v.equals("true", ignoreCase = true)
        else -> fallback
    }
    fun getBoolean(name: String): Boolean = map[name] as? Boolean ?: throw JSONException("No boolean for $name")

    fun optJSONObject(name: String): JSONObject? = map[name] as? JSONObject
    fun getJSONObject(name: String): JSONObject = optJSONObject(name) ?: throw JSONException("No object for $name")
    fun optJSONArray(name: String): JSONArray? = map[name] as? JSONArray
    fun getJSONArray(name: String): JSONArray = optJSONArray(name) ?: throw JSONException("No array for $name")

    private fun number(name: String): Number? = when (val v = map[name]) {
        is Number -> v
        is String -> v.toDoubleOrNull()
        else -> null
    }

    internal fun toElement(): KObject = KObject(map.mapValues { unwrap(it.value) })
    override fun toString(): String = toElement().toString()

    companion object {
        val NULL: Any = JSONObjectNull
    }
}

class JSONArray() {
    private val list = mutableListOf<Any?>()

    constructor(json: String) : this() {
        val parsed = runCatching { Json.parseToJsonElement(json) }.getOrNull() as? KArray
            ?: throw JSONException("Not a JSON array")
        parsed.forEach { list += wrap(it) }
    }

    internal constructor(element: KArray) : this() {
        element.forEach { list += wrap(it) }
    }

    constructor(values: Collection<*>) : this() {
        list.addAll(values)
    }

    fun length(): Int = list.size
    fun put(value: Any?): JSONArray = apply { list += value }
    fun put(index: Int, value: Any?): JSONArray = apply {
        while (list.size <= index) list += null
        list[index] = value
    }
    fun opt(index: Int): Any? = list.getOrNull(index)
    fun get(index: Int): Any = list.getOrNull(index) ?: throw JSONException("No value at $index")
    fun remove(index: Int): Any? = list.removeAt(index)
    fun isNull(index: Int): Boolean = list.getOrNull(index) == null

    fun optString(index: Int, fallback: String = ""): String = list.getOrNull(index)?.toString() ?: fallback
    fun getString(index: Int): String = get(index).toString()
    fun optInt(index: Int, fallback: Int = 0): Int = (list.getOrNull(index) as? Number)?.toInt() ?: fallback
    fun getInt(index: Int): Int = (get(index) as? Number)?.toInt() ?: throw JSONException("No int at $index")
    fun optLong(index: Int, fallback: Long = 0L): Long = (list.getOrNull(index) as? Number)?.toLong() ?: fallback
    fun optDouble(index: Int, fallback: Double = Double.NaN): Double =
        (list.getOrNull(index) as? Number)?.toDouble() ?: fallback
    fun getDouble(index: Int): Double = (get(index) as? Number)?.toDouble() ?: throw JSONException("No double at $index")
    fun optBoolean(index: Int, fallback: Boolean = false): Boolean = list.getOrNull(index) as? Boolean ?: fallback
    fun optJSONObject(index: Int): JSONObject? = list.getOrNull(index) as? JSONObject
    fun getJSONObject(index: Int): JSONObject = optJSONObject(index) ?: throw JSONException("No object at $index")
    fun optJSONArray(index: Int): JSONArray? = list.getOrNull(index) as? JSONArray
    fun getJSONArray(index: Int): JSONArray = optJSONArray(index) ?: throw JSONException("No array at $index")

    internal fun toElement(): KArray = KArray(list.map(::unwrap))
    override fun toString(): String = toElement().toString()
}
