package com.openminis.app.sandbox
import java.io.File
class NativeOffloadRequest(
    val pid: Int,
    val argv: List<String>,
    val env: Map<String, String>,
    val cwd: String,
    val sessionId: String? = null,
    val executionPeer: ai.tama.sandbox.OffloadPeerLease? = null,
)
class NativeOffloadResult(val exitCode:Int,val output:String)
fun interface NativeOffloadHandler { fun handle(request:NativeOffloadRequest): NativeOffloadResult }
object PRootKernel {
    val bindMounts = mutableMapOf<String,String>()
    fun resolveHostPath(path:String):File? = File(path)
}
