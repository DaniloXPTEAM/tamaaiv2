package com.openminis.app.util
import android.content.Context
import android.content.SharedPreferences
object EncryptedPrefsFactory {
    fun safeCreate(context: Context, name: String): SharedPreferences = MemoryPreferences()
}
private class MemoryPreferences : SharedPreferences, SharedPreferences.Editor {
    private val items = mutableMapOf<String, String>()
    override fun getString(key: String, defValue: String?) = items[key] ?: defValue
    override fun edit(): SharedPreferences.Editor = this
    override fun putString(key: String, value: String) = apply { items[key] = value }
    override fun remove(key: String) = apply { items.remove(key) }
    override fun apply() {}
}
