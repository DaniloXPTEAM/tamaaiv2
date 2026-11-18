package android.content

import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** In-memory host substitute; it does not claim Android preference persistence. */
open class Context(val filesDir: File) {
    companion object { const val MODE_PRIVATE = 0 }
    val applicationContext: Context get() = this
    private val preferences = ConcurrentHashMap<String, SharedPreferences>()
    fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
        preferences.computeIfAbsent(name) { SharedPreferences() }
}

class SharedPreferences {
    private val values = ConcurrentHashMap<String, Any>()
    fun getString(key: String, default: String?): String? = values[key] as? String ?: default
    fun getBoolean(key: String, default: Boolean): Boolean = values[key] as? Boolean ?: default
    fun edit() = Editor()
    inner class Editor {
        fun putString(key: String, value: String?) = apply {
            if (value == null) values.remove(key) else values[key] = value
        }
        fun putBoolean(key: String, value: Boolean) = apply { values[key] = value }
        fun remove(key: String) = apply { values.remove(key) }
        fun apply() = Unit
    }
}
