package ai.tama.router

import android.content.Context

/**
 * Process-local ordering for the parallel app's asynchronous sign-in flows.
 * Starting a newer sign-in, changing credentials, changing a known route or
 * deleting a provider makes an older result ineligible to replace the account.
 * A ticket contains no token, callback URL or user data and is never persisted.
 *
 * All actions are short, non-suspending preference updates under the same lock
 * as consent reviews. This does not coordinate other processes or uncooperative
 * writers, and it does not make multiple preference files crash-atomic.
 */
internal object OAuthLoginAttempts {
    internal class Ticket internal constructor(
        internal val providerId: String,
        internal val sequence: Long,
        internal val accountVersion: Long,
        /** Global ordering for attempts sharing a loopback callback port. */
        internal val order: Long,
    )

    // Access only under AutoModelStore's credential lock, never acquire the
    // provider-config lock from this object or from its callbacks.
    private val sequences = mutableMapOf<String, Long>()
    private var globalOrder = 0L

    private fun advance(providerId: String): Long {
        require(providerId.isNotBlank())
        val previous = sequences[providerId] ?: 0L
        check(previous < Long.MAX_VALUE) { "OAuth login sequence exhausted" }
        return (previous + 1).also { sequences[providerId] = it }
    }

    fun begin(providerId: String): Ticket = AutoModelStore.credentialTransaction {
        check(globalOrder < Long.MAX_VALUE) { "OAuth login order exhausted" }
        globalOrder += 1
        Ticket(providerId, advance(providerId), AutoModelRuntime.accountVersion(providerId), globalOrder)
    }

    /** A deliberate credential edit cancels older logins even if the value is unchanged. */
    fun supersede(providerId: String) = AutoModelStore.credentialTransaction { advance(providerId); Unit }

    fun isCurrent(ticket: Ticket): Boolean = AutoModelStore.credentialTransaction {
        sequences[ticket.providerId] == ticket.sequence &&
            AutoModelRuntime.accountVersion(ticket.providerId) == ticket.accountVersion
    }

    /** Revoke old consent and write the new credential only if this attempt still owns it. */
    fun commit(
        context: Context,
        ticket: Ticket,
        active: () -> Boolean,
        write: () -> Unit,
    ): Boolean = AutoModelStore.credentialTransaction {
        if (!active() || sequences[ticket.providerId] != ticket.sequence ||
            AutoModelRuntime.accountVersion(ticket.providerId) != ticket.accountVersion) {
            false
        } else {
            AutoModelStore.accountChanged(context, ticket.providerId)
            write()
            true
        }
    }
}
