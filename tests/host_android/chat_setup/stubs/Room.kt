package androidx.room

import kotlin.reflect.KClass

// Compile-time stand-ins only. They do not implement Room or a database.
@Target(AnnotationTarget.CLASS)
annotation class Entity(
    val tableName: String = "",
    val foreignKeys: Array<ForeignKey> = [],
    val indices: Array<Index> = [],
)

annotation class ForeignKey(
    val entity: KClass<*>,
    val parentColumns: Array<String>,
    val childColumns: Array<String>,
    val onDelete: Int = 0,
) {
    companion object { const val CASCADE = 5 }
}

annotation class Index(val value: Array<String>)

@Target(AnnotationTarget.FIELD, AnnotationTarget.PROPERTY, AnnotationTarget.VALUE_PARAMETER)
annotation class ColumnInfo(val name: String)

@Target(AnnotationTarget.FIELD, AnnotationTarget.PROPERTY, AnnotationTarget.VALUE_PARAMETER)
annotation class PrimaryKey
