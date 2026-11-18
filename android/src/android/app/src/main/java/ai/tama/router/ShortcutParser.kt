package ai.tama.router

import java.text.Normalizer
import java.util.Locale

/** A narrowly scoped local command; uncertain or compound requests go to normal chat. */
sealed interface Shortcut {
    data class Image(val prompt: String) : Shortcut
}

object ShortcutParser {
    private val image = Regex(
        "^(?:(?:por favor|pfv|please)\\s+)?(?:gera|gere|cria|crie|desenha|desenhe|generate|create|draw)\\s+" +
            "(?:(?:um|uma|a|an|the)\\s+)?(?:imagem|figura|image|picture)\\s+" +
            "(?:(?:de|do|da|of)\\s+)?(.+?)\\s*$",
        RegexOption.IGNORE_CASE,
    )
    private val imageWithPortugueseMake = Regex(
        image.pattern.replace("gera|gere|", "faz|faça|faca|gera|gere|"), RegexOption.IGNORE_CASE,
    )
    private val negation = Regex("\\b(nao|nunca|never|not|don'?t|do not)\\b")
    private val conditional = Regex("\\b(se|caso|talvez|quando|if|unless|maybe|when)\\b")
    private val hazardous = Regex("\\b(apaga|apagar|apague|exclui|excluir|delete|deleta|envia|enviar|envie|manda|mandar|send|paga|pagar|pay|compra|comprar|buy|transferir)\\b")
    // A second imperative is a compound request, not part of the picture.
    // Nouns joined by 'e/and' remain valid descriptions (a cat and a dog).
    private val secondAction = Regex(
        "(?:\\b(?:e depois|and then)\\b|(?:[,.:!]|\\b(?:e|and)\\b)\\s*" +
            "(?:(?:depois|then|tambem|also)\\s+)?(?:(?:me|para mim)\\s+)?" +
            "(?:abre|abra|abrir|gera|gere|cria|crie|desenha|desenhe|pesquisa|pesquise|" +
            "busca|busque|procura|procure|lembra|lembre|alarme|escreve|escreva|" +
            "diga|fala|fale|mostra|mostre|explica|explique|resuma|faz|faca|" +
            "open|generate|create|draw|search|remind|write|tell|show|explain|set|call)\\b)",
    )
    private val trailingCourtesy = Regex("(?:,\\s*|\\s+)(?:por favor|pfv|please)[.!]?$", RegexOption.IGNORE_CASE)

    internal fun normalized(text: String): String = Normalizer.normalize(text.lowercase(Locale.ROOT), Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "").replace('’', '\'')

    /** Only the whole, unambiguous request becomes a zero-LLM image shortcut. */
    fun parse(text: String, portugueseMake: Boolean = false): Shortcut? {
        val clean = text.trim()
        if (clean.length !in 1..4_000 || clean.any { it in "\n\r;?&|`\"“”" }) return null
        val normalized = normalized(clean)
        if (negation.containsMatchIn(normalized) || conditional.containsMatchIn(normalized) ||
            hazardous.containsMatchIn(normalized) || secondAction.containsMatchIn(normalized)) return null
        val match = (if (portugueseMake) imageWithPortugueseMake else image).matchEntire(clean) ?: return null
        val description = match.groupValues[1].replace(trailingCourtesy, "").trim().trimEnd('.', '!').trim()
        if (description.length < 2 || normalized(description) in setOf("de", "do", "da", "of")) return null
        return Shortcut.Image(description)
    }
}

/** Separate from parsing: a model may only call the image tool on a person's explicit turn. */
object ImageRequestIntent {
    private val negativeOrConditional = Regex("\\b(nao|nunca|never|not|don'?t|do not|se|caso|if|unless|maybe|talvez)\\b")
    // A direct imperative or a separate imperative in a compound instruction,
    // not an incidental mention such as "explain how you generate an image".
    private val clause = Regex(
        "(?:^|\\b(?:e|and|then)\\s+|[,;]\\s*)(?:(?:por favor|please)\\s+)?" +
            "(?:gera|gere|cria|crie|desenha|desenhe|generate|create|draw)\\s+" +
            "(?:(?:um|uma|a|an|the)\\s+)?(?:imagem|figura|image|picture)\\b",
    )

    private val clauseWithPortugueseMake = Regex(clause.pattern.replace("gera|gere|", "faz|faca|gera|gere|"))

    fun explicit(text: String, portugueseMake: Boolean = false): Boolean {
        val normalized = ShortcutParser.normalized(text.trim())
        if (text.length !in 1..4_000 || normalized.any { it in "?\"“”\n\r`" } ||
            negativeOrConditional.containsMatchIn(normalized) || text.contains("[Pasted#")) return false
        val match = (if (portugueseMake) clauseWithPortugueseMake else clause).find(normalized) ?: return false
        val description = normalized.substring(match.range.last + 1).trim()
            .replaceFirst(Regex("^(de|do|da|of)\\b\\s*"), "").trim().trimEnd('.', '!').trim()
        return description.length >= 2
    }
}
