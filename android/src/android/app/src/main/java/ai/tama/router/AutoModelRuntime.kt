package ai.tama.router

/** Shared among chat view models in this process; no credential or conversation is retained. */
object AutoModelRuntime {
    val health: ModelHealth by lazy { ModelHealth { android.os.SystemClock.elapsedRealtime() } }
    private val accounts = AccountVersions { providerId -> health.clearAccount(providerId) }

    /** In-flight responses from an older credential/endpoint must not block the new account. */
    fun accountVersion(providerId: String): Long = accounts.current(providerId)
    /** Changes for any account, including before a media route is known. Process-local only. */
    internal fun accountRevision(): Long = accounts.revision()
    fun invalidateAccount(providerId: String) = accounts.invalidate(providerId)

    /** Run a short non-suspending update only while the dispatch generation is current. */
    internal fun runIfCurrent(providerId: String, expected: Long?, action: () -> Unit): Boolean =
        accounts.runIfCurrent(providerId, expected, action)
}
