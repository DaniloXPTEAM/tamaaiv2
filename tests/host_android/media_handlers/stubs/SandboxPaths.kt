package io.github.nanomuse.sandbox
import android.content.Context
import java.io.File
object SandboxPaths {
 fun normalise(path:String):String = path
 fun inside(context:Context, file:File):Boolean = false
 fun host(context:Context, path:String, sessionId:String?):File? = null
}
