package io.github.nanomuse.sandbox

/**
 * Incrementally removes the persistent-shell completion marker without assuming that one
 * process read contains the entire marker or its exit status. Only a possible marker prefix
 * is held back; ordinary output remains available for streaming.
 */
internal class ShellMarkerDecoder(marker: String) {
    internal data class Frame(val text: String, val exitCode: Int?)

    private val prefix = "__MINIS_DONE_${marker}_EXIT_"
    private val pending = StringBuilder()
    private var complete = false

    fun feed(chunk: String): Frame {
        if (complete) return Frame("", null)
        pending.append(chunk)
        val visible = StringBuilder()
        while (pending.isNotEmpty()) {
            val index = pending.indexOf(prefix)
            if (index < 0) {
                // A prefix at the end may be completed by the next process read.
                val value = pending.toString()
                val max = minOf(value.length, prefix.length - 1)
                val keep = (max downTo 1).firstOrNull { length ->
                    value.regionMatches(value.length - length, prefix, 0, length)
                } ?: 0
                visible.append(value, 0, value.length - keep)
                pending.delete(0, value.length - keep)
                break
            }
            if (index > 0) {
                visible.append(pending.substring(0, index))
                pending.delete(0, index)
            }

            var end = prefix.length
            while (end < pending.length && pending[end] in '0'..'9') end++
            val digits = end - prefix.length
            if (digits > 10) {
                // Not a status the shell can produce; treat the candidate as ordinary output.
                visible.append(pending[0])
                pending.deleteCharAt(0)
                continue
            }
            if (end == pending.length) break // The status itself may arrive in the next read.
            if (digits == 0 || pending[end] != '_') {
                visible.append(pending[0])
                pending.deleteCharAt(0)
                continue
            }
            if (end + 1 == pending.length) break // The second underscore may be split too.
            if (pending[end + 1] != '_') {
                visible.append(pending[0])
                pending.deleteCharAt(0)
                continue
            }
            // The shell uses echo, so its newline is part of the frame. Waiting for it also
            // prevents a delayed newline from leaking into the next command's output.
            val newline = end + 2
            if (newline == pending.length) break
            val lineEnd = when (pending[newline]) {
                '\n' -> true
                '\r' -> {
                    if (newline + 1 == pending.length) break
                    pending[newline + 1] == '\n'
                }
                else -> false
            }
            if (!lineEnd) {
                visible.append(pending[0])
                pending.deleteCharAt(0)
                continue
            }
            val exitCode = pending.substring(prefix.length, end).toIntOrNull() ?: -1
            // As before, bytes after the completion marker are not part of this command's result.
            pending.setLength(0)
            complete = true
            return Frame(visible.toString(), exitCode)
        }
        return Frame(visible.toString(), null)
    }

    /** Preserve a partial marker as output if the process dies before a complete marker. */
    fun finish(): String {
        if (complete) return ""
        val remainder = pending.toString()
        pending.setLength(0)
        complete = true
        return remainder
    }
}
