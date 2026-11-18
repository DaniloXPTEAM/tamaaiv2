package android.content

import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** In-memory host substitute; it does not claim Android preference persistence. */
open class Context(val filesDir: File = File(System.getProperty("java.io.tmpdir"))) {
    companion object { const val MODE_PRIVATE = 0 }
    fun getString(id: Int, vararg args: Any): String = when (id) {
        1 -> "Image permission or capability missing"
        2 -> "Image authorization changed"
        3 -> "Automatic cost is not verified"
        4 -> "Model cost is not verified"
        5 -> "Automatic edit unavailable"
        6 -> "Permission denied"
        7 -> "Image could not be completed"
        8 -> "Image may have been charged; do not retry"
        9 -> "Image"
        10 -> "Nenhum provedor de imagem configurado — abra Configurações → Modelos de imagem e vídeo"
        11 -> "Used ${args[0]} (${args[1]})"
        12 -> "Video route unavailable; sent requests may be charged"
        13 -> "Video may have been sent and may be charged"
        14 -> "Video authorization changed before the task"
        15 -> "Media route changed"
        else -> "unknown"
    }
    val applicationContext: Context get() = this
    private val preferences = ConcurrentHashMap<String, SharedPreferences>()
    fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
        preferences.computeIfAbsent(name) { SharedPreferences() }
}

class SharedPreferences {
    private val values = ConcurrentHashMap<String, Any>()
    val all: Map<String, *> get() = values.toMap()
    fun contains(key: String) = values.containsKey(key)
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
