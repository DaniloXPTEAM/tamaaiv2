package io.github.nanomuse.account

import java.util.Collections

// Records calls; this is not the real asynchronous ownership database.
object AccountData {
    val claimed = Collections.synchronizedSet(mutableSetOf<String>())
    fun claim(sessionId: String) { claimed.add(sessionId) }
}
