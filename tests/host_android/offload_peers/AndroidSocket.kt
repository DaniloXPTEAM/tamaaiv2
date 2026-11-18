package android.net

import java.io.InputStream
import java.io.OutputStream
import java.io.IOException

class Credentials(val pid: Int)
class LocalSocket(val inputStream: InputStream, val outputStream: OutputStream, val peerCredentials: Credentials) {
    fun close() {}
}
class LocalServerSocket(name: String) {
    fun accept(): LocalSocket = throw IOException("host fake does not accept sockets")
    fun close() {}
}
