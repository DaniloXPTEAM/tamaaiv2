package android.os
object Build { const val MODEL = "host"; object VERSION { const val RELEASE = "host" } }
object SystemClock { fun elapsedRealtime(): Long = System.nanoTime() / 1_000_000 }
