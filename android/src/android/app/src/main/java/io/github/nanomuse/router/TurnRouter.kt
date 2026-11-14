package io.github.nanomuse.router

import java.text.Normalizer
import java.util.Locale

/** Capabilities describe what a turn needs; they are not evidence that a model has them. */
enum class Need { CHAT, TOOLS, VISION, IMAGE_GENERATION }

data class SessionState(
    val previousProfile: Profile? = null,
    val previousHints: Set<ToolGroup> = emptySet(),
    val previousTurnUsedTools: Boolean = false,
)

data class ProviderBudget(val tokensPerMinute: Int? = null, val maxOutputTokens: Int? = null)

sealed interface Route {
    data class Chat(
        val profile: Profile,
        val hints: Set<ToolGroup>,
        val needs: Set<Need>,
        /** The original intent is retained even if a provider budget lowers the profile. */
        val requestedProfile: Profile = profile,
        val budgetLimited: Boolean = false,
    ) : Route
}

/** Deterministic, local prefilter: it never performs an action or asks a model to classify. */
object TurnRouter {
    private fun normalize(text: String): String = Normalizer.normalize(text.lowercase(Locale.ROOT), Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim().replace(Regex("\\s+"), " ")

    private fun containsAny(text: String, words: List<String>): Boolean {
        val padded = " $text "
        return words.any { " $it " in padded }
    }

    private val groups = linkedMapOf(
        ToolGroup.WEB to listOf("pesquisa", "pesquise", "pesquisar", "busca", "busque", "buscar", "site", "sites", "noticia", "noticias", "navegador", "web", "website", "browse", "search", "look up", "news", "internet"),
        ToolGroup.WEB_ADVANCED to listOf("baixar", "download", "cookie", "cookies", "rolar pagina", "scroll", "login", "log in", "tela cheia", "full screen"),
        ToolGroup.FILES to listOf("arquivo", "arquivos", "pasta", "pastas", "salva", "salvar", "edita", "editar", "codigo", "script", "instala", "instalar", "file", "files", "folder", "directory", "save", "edit", "code", "install"),
        ToolGroup.MEMORY to listOf("lembra", "lembrar", "memoria", "anota", "anotar", "remember", "memory", "note down"),
        ToolGroup.ANDROID to listOf("wifi", "bluetooth", "bateria", "volume", "brilho", "camera", "localizacao", "alarme", "clima", "tempo em", "abrir app", "abre o app", "abre o", "abre a", "abrir o", "abra o", "battery", "brightness", "location", "alarm", "weather", "open app", "open the app"),
        ToolGroup.SCREEN to listOf("clica", "clicar", "toca na tela", "toque na tela", "opera o app", "preenche", "click", "tap the screen", "fill in", "operate the app"),
        ToolGroup.IMAGE to listOf("imagem", "imagens", "desenha", "desenhar", "foto de", "figura", "image", "images", "draw", "picture", "photo of"),
        ToolGroup.CONFIG to listOf("configura", "configurar", "configuracao", "provedor", "modelo", "chave", "provider", "model", "api key", "configure", "settings"),
        ToolGroup.SCHEDULE to listOf("agenda", "agendar", "todo dia", "toda semana", "daqui a", "schedule", "every day", "every week", "remind me"),
        ToolGroup.SHELL to listOf("terminal", "comando", "shell", "command", "run script", "executar script"),
        ToolGroup.MEDIA to listOf("video", "videos", "audio", "clip", "video clip"),
    )
    private val risky = listOf(
        "apaga", "apagar", "apague", "exclui", "excluir", "deleta", "delete", "remove file",
        "envia", "enviar", "envie", "manda", "mandar", "send message", "post", "publicar", "paga", "pagar", "pay",
        "compra", "comprar", "buy", "transferir", "transfer money", "pagamento", "payment",
    )
    private val complex = listOf("passo a passo no app", "varias etapas", "multiplas etapas", "multi step", "multiple steps")

    fun route(
        text: String,
        session: SessionState = SessionState(),
        override: Profile? = null,
        hasImageAttachment: Boolean = false,
        hasFileAttachment: Boolean = false,
    ): Route.Chat {
        val normalized = normalize(text)
        val hints = groups.filterValues { containsAny(normalized, it) }.keys.toMutableSet()
        if (ToolGroup.FILES in hints || hasFileAttachment) {
            hints += ToolGroup.FILES
            hints += ToolGroup.SHELL
        }
        if (ToolGroup.WEB_ADVANCED in hints) hints += ToolGroup.WEB
        val riskyOrComplex = containsAny(normalized, risky) || containsAny(normalized, complex)
        val inferred = when {
            riskyOrComplex || ToolGroup.SCREEN in hints || ToolGroup.WEB_ADVANCED in hints -> Profile.FULL
            hints.isNotEmpty() -> Profile.BASIC
            else -> Profile.LEAN
        }
        val keepPrevious = session.previousTurnUsedTools && session.previousProfile != null
        if (keepPrevious) hints += session.previousHints
        val profile = override ?: if (keepPrevious) maxOf(inferred, session.previousProfile!!) else inferred
        val needs = buildSet {
            add(Need.CHAT)
            // An explicit light-mode override changes what is sent, not what the
            // person asked for. Keep the original tool requirement visible to
            // routing and warnings rather than pretending the action is possible.
            if (inferred != Profile.LEAN || profile != Profile.LEAN || (keepPrevious && session.previousProfile != Profile.LEAN)) add(Need.TOOLS)
            if (hasImageAttachment) add(Need.VISION)
            if (ToolGroup.IMAGE in hints && containsAny(normalized, listOf("gera", "gerar", "gere", "cria", "criar", "crie", "desenha", "draw", "generate", "create"))) {
                add(Need.IMAGE_GENERATION)
            }
        }
        return Route.Chat(profile = profile, hints = hints, needs = needs)
    }

    /**
     * Plan for up to three calls. A null limit is unknown, not a claim of unlimited quota.
     * A known budget that cannot fit even LEAN returns null; it never silently drops history.
     */
    fun fit(route: Route.Chat, budget: ProviderBudget, estimatedTokens: Map<Profile, Int>): Route.Chat? {
        val limit = budget.tokensPerMinute ?: return route
        if (limit <= 0) return null
        // Do not assume profile sizes are monotonic: user-provided fragments or
        // hints can make BASIC larger than FULL. Only choose an actually measured
        // profile at or below the one requested.
        val profile = Profile.entries.filter { candidate ->
            if (candidate > route.profile) return@filter false
            val estimate = estimatedTokens[candidate] ?: return@filter false
            estimate >= 0 && estimate.toLong() * 3 <= limit.toLong()
        }.maxOrNull() ?: return null
        return route.copy(profile = profile, requestedProfile = route.requestedProfile,
            budgetLimited = route.budgetLimited || profile != route.profile)
    }

    fun lower(profile: Profile): Profile? = when (profile) {
        Profile.FULL -> Profile.BASIC
        Profile.BASIC -> Profile.LEAN
        Profile.LEAN -> null
    }
}
