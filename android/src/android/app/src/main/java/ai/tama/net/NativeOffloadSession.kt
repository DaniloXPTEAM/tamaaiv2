package ai.tama.net

/**
 * A guest-supplied session is later used as one directory name. Reject traversal before
 * dispatch in the parallel app. A syntactically valid id is not proof of ownership.
 */
internal object NativeOffloadSession {
    fun valid(parallel: Boolean, sessionId: String?): Boolean {
        if (!parallel || sessionId == null) return true
        return sessionId.isNotEmpty() && sessionId != "." && sessionId != ".." &&
            sessionId.toByteArray(Charsets.UTF_8).size <= 255 &&
            sessionId.none { it == '/' || it == '\\' || it.isISOControl() }
    }
}
