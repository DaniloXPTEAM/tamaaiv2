package android.util
object Base64 {
    const val NO_WRAP: Int = 2
    const val DEFAULT: Int = 0
    fun decode(input: String, flags: Int): ByteArray = java.util.Base64.getDecoder().decode(input)
    fun encodeToString(input: ByteArray, flags: Int): String = java.util.Base64.getEncoder().encodeToString(input)
}
object Log {
    fun d(tag: String, msg: String): Int = 0
    fun i(tag: String, msg: String): Int = 0
    fun w(tag: String, msg: String): Int = 0
    fun w(tag: String, msg: String, error: Throwable): Int = 0
    fun e(tag: String, msg: String): Int = 0
}
