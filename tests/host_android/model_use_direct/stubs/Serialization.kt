package kotlinx.serialization
import kotlin.reflect.KClass
annotation class Serializable(val with: KClass<*> = Nothing::class)
annotation class SerialName(val value: String)
annotation class Transient
