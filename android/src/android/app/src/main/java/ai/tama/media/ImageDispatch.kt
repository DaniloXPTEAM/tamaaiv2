package ai.tama.media

import io.github.nanomuse.media.*

/**
 * The common gate immediately before an agent image call. The selection and transport are
 * injected so the policy can be exercised without an Android device or a paid service.
 * Revalidation reduces, but cannot make atomic, concurrent settings changes and network I/O.
 */
internal object ImageDispatch {
    /** A route or approval changed while a transport was preparing its request. */
    class AuthorizationChanged : Exception("Image authorization changed")

    enum class Block { AUTO_EDIT_UNAVAILABLE, NO_APPROVED_IMAGE, AUTHORIZATION_CHANGED, NO_MANUAL_IMAGE, PERMISSION_DENIED }

    fun validSize(value: String): Boolean {
        val match = Regex("(\\d{3,4})x(\\d{3,4})").matchEntire(value) ?: return false
        return match.groupValues.drop(1).all { it.toInt() in 128..4096 }
    }

    sealed interface Outcome<out E, out T> {
        data class Sent<E, T>(val endpoint: E, val picture: T) : Outcome<E, T>
        data class Blocked(val reason: Block) : Outcome<Nothing, Nothing>
    }

    suspend fun <S : Any, E : Any, T : Any> run(
        automatic: Boolean,
        hasReference: Boolean,
        automaticNow: () -> Boolean,
        select: () -> S?,
        revalidate: (S) -> E?,
        manualEndpoint: () -> E?,
        // The caller must supply the real paid-action gate; there is no permissive default.
        authorize: suspend (E) -> Boolean,
        send: suspend (E) -> T,
    ): Outcome<E, T> {
        if (automatic && hasReference) return Outcome.Blocked(Block.AUTO_EDIT_UNAVAILABLE)
        if (automatic != automaticNow()) return Outcome.Blocked(Block.AUTHORIZATION_CHANGED)
        val selected = if (automatic) select() ?: return Outcome.Blocked(Block.NO_APPROVED_IMAGE) else null
        val initial = if (selected != null) revalidate(selected) ?: return Outcome.Blocked(Block.AUTHORIZATION_CHANGED)
            else manualEndpoint() ?: return Outcome.Blocked(Block.NO_MANUAL_IMAGE)
        if (automatic != automaticNow()) return Outcome.Blocked(Block.AUTHORIZATION_CHANGED)
        // A person may take time to answer. Do not retain an old authorization or
        // a changed manual route across the dialog; re-read both immediately after.
        if (!authorize(initial)) return Outcome.Blocked(Block.PERMISSION_DENIED)
        val endpoint = if (selected != null) revalidate(selected)?.takeIf { it == initial }
            ?: return Outcome.Blocked(Block.AUTHORIZATION_CHANGED)
            else manualEndpoint()?.takeIf { it == initial } ?: return Outcome.Blocked(Block.AUTHORIZATION_CHANGED)
        if (automatic != automaticNow()) return Outcome.Blocked(Block.AUTHORIZATION_CHANGED)
        // No alternative model or second invocation after send throws.
        return Outcome.Sent(endpoint, send(endpoint))
    }
}
