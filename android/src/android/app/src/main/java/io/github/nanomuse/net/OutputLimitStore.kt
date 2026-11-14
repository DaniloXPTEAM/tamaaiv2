package io.github.nanomuse.net

import android.content.Context
import android.content.SharedPreferences
import java.security.MessageDigest
import org.json.JSONObject

/**
 * A successful retry's observed output ceiling, scoped to the exact provider route and model.
 * Entries expire so a provider's later model upgrade does not keep an old cap forever. Keys are
 * hashes; neither the endpoint nor the model identifier or response text is saved in the value.
 */
class OutputLimitStore(private val preferences: SharedPreferences) {
    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences("nm_observed_output_limits", Context.MODE_PRIVATE),
    )

    fun load(providerKey: String, modelId: String, now: Long = System.currentTimeMillis()): Int? = runCatching {
        val saved = preferences.getString(key(providerKey, modelId), null) ?: return@runCatching null
        val json = JSONObject(saved)
        val limit = json.getInt("limit")
        val at = json.getLong("at")
        if (limit !in 1..1_000_000 || at < 0 || now < at || now - at > MAX_AGE_MS) null else limit
    }.getOrNull()

    /** Only call after the provider accepted a retry at this ceiling. */
    fun save(providerKey: String, modelId: String, limit: Int, now: Long = System.currentTimeMillis()) {
        require(limit in 1..1_000_000 && now >= 0)
        val value = JSONObject().put("limit", limit).put("at", now).toString()
        preferences.edit().putString(key(providerKey, modelId), value).apply()
    }

    private fun key(providerKey: String, modelId: String): String {
        val identity = "${providerKey.length}:$providerKey${modelId.length}:$modelId"
        val digest = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
        return "v1:" + digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    companion object {
        const val MAX_AGE_MS: Long = 7L * 24 * 60 * 60 * 1000
    }
}
