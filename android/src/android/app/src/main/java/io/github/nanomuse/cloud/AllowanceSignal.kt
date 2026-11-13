package io.github.nanomuse.cloud

import android.content.Context
import org.json.JSONObject

/**
 * The relay's refusals, caught on their way through the model client. nanoMuse Cloud refuses
 * a model call with a stable `error.code` (docs/cloud.md): `allowance_exhausted` with what is
 * left, the pool, the invite link, what an invitation adds to each side, the guide for one's
 * own key and — relay 0.21 — the ways-on card as data (`guidance`); `daily_cap`; and the rest
 * ([RelayRefusal]: 413 `too_large`, `too_many_in_flight`, `provider_busy`, the operator's
 * switches …). The generic client maps a 429 to "rate limited", so the body would be lost;
 * the parsed diagnostic travels on the exception for the particular call. A concurrent call
 * or a later fallback must not put another request's allowance card on this turn.
 */
object AllowanceSignal {
    /** What the relay said, in yuan. */
    data class Exhausted(
        val leftCny: Double,
        val grantCny: Double,
        val inviteUrl: String,
        /** What a sign-up with the link adds to the inviter and to the new account; 0 = unknown. */
        val inviteBonusCny: Double,
        val inviteeBonusCny: Double,
        val ownKeyDocs: String,
        /** True for the relay's `daily_cap`: today's share is spent, the pool is not; it comes back with the day. */
        val dailyCap: Boolean = false,
        /** Relay 0.22: the free allowance is paused by the operator — not used up; the card leads with that. */
        val paused: Boolean = false,
        /** Relay 0.21: the ways on as the relay lists them (contract C11); null from an older relay. */
        val guidance: Guidance? = null,
        val at: Long = System.currentTimeMillis(),
    )

    /** The relay's refusal and, for an allowance refusal, the data needed by its card. */
    data class HttpError(val refusal: RelayRefusal.Refusal, val exhausted: Exhausted? = null)

    /**
     * Parse a failed model response, without keeping any process-wide last-error state.
     * [fromRelay] says the call went to the relay's host. The caller attaches the result to
     * the corresponding exception and carries it across any final fallback wrapper.
     */
    fun fromHttpError(status: Int, body: String?, fromRelay: Boolean = false): HttpError? {
        val refusal = RelayRefusal.parse(status, body, fromRelay) ?: return null
        if (!refusal.isAllowance) return HttpError(refusal)
        val err = RelayRefusal.errorObject(body) ?: JSONObject()
        return HttpError(refusal, Exhausted(
            dailyCap = refusal.kind == RelayRefusal.Kind.DAILY_CAP,
            paused = refusal.kind == RelayRefusal.Kind.ALLOWANCE_PAUSED,
            leftCny = err.optDouble("left", 0.0),
            grantCny = err.optDouble("grant", 0.0),
            inviteUrl = err.optString("invite_url", ""),
            inviteBonusCny = err.optDouble("invite_bonus_cny", 0.0),
            inviteeBonusCny = err.optDouble("invitee_bonus_cny", 0.0),
            ownKeyDocs = err.optString("own_key_docs", ""),
            guidance = Guidance.parse(err.optJSONObject("guidance")),
        ))
    }

    /** The same, from the cached account (the account page shows it whenever the pool is spent). */
    fun fromAccount(context: Context): Exhausted? {
        val a = NanoMuseCloud.account(context) ?: return null
        if (!a.exhausted) return null
        return Exhausted(
            leftCny = a.leftCny.coerceAtLeast(0.0),
            grantCny = a.grantCny,
            inviteUrl = a.inviteUrl,
            inviteBonusCny = a.inviteBonusCny,
            inviteeBonusCny = a.inviteeBonusCny,
            ownKeyDocs = a.ownKeyDocs,
            guidance = NanoMuseCloud.guidance(context),
        )
    }
}
