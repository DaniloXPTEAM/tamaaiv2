package ai.tama.sandbox

/** Admission at the host socket; a session string supplied by the guest is not identity. */
internal object OffloadPeerPolicy {
    data class Decision(val allowed: Boolean, val sessionId: String?, val refusal: String? = null)

    fun resolve(
        parallel: Boolean, peer: OffloadPeerLease?, guestSession: String?, name: String, argv: List<String>,
    ): Decision {
        if (!parallel) return Decision(true, guestSession)
        if (peer != null) {
            return if (peer.isCurrent()) Decision(true, peer.sessionId)
            else Decision(false, null, "native_offload: shell session has stopped\n")
        }
        // Unmanaged shells retain global, non-media offloads, but cannot claim a
        // chat's grants/attachments or make an untracked image/video request.
        if (guestSession != null) return Decision(false, null, "native_offload: unrecognized shell session\n")
        if (name == "nanomuse-media") {
            val args = argv.drop(1)
            val readOnly = args.isEmpty() || (args.size == 1 && args[0] in setOf("status", "help", "--help", "-h"))
            if (!readOnly) return Decision(false, null, "native_offload: media requires a managed chat shell in this test app\n")
        }
        return Decision(true, null)
    }
}
