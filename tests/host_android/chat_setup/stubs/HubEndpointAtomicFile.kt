package android.util

import java.io.File
import java.io.FileOutputStream

/** The host tests inject a memory journal and never use Android AtomicFile. */
class AtomicFile(@Suppress("UNUSED_PARAMETER") file: File) {
    fun readFully(): ByteArray = error("AtomicFile is not exercised by host tests")
    fun startWrite(): FileOutputStream = error("AtomicFile is not exercised by host tests")
    fun finishWrite(@Suppress("UNUSED_PARAMETER") stream: FileOutputStream) = Unit
    fun failWrite(@Suppress("UNUSED_PARAMETER") stream: FileOutputStream) = Unit
}
