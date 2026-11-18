package io.github.nanomuse.net
object OwnProviderProxy {
    val authenticator = okhttp3.Authenticator.NONE
    fun hostOf(url: String): String = java.net.URI(url).host.orEmpty()
    fun isRelay(url: String): Boolean = false
}
