package ai.tama.net

import io.github.nanomuse.net.*

import okhttp3.Authenticator
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import okio.BufferedSink

/** A billable image POST must not be replayed or redirected to an unapproved route. */
internal fun RequestBody.singleUseImageBody(): RequestBody {
    val original = this
    return object : RequestBody() {
        override fun contentType(): MediaType? = original.contentType()
        override fun contentLength(): Long = original.contentLength()
        override fun isOneShot(): Boolean = true
        override fun writeTo(sink: BufferedSink) = original.writeTo(sink)
    }
}

/**
 * Authenticate a proxy tunnel before a POST leaves the device, but never ask an
 * authenticator to replace a POST after a 407: a replacement body need not be one-shot.
 */
internal fun connectOnlyImageProxyAuthenticator(delegate: Authenticator): Authenticator = Authenticator { route, response ->
    if (response.request.method != "CONNECT") null
    else delegate.authenticate(route, response)?.takeIf {
        it.method == "CONNECT" && it.body == null && it.url == response.request.url
    }
}

internal fun OkHttpClient.singleUseImageClient(): OkHttpClient = newBuilder()
    .retryOnConnectionFailure(false)
    .followRedirects(false)
    .followSslRedirects(false)
    .authenticator(Authenticator.NONE)
    .proxyAuthenticator(connectOnlyImageProxyAuthenticator(proxyAuthenticator))
    .build()
