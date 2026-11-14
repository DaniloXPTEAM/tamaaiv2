package io.github.nanomuse.router

import com.openminis.app.data.model.AgentToolDefinition
import io.github.nanomuse.chat.PromptProfiles
import io.github.nanomuse.chat.PromptSize

/**
 * One outbound view, built from a snapshot of the same inputs used by the real chat.
 * It never stores or logs conversation data. The caller supplies an estimate for the
 * actual history, images, protocol overhead and model; it is not a token count or bill.
 */
object TurnRequestPlanner {
    data class Attempt(
        val route: Route.Chat,
        val system: String,
        val systemSizes: List<PromptSize.Measure<PromptSize.Section>>,
        val tools: List<AgentToolDefinition>,
        val inputEstimate: Int,
        /** Size of the system and OpenAI-shaped schemas, excluding unchanged history. */
        val fixedChars: Long,
    )

    private fun make(
        input: PromptProfiles.Input,
        originals: List<AgentToolDefinition>,
        route: Route.Chat,
        prefix: String,
        estimate: (String, List<AgentToolDefinition>) -> Int,
    ): Attempt {
        val built = PromptProfiles.build(route.profile, input, route.hints)
        val actualPrefix = prefix.takeIf { it.isNotEmpty() && !built.prompt.startsWith(it) }
        val system = if (actualPrefix != null) "$actualPrefix\n\n${built.prompt}" else built.prompt
        val tools = PromptProfiles.tools(route.profile, originals, route.hints)
        val schemaChars = PromptSize.tools(emptyList(), tools).last().afterChars
        val rows = if (actualPrefix == null) built.sizes else {
            // The authentication prefix is application-owned. Count it without retaining its text.
            val extra = actualPrefix.length + 2
            built.sizes.map { row ->
                if (row.label == PromptSize.Section.TOTAL) row.copy(
                    beforeChars = row.beforeChars + extra, afterChars = row.afterChars + extra,
                ) else row
            } + PromptSize.Measure(PromptSize.Section.PROVIDER_PREFIX, extra, extra)
        }
        return Attempt(route, system, rows, tools, estimate(system, tools).coerceAtLeast(0),
            system.length.toLong() + schemaChars.toLong())
    }

    /** Re-estimate every profile from the current history and the current provider's tools. */
    fun prepare(
        input: PromptProfiles.Input,
        originals: List<AgentToolDefinition>,
        route: Route.Chat,
        budget: ProviderBudget,
        providerPrefix: String = "",
        estimate: (String, List<AgentToolDefinition>) -> Int,
    ): Attempt? {
        val views = Profile.entries.associateWith { profile ->
            make(input, originals, route.copy(profile = profile), providerPrefix, estimate)
        }
        val fitted = TurnRouter.fit(route, budget, views.mapValues { it.value.inputEstimate }) ?: return null
        return views.getValue(fitted.profile).copy(route = fitted)
    }

    /**
     * Retry an HTTP 413 only with an actually smaller fixed request. If a custom prompt makes
     * BASIC larger than FULL, skip it rather than repeat a larger or unchanged request.
     * Never executes or removes tools from the already completed history.
     */
    fun smallerAfter413(
        rejected: Attempt,
        input: PromptProfiles.Input,
        originals: List<AgentToolDefinition>,
        providerPrefix: String = "",
        estimate: (String, List<AgentToolDefinition>) -> Int,
    ): Attempt? {
        var profile = TurnRouter.lower(rejected.route.profile)
        while (profile != null) {
            val route = rejected.route.copy(profile = profile)
            val next = make(input, originals, route, providerPrefix, estimate)
            // A subset plus a shorter prompt cannot grow the tool declarations on another wire format.
            if (next.system.length < rejected.system.length && next.fixedChars < rejected.fixedChars &&
                next.tools.all { candidate -> rejected.tools.any { it.name == candidate.name } }) return next
            profile = TurnRouter.lower(profile)
        }
        return null
    }

    /** Reserve some room for output within each of the three planned calls when TPM is known. */
    fun outputLimit(preferred: Int, budget: ProviderBudget, inputEstimate: Int): Int? {
        if (preferred <= 0 || inputEstimate < 0) return null
        var max = budget.maxOutputTokens?.takeIf { it > 0 }?.let { minOf(preferred, it) } ?: preferred
        budget.tokensPerMinute?.let { tpm ->
            if (tpm <= 0) return null
            val remaining = tpm / 3 - inputEstimate
            if (remaining <= 0) return null
            max = minOf(max, remaining)
        }
        return max.takeIf { it > 0 }
    }
}
