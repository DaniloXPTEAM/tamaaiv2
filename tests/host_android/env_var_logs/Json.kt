package org.json
class JSONArray {
    private val items = mutableListOf<JSONObject>()
    constructor()
    constructor(data: String) { throw IllegalArgumentException("fixture-private-content") }
    fun put(obj: JSONObject): JSONArray = apply { items.add(obj) }
    fun length(): Int = items.size
    fun getJSONObject(i: Int): JSONObject = items[i]
    override fun toString() = items.joinToString(prefix="[", postfix="]")
}
class JSONObject {
    private val values = mutableMapOf<String, Any>()
    fun put(key: String, value: Any): JSONObject = apply { values[key] = value }
    fun optString(key: String, fallback: String) = values[key] as? String ?: fallback
    fun optLong(key: String, fallback: Long) = values[key] as? Long ?: fallback
    override fun toString() = values.toString()
}
