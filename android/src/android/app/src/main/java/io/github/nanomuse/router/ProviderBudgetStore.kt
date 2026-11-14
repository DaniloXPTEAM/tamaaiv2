package io.github.nanomuse.router

import android.content.Context

/** Local per-provider override; blank means unknown, not a guaranteed unlimited allowance. */
object ProviderBudgetStore {
    private const val PREFS = "tama_provider_budgets"
    private fun key(instanceId: String) = "tpm.$instanceId"

    fun read(context: Context, instanceId: String, catalogueTpm: Int?): Int? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val key = key(instanceId)
        if (!prefs.contains(key)) return catalogueTpm?.takeIf { it > 0 }
        return prefs.getInt(key, 0).takeIf { it > 0 }
    }

    /** Save only a positive limit, or null for unknown. No provider credential is stored here. */
    fun save(context: Context, instanceId: String, value: Int?) {
        require(value == null || value > 0)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(key(instanceId), value ?: 0).apply()
    }

    sealed interface Input {
        data class Valid(val value: Int?) : Input
        data object Invalid : Input
    }

    fun parse(text: String): Input {
        val value = text.trim()
        if (value.isEmpty()) return Input.Valid(null)
        if (!value.all { it in '0'..'9' }) return Input.Invalid
        val number = value.toIntOrNull()?.takeIf { it > 0 } ?: return Input.Invalid
        return Input.Valid(number)
    }
}
