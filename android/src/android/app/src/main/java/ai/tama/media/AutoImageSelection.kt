package ai.tama.media

import io.github.nanomuse.media.*

import android.content.Context
import com.openminis.app.MinisApp
import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.repository.ProviderRepository
import io.github.nanomuse.avatar.ImageGen
import io.github.nanomuse.avatar.ImageModelRequests
import io.github.nanomuse.cloud.NanoMuseCloud
import io.github.nanomuse.cloud.ProviderCatalogue
import ai.tama.router.AutoImageRouter
import ai.tama.router.AutoModelRuntime
import ai.tama.router.AutoModelStore
import ai.tama.router.Need

/** Agent picture calls use a separately approved, exact image model and transport. */
internal object AutoImageSelection {
    data class Selection(
        val entryId: String,
        val providerId: String,
        val modelId: String,
        val identity: AutoModelStore.Identity,
        val accountVersion: Long,
        // The parallel app must not revive a selection after consent or capability ABA.
        val reviewVersion: Long? = null,
    )

    private data class Entry(
        val candidate: AutoImageRouter.Candidate,
        val model: ModelEntry,
        val instance: ProviderInstance,
        val identity: AutoModelStore.Identity,
    )

    private fun entries(context: Context, repo: ProviderRepository): List<Entry> {
        val config = repo.config.value
        val eligible = ImageGen.eligibleInstances(context).mapTo(mutableSetOf()) { it.id }
        val cloudId = NanoMuseCloud.instance(context)?.id
        val menu = if (cloudId == null) emptySet() else NanoMuseCloud.menuModelIds(context).toSet()
        val catalogue = ProviderCatalogue.load(context)
        // ImageGen resolves model metadata by provider/model, so duplicates could
        // otherwise use a different entry from the one the person approved.
        val duplicateProviders = config.instances.groupingBy { it.id }.eachCount().filterValues { it > 1 }.keys
        val duplicateModels = config.modelEntries.groupingBy { it.providerInstanceId to it.model.id }
            .eachCount().filterValues { it > 1 }.keys
        return config.modelEntries.mapNotNull { entry ->
            val instance = config.instances.find { it.id == entry.providerInstanceId } ?: return@mapNotNull null
            val identity = AutoModelStore.identity(entry, instance)
            val vendor = ProviderCatalogue.match(catalogue, instance)
            val capabilities = AutoModelStore.capabilities(context, identity,
                vendor?.models?.get(entry.model.id)?.capabilities.orEmpty())
            val id = entry.model.id
            val supported = instance.id !in duplicateProviders && (instance.id to id) !in duplicateModels &&
                instance.id in eligible && ImageModelRequests.supportsAutomaticGeneration(instance, id) &&
                (instance.id != cloudId || id in menu)
            val candidate = AutoImageRouter.Candidate(
                entryId = entry.id, providerId = instance.id, modelId = id,
                enabled = instance.isEnabled, visible = !entry.isHidden,
                // An explicitly keyless compatible endpoint is represented by an empty string.
                credentialed = repo.usableApiKey(instance) != null,
                supportedTransport = supported,
                confirmedImageGeneration = Need.IMAGE_GENERATION in capabilities,
                imageApproved = AutoModelStore.imageApproved(context, identity),
            )
            Entry(candidate, entry, instance, identity)
        }
    }

    /** Distinguish no configured image model from a configured but unapproved one. */
    fun hasRegisteredImage(context: Context): Boolean {
        val app = context.applicationContext as? MinisApp ?: return false
        val repo = app.providerRepositoryOrNull ?: return false
        return entries(context, repo).any {
            it.candidate.confirmedImageGeneration || "image" in it.model.model.outputModalities.orEmpty() ||
                ImageGen.looksLikeImageModel(it.model.model.id)
        }
    }

    /** Does not make a network call or retain credentials. */
    fun choose(context: Context): Selection? {
        if (!AutoModelStore.enabled(context) || !ai.tama.router.AutomaticCostGuard.permits(context)) return null
        val app = context.applicationContext as? MinisApp ?: return null
        val repo = app.providerRepositoryOrNull ?: return null
        val candidates = entries(context, repo)
        val preferred = ImageGen.endpoint(context)
        val choice = AutoImageRouter.pick(candidates.map { it.candidate }, preferred?.instanceId, preferred?.model)
            as? AutoImageRouter.Choice.Selected ?: return null
        val selected = candidates.first { it.candidate.entryId == choice.candidate.entryId }
        val review = if (com.openminis.app.BuildConfig.APPLICATION_ID == "ai.tama.assistant.tamav2test") {
            AutoModelStore.review(context, selected.identity).takeIf { it.imageApproved } ?: return null
        } else null
        return Selection(selected.candidate.entryId, selected.instance.id, selected.model.model.id,
            selected.identity, review?.accountVersion ?: AutoModelRuntime.accountVersion(selected.instance.id),
            review?.reviewVersion)
    }

    /** Re-read route, consent, capability, account generation and the current key before sending. */
    fun endpointIfStillAllowed(context: Context, selection: Selection): ImageGen.Endpoint? {
        if (!AutoModelStore.enabled(context) || !ai.tama.router.AutomaticCostGuard.permits(context)) return null
        val parallel = com.openminis.app.BuildConfig.APPLICATION_ID == "ai.tama.assistant.tamav2test"
        fun reviewCurrent(): Boolean = !parallel || AutoModelStore.imageReviewCurrent(
            context, selection.identity, selection.accountVersion, selection.reviewVersion,
        )
        if (!reviewCurrent()) return null
        val app = context.applicationContext as? MinisApp ?: return null
        val repo = app.providerRepositoryOrNull ?: return null
        val candidates = entries(context, repo)
        val choice = AutoImageRouter.pick(candidates.map { it.candidate }, selection.providerId, selection.modelId)
            as? AutoImageRouter.Choice.Selected ?: return null
        if (choice.candidate.entryId != selection.entryId) return null
        val current = candidates.first { it.candidate.entryId == selection.entryId }
        if (current.identity != selection.identity || current.instance.id != selection.providerId ||
            current.model.model.id != selection.modelId ||
            AutoModelRuntime.accountVersion(selection.providerId) != selection.accountVersion) return null
        val key = repo.usableApiKey(current.instance) ?: return null
        if (!AutoModelStore.enabled(context) || !ai.tama.router.AutomaticCostGuard.permits(context) ||
            !AutoModelStore.imageApproved(context, current.identity) || !reviewCurrent() ||
            AutoModelRuntime.accountVersion(selection.providerId) != selection.accountVersion) return null
        // The repository instance is mutable. Dispatch uses this snapshot rather
        // than following later UI edits to a route that was not approved.
        return ImageGen.Endpoint(current.instance.copy(), key, selection.modelId)
    }
}
