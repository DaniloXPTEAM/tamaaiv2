package android.content
import java.io.File
open class Context(val filesDir: File)
interface SharedPreferences {
    fun getString(key: String, defValue: String?): String?
    fun edit(): Editor
    interface Editor { fun putString(key: String, value: String): Editor; fun remove(key: String): Editor; fun apply() }
}
