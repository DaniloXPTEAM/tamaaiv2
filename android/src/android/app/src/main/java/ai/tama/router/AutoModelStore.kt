package ai.tama.router

import android.content.Context
import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.model.ProviderInstance
import java.security.MessageDigest

/**
 * Device-local consent and capability evidence for an exact model route. None of these
 * preferences contains credentials, prompts or replies. A changed model id, endpoint, auth
 * mode or provider instance gets a different key and cannot inherit automatic-use consent.
 */
object AutoModelStore {
    private const val PREFS = "tama_auto_models"
    private const val MODE = "enabled"
    private const val ALLOW_UNVERIFIED_COST = "allow_unverified_automatic_cost"

    data class Identity(val entryId: String, val providerId: String, val modelId: String, val route: String)

    /** A settings screen's immutable view, with no credential or endpoint text. */
    internal data class Review(
        val identity: Identity,
        val accountVersion: Long,
        val reviewVersion: Long,
        val chatApproved: Boolean,
        val imageApproved: Boolean,
    )

    // A second settings screen or a learned refusal must not be overwritten by
    // a screen that has been open since before that change. Process-local only.
    private val reviewVersions = mutableMapOf<String, Long>()
    // Process-local witness for automatic mode/cost settings. A switch away and
    // back invalidates an already-running media attempt, not only an open dialog.
    private var automaticPolicyRevision = 0L
    @Synchronized
    internal fun automaticPolicyRevision(): Long = automaticPolicyRevision
    private fun advanceAutomaticPolicy() {
        check(automaticPolicyRevision < Long.MAX_VALUE) { "automatic policy version exhausted" }
        automaticPolicyRevision++
    }

    private fun advanceReview(providerId: String) {
        val previous = reviewVersions[providerId] ?: 0L
        check(previous < Long.MAX_VALUE) { "consent review version exhausted" }
        reviewVersions[providerId] = previous + 1
    }

    fun identity(entry: ModelEntry, instance: ProviderInstance): Identity {
        require(entry.providerInstanceId == instance.id)
        return Identity(entry.id, instance.id, entry.model.id, routeFingerprint(instance))
    }

    /** No raw endpoint or credential is stored in the route key. */
    fun routeFingerprint(instance: ProviderInstance): String =
        digest(listOf(instance.providerType.name, instance.credentialType.name, instance.effectiveBaseURL.orEmpty(),
            instance.useResponsesAPI.toString(), instance.azureMode.toString(), instance.imageEndpointMode.name))

    private fun digest(parts: List<String>): String {
        val md = MessageDigest.getInstance("SHA-256")
        // Length prefixes prevent ambiguity when a provider or model name contains a separator.
        parts.forEach { value ->
            val bytes = value.toByteArray(Charsets.UTF_8)
            md.update(byteArrayOf((bytes.size ushr 24).toByte(), (bytes.size ushr 16).toByte(),
                (bytes.size ushr 8).toByte(), bytes.size.toByte()))
            md.update(bytes)
        }
        return md.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun providerPrefix(identity: Identity) = "p.${digest(listOf(identity.providerId))}."
    private fun prefix(identity: Identity) = providerPrefix(identity) +
        digest(listOf(identity.entryId, identity.providerId, identity.modelId, identity.route)) + "."
    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun historyGenerationKey(providerId: String) = "history.${digest(listOf(providerId))}"
    private fun currentRouteKey(providerId: String) = "p.${digest(listOf(providerId))}.current_route"

    /**
     * Serialize credential reads/writes with account invalidation and review.
     * Never suspend or perform network I/O here. Do not acquire the provider-config
     * lock from this callback: a caller that needs both must already hold that lock.
     * Model-list commits may synchronously write local storage while both are held,
     * so they run on an IO worker and credential writers can briefly wait for them.
     * This coordinates cooperating writers in this process only.
     */
    @Synchronized
    internal fun <T> credentialTransaction(action: () -> T): T = action()

    /** Account generation is random, local and contains no key or account identifier. */
    @Synchronized
    fun historyFingerprint(context: Context, instance: ProviderInstance): String {
        val p = prefs(context)
        val key = historyGenerationKey(instance.id)
        val generation = p.getString(key, null) ?: java.util.UUID.randomUUID().toString().also {
            p.edit().putString(key, it).apply()
        }
        return digest(listOf(instance.id, routeFingerprint(instance), generation))
    }

    private fun rotateHistoryGeneration(context: Context, providerId: String) {
        prefs(context).edit().putString(historyGenerationKey(providerId), java.util.UUID.randomUUID().toString()).apply()
    }

    /**
     * Revoke old approvals when a provider route changes, including a later switch back.
     * The stored value is only a hash; capabilities belong to the old route as well.
     */
    @Synchronized
    fun synchronizeRoute(context: Context, providerId: String, route: String) {
        require(providerId.isNotBlank() && route.isNotBlank())
        val p = prefs(context)
        val key = currentRouteKey(providerId)
        val previous = p.getString(key, null)
        if (previous != null && previous != route) {
            revokeProvider(context, providerId)
            rotateHistoryGeneration(context, providerId)
            AutoModelRuntime.invalidateAccount(providerId)
        }
        p.edit().putString(key, route).apply()
    }

    /** Changing credentials or logging into another account requires a fresh approval. */
    @Synchronized
    fun accountChanged(context: Context, providerId: String) {
        revokeProvider(context, providerId)
        rotateHistoryGeneration(context, providerId)
        AutoModelRuntime.invalidateAccount(providerId)
    }

    /** Manual is the safe default until the person turns automatic selection on. */
    fun enabled(context: Context): Boolean = prefs(context).getBoolean(MODE, false)

    /** Read mode and revision under the same lock used by cooperating settings writers. */
    @Synchronized
    internal fun directModelMode(context: Context): ai.tama.net.DirectModelUseGuard.Mode =
        ai.tama.net.DirectModelUseGuard.Mode(enabled(context), automaticPolicyRevision)

    @Synchronized
    fun setEnabled(context: Context, enabled: Boolean) {
        advanceAutomaticPolicy()
        prefs(context).edit().putBoolean(MODE, enabled).apply()
    }

    /**
     * Temporary fail-closed guard for automatic routes in the parallel app. No
     * current catalogue quote alone proves all charges are zero. Only a separate,
     * explicit confirmation can enable automatic use of unverified-cost routes.
     * This does not protect manual calls or services outside these dispatch paths.
     */
    fun allowUnverifiedAutomaticCost(context: Context): Boolean = try {
        prefs(context).getBoolean(ALLOW_UNVERIFIED_COST, false)
    } catch (_: Exception) {
        // An unreadable or wrong-typed preference must never grant cost permission.
        false
    }
    @Synchronized
    fun setAllowUnverifiedAutomaticCost(context: Context, allowed: Boolean) {
        advanceAutomaticPolicy()
        prefs(context).edit().putBoolean(ALLOW_UNVERIFIED_COST, allowed).apply()
    }

    /** Route consent is separate from cost evidence and the automatic-cost guard. */
    @Synchronized
    fun approved(context: Context, identity: Identity): Boolean {
        synchronizeRoute(context, identity.providerId, identity.route)
        return prefs(context).getBoolean(prefix(identity) + "approved", false)
    }
    @Synchronized
    fun setApproved(context: Context, identity: Identity, approved: Boolean) {
        synchronizeRoute(context, identity.providerId, identity.route)
        prefs(context).edit().putBoolean(prefix(identity) + "approved", approved).apply()
        advanceReview(identity.providerId)
    }

    /** Image endpoints can charge per picture; chat consent alone never authorizes them. */
    @Synchronized
    fun imageApproved(context: Context, identity: Identity): Boolean {
        synchronizeRoute(context, identity.providerId, identity.route)
        return prefs(context).getBoolean(prefix(identity) + "image_approved", false)
    }

    @Synchronized
    fun setImageApproved(context: Context, identity: Identity, approved: Boolean) {
        synchronizeRoute(context, identity.providerId, identity.route)
        prefs(context).edit().putBoolean(prefix(identity) + "image_approved", approved).apply()
        advanceReview(identity.providerId)
    }

    /** Read both approvals and their generations together when opening a review. */
    @Synchronized
    internal fun review(context: Context, identity: Identity): Review {
        synchronizeRoute(context, identity.providerId, identity.route)
        val preferences = prefs(context)
        return Review(identity, AutoModelRuntime.accountVersion(identity.providerId),
            reviewVersions[identity.providerId] ?: 0L,
            preferences.getBoolean(prefix(identity) + "approved", false),
            preferences.getBoolean(prefix(identity) + "image_approved", false))
    }

    /** Check an in-flight image selection without restoring an obsolete route or grant. */
    @Synchronized
    internal fun imageReviewCurrent(
        context: Context, identity: Identity, accountVersion: Long, reviewVersion: Long?,
    ): Boolean {
        if (reviewVersion == null || (reviewVersions[identity.providerId] ?: 0L) != reviewVersion) return false
        val preferences = prefs(context)
        return preferences.getString(currentRouteKey(identity.providerId), null) == identity.route &&
            preferences.getBoolean(prefix(identity) + "image_approved", false) &&
            AutoModelRuntime.accountVersion(identity.providerId) == accountVersion
    }

    /**
     * Commit a review only if the account, route and other reviews are still unchanged.
     * Never synchronize an old route here: that would revoke the new route's approvals.
     */
    @Synchronized
    internal fun saveReview(
        context: Context, review: Review, chat: Boolean, image: Boolean, edits: Map<Need, Boolean>,
    ): Boolean {
        val identity = review.identity
        val preferences = prefs(context)
        if (preferences.getString(currentRouteKey(identity.providerId), null) != identity.route ||
            (reviewVersions[identity.providerId] ?: 0L) != review.reviewVersion) return false
        return AutoModelRuntime.runIfCurrent(identity.providerId, review.accountVersion) {
            val editor = preferences.edit()
                .putBoolean(prefix(identity) + "approved", chat)
                .putBoolean(prefix(identity) + "image_approved", image)
            for ((need, value) in edits) {
                editor.putBoolean(capabilityKey(identity, need), value)
                if (need == Need.TOOLS && value) editor.remove(deniedToolsKey(identity))
            }
            editor.apply()
            advanceReview(identity.providerId)
        }
    }

    private fun capabilityKey(identity: Identity, need: Need) = prefix(identity) + "cap." + need.name
    private fun deniedToolsKey(identity: Identity) = prefix(identity) + "tools_refused"

    /** Null inherits only exact-model catalogue evidence; false is an explicit negative. */
    @Synchronized
    fun override(context: Context, identity: Identity, need: Need): Boolean? {
        synchronizeRoute(context, identity.providerId, identity.route)
        val preferences = prefs(context)
        val key = capabilityKey(identity, need)
        return if (preferences.contains(key)) preferences.getBoolean(key, false) else null
    }

    /** An explicit user assertion of tool support clears a previously learned refusal. */
    @Synchronized
    fun setCapability(context: Context, identity: Identity, need: Need, value: Boolean?) {
        synchronizeRoute(context, identity.providerId, identity.route)
        val edit = prefs(context).edit()
        val key = capabilityKey(identity, need)
        if (value == null) edit.remove(key) else edit.putBoolean(key, value)
        if (need == Need.TOOLS && value == true) edit.remove(deniedToolsKey(identity))
        edit.apply()
        advanceReview(identity.providerId)
    }

    @Synchronized
    fun noteUnsupportedTools(context: Context, identity: Identity) {
        synchronizeRoute(context, identity.providerId, identity.route)
        prefs(context).edit().putBoolean(deniedToolsKey(identity), true).apply()
        advanceReview(identity.providerId)
    }

    /** Do not attach an old response's learned refusal to a replacement account or route. */
    @Synchronized
    internal fun noteUnsupportedToolsIfCurrent(context: Context, identity: Identity, expected: Long?): Boolean {
        val preferences = prefs(context)
        // Unlike synchronizeRoute, a stale response must not change the current route.
        if (preferences.getString(currentRouteKey(identity.providerId), null) != identity.route) return false
        return AutoModelRuntime.runIfCurrent(identity.providerId, expected) {
            preferences.edit().putBoolean(deniedToolsKey(identity), true).apply()
            advanceReview(identity.providerId)
        }
    }

    @Synchronized
    fun toolsWereRefused(context: Context, identity: Identity): Boolean {
        synchronizeRoute(context, identity.providerId, identity.route)
        return prefs(context).getBoolean(deniedToolsKey(identity), false)
    }

    /** Only exact-model metadata and explicit user assertions are considered; unknown is absent. */
    @Synchronized
    fun capabilities(context: Context, identity: Identity, exactModelCapabilities: Set<String>): Set<Need> {
        val fromCatalogue = buildSet {
            if ("chat" in exactModelCapabilities) add(Need.CHAT)
            if ("tools" in exactModelCapabilities) add(Need.TOOLS)
            if ("vision" in exactModelCapabilities) add(Need.VISION)
            if ("image_gen" in exactModelCapabilities) add(Need.IMAGE_GENERATION)
        }
        return Need.entries.filterTo(mutableSetOf()) { need -> override(context, identity, need) ?: (need in fromCatalogue) }
            .also { if (toolsWereRefused(context, identity)) it.remove(Need.TOOLS) }
    }

    /** Explicit revocation after changing an account; other providers and mode stay untouched. */
    @Synchronized
    fun revokeProvider(context: Context, providerId: String) {
        val preferences = prefs(context)
        val providerPrefix = "p.${digest(listOf(providerId))}."
        val edit = preferences.edit()
        preferences.all.keys.filter { it.startsWith(providerPrefix) }.forEach(edit::remove)
        edit.apply()
        advanceReview(providerId)
    }
}
