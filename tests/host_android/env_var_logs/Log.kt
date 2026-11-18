package android.util
object Log {
    val messages = mutableListOf<String>()
    fun i(tag: String, message: String): Int { messages.add(message); return 0 }
    fun e(tag: String, message: String): Int { messages.add(message); return 0 }
}
