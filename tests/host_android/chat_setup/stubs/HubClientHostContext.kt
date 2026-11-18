package android.content

/** Return resource IDs as strings; these checks do not render Android UI. */
open class Context {
    open fun getString(id: Int): String = "resource-$id"
}
