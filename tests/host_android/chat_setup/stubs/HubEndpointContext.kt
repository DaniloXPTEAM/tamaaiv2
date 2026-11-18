package android.content

import java.io.File

/** Only the journal's unused Android factory needs this in the host fixture. */
open class Context {
    open val applicationContext: Context get() = this
    val noBackupFilesDir: File get() = error("Android journal factory is not exercised by host tests")
}
