package com.openminis.app.diagnostics
class MemorySnapshot { companion object { fun capture() = MemorySnapshot() } }
object LargeAllocProbe {
    const val NOTABLE_BYTES = 1024 * 1024
    fun report(label: String, bytes: Long, detail: String, before: MemorySnapshot, started: Long, failure: Throwable?) {}
}
