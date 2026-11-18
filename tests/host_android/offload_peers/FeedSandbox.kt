package io.github.nanomuse.sandbox

import android.content.Context
import java.io.File

object FeedSandboxScope {
    const val LINUX_ROOT = "/var/minis/feed"
    @Volatile var onRefresh: (() -> Unit)? = null
    fun refresh(context: Context, sessionId: String): File {
        onRefresh?.invoke()
        return File(context.filesDir, "feed-$sessionId").also { it.mkdirs() }
    }
    fun clear(context: Context, sessionId: String) {}
}
