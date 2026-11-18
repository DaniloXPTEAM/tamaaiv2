package com.openminis.app.sandbox

import android.content.Context
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow

class RootfsManager private constructor(val rootfsDir: File, val prootBinary: File) {
    companion object {
        lateinit var binary: File
        fun getInstance(context: Context) = RootfsManager(context.filesDir, binary)
    }
}
class MountedEntry(val name: String, val resolvedHostPath: String?)
class MountedFoldersStore { val entries = MutableStateFlow<List<MountedEntry>>(emptyList()) }
object PRootKernel {
    var isBooted = true
    var nativeLibDir = ""
    var prootLoaderPath = ""
    var prootLoader32Path = ""
    val customEnvironment = mutableMapOf<String, String>()
    val bindMounts = mutableMapOf<String, String>()
    var mountedFoldersStore: MountedFoldersStore? = null
    fun boot(context: Context) { isBooted = true }
    fun getProotTmpDir(context: Context) = context.filesDir
    fun posixTz() = "UTC"
    fun addBindMount(linux: String, host: String) { bindMounts[linux] = host }
    fun updateTimezone() = "UTC"
    fun updateProxy(context: Context) = emptyMap<String, String>()
}
object TerminalSanitizer {
    fun sanitize(value: String) = value
    fun truncateIfNeeded(value: String) = value
}
object ShellExecutor { fun destroyCurrent() {} }
object TerminalSession {
    fun broadcastTimezone(value: String) {}
    fun broadcastProxy(value: Map<String, String>) {}
}
