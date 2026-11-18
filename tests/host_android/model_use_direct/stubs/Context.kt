package android.content
import java.util.concurrent.ConcurrentHashMap
open class Context {
    companion object { const val MODE_PRIVATE = 0 }
    val filesDir = java.io.File(System.getProperty("java.io.tmpdir"), "tama-handler-host")
    fun getString(id: Int): String = when (id) { 1 -> "automatic mode blocked"; 2 -> "model cost unverified"; else -> "unknown" }
    private val stores = ConcurrentHashMap<String, SharedPreferences>()
    fun getSharedPreferences(name: String, mode: Int): SharedPreferences = stores.computeIfAbsent(name) { SharedPreferences() }
}
class SharedPreferences {
    private val values = mutableMapOf<String, Any>()
    val all: Map<String, *> get() = synchronized(values) { values.toMap() }
    fun contains(key: String) = synchronized(values) { values.containsKey(key) }
    fun getString(key: String, default: String?): String? = synchronized(values) { values[key]?.let { it as String } ?: default }
    // Test hook: a cooperating setting can change at a chosen read boundary.
    var beforeBooleanRead: ((String) -> Unit)? = null
    fun getBoolean(key: String, default: Boolean): Boolean {
        beforeBooleanRead?.invoke(key)
        return synchronized(values) { values[key]?.let { it as Boolean } ?: default }
    }
    fun edit() = Editor()
    inner class Editor {
        private val changes = mutableMapOf<String, Any?>()
        fun putString(key: String, value: String?) = apply { changes[key] = value }
        fun putBoolean(key: String, value: Boolean) = apply { changes[key] = value }
        fun remove(key: String) = apply { changes[key] = null }
        fun apply() = synchronized(values) { for ((key, value) in changes) if (value == null) values.remove(key) else values[key] = value }
    }
}
