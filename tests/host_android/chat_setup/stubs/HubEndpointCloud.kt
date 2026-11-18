package io.github.nanomuse.cloud

import android.content.Context

object NanoMuseCloud {
    data class Account(val accountId: String)
    fun account(@Suppress("UNUSED_PARAMETER") context: Context): Account? = null
}
