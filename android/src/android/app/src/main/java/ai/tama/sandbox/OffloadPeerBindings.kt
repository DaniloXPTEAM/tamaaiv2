package ai.tama.sandbox

import java.io.IOException
import java.io.InputStream

/**
 * The host-created lifetime of one persistent PRoot process. This is not a
 * permission: it only binds a socket peer to the session for which the host
 * launched that process. It cannot retract a request already handed to I/O.
 */
class OffloadPeerLease internal constructor(
    val sessionId: String,
    private val processAlive: () -> Boolean,
) {
    internal class Expired : Exception("Offload process is no longer current")

    private var revoked = false

    @Synchronized
    fun isCurrent(): Boolean = !revoked && try { processAlive() } catch (_: Exception) { false }

    /** Serialize a conservative submission marker with revocation, not with I/O. */
    @Synchronized
    internal fun <T> whileCurrent(action: () -> T): T {
        if (!isCurrent()) throw Expired()
        return action()
    }

    @Synchronized
    internal fun revoke() { revoked = true }
}

/** Peer PIDs come from LocalSocket's kernel credentials, never from the wire body. */
internal object OffloadPeerBindings {
    private val peers = mutableMapOf<Int, OffloadPeerLease>()

    @Synchronized
    fun register(pid: Int, sessionId: String, processAlive: () -> Boolean): OffloadPeerLease {
        require(pid > 0)
        require(ai.tama.net.NativeOffloadSession.valid(true, sessionId))
        val previous = peers[pid]
        check(previous?.isCurrent() != true) { "A live offload process already owns this PID" }
        previous?.revoke()
        return OffloadPeerLease(sessionId, processAlive).also { peers[pid] = it }
    }

    /** Capture at accept, before a delayed worker can be confused with a later process. */
    @Synchronized
    fun capture(pid: Int): OffloadPeerLease? = peers[pid]?.takeIf { it.isCurrent() }

    /** Releasing an old process must not remove a replacement with the same PID. */
    @Synchronized
    fun release(pid: Int, lease: OffloadPeerLease) {
        lease.revoke()
        if (peers[pid] === lease) peers.remove(pid)
    }
}

/**
 * Android's supported Process API does not expose the child PID on our target
 * API. A fixed host shell reports its own PID, then waits for the parent to
 * register it before exec replaces it with PRoot. Arguments are passed as argv,
 * not interpolated into shell code. The child cannot run PRoot before the ack.
 */
internal object OffloadPeerLaunch {
    private const val SCRIPT = "printf '%s\\n' \"\$\$\"; IFS= read -r ack || exit 1; " +
        "[ \"\$ack\" = TAMA_READY ] || exit 1; exec \"\$@\""
    const val ACK = "TAMA_READY\n"

    fun wrap(command: List<String>, hostShell: String = "/system/bin/sh"): List<String> {
        require(command.isNotEmpty())
        return listOf(hostShell, "-c", SCRIPT, "tama-offload-launcher") + command
    }

    /** Read only the PID line: do not buffer bytes belonging to PRoot's stdout. */
    fun readPid(input: InputStream): Int {
        val digits = StringBuilder()
        while (true) {
            val c = input.read()
            if (c == '\n'.code) break
            if (c !in '0'.code..'9'.code || digits.length >= 10) throw IOException("Invalid launcher PID")
            digits.append(c.toChar())
        }
        val pid = digits.toString().toIntOrNull()
        if (pid == null || pid <= 0) throw IOException("Invalid launcher PID")
        return pid
    }
}
