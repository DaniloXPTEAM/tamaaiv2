package ai.tama.media
import android.content.Context
import ai.tama.router.AutoModelStore
import io.github.nanomuse.avatar.ImageGen
object AutoImageSelection {
 data class Selection(val entryId:String,val providerId:String,val modelId:String,val identity:AutoModelStore.Identity,val accountVersion:Long,val reviewVersion:Long?=null)
 fun choose(context:Context):Selection? = null
 fun endpointIfStillAllowed(context:Context, selection:Selection):ImageGen.Endpoint? = null
 fun hasRegisteredImage(context:Context):Boolean = false
}
