package android.os

object Process {
    fun myPid(): Int = 123
}

object SystemClock {
    fun elapsedRealtime(): Long = System.nanoTime() / 1_000_000L
}
