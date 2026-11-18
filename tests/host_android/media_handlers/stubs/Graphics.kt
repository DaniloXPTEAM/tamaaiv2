package android.graphics
class Bitmap { enum class CompressFormat { PNG }; fun compress(format:CompressFormat, quality:Int, output:java.io.OutputStream):Boolean = false }
object BitmapFactory { fun decodeFile(path:String):Bitmap? = null }
