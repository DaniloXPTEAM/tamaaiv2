package io.github.nanomuse.media
import android.content.Context
import android.graphics.Bitmap
import com.openminis.app.data.model.ProviderInstance
import io.github.nanomuse.avatar.ImageGen
object VideoGen {
 data class Endpoint(val instance:ProviderInstance, val apiKey:String, val model:String) {
  val instanceId get() = instance.id
  val label get() = instance.label
 }
 suspend fun imageToVideo(endpoint:Endpoint, reference:Bitmap, prompt:String, seconds:Int, beforeRequest:()->Unit, onPossibleTaskSubmission:()->Unit):ByteArray = error("not used by host test")
 suspend fun textToVideo(endpoint:Endpoint, prompt:String, seconds:Int, ratio:String, beforeRequest:()->Unit, onPossibleTaskSubmission:()->Unit):ByteArray = error("not used by host test")
}
object MediaModels {
 const val DEEP_LINK = "minis://settings/media"
 fun imageEndpoint(context:Context):ImageGen.Endpoint? = null
 fun agentImageEndpoint(context:Context):ImageGen.Endpoint? = null
 fun videoEndpoint(context:Context):VideoGen.Endpoint? = null
 fun videoChoiceRevision():Long = 0
}
