package io.github.nanomuse.guard

import com.openminis.app.logging.AppLogger
import java.util.concurrent.CancellationException
import org.json.JSONObject

/**
 * Looks at what a `browser_use` click or type is about to touch, before it happens.
 *
 * Recognized secret fields are refused; recognized pay, send or delete labels are checked
 * against approvals bound to the page's host. Detection is heuristic, earlier grants may
 * apply, and arbitrary JavaScript is not covered by these click/type checks.
 */
object BrowserGuard {
    private const val TAG = "BrowserGuard"

    /** Prefix on a refusal so the chat layer knows to bring the browser sheet forward. */
    const val HANDOFF_PREFIX = "[TAMA AI handoff]"

    sealed class Verdict {
        object Proceed : Verdict()
        /** Refused outright (secret field or uninspectable target). [text] is what the model reads. */
        data class Refuse(val text: String) : Verdict()
        /** Needs the user's yes first. */
        data class Ask(val assessment: RiskAssessment, val elementText: String) : Verdict()
    }

    /** Everything we look at, gathered in one `evaluateJavascript`. */
    data class Target(
        val found: Boolean,
        val tag: String,
        val type: String,
        val name: String,
        val id: String,
        val placeholder: String,
        val autocomplete: String,
        val inputMode: String,
        val aria: String,
        val label: String,
        val text: String,
        val href: String,
        val maxLength: Int,
        val url: String,
        val host: String,
        // TAMA: a clicked child can have a different label from its parent button.
        val buttonAria: String = "",
    ) {
        companion object {
            fun parse(json: String): Target? = runCatching {
                val o = JSONObject(json)
                Target(
                    found = o.optBoolean("found", false),
                    tag = o.optString("tag"), type = o.optString("type"), name = o.optString("name"),
                    id = o.optString("id"), placeholder = o.optString("placeholder"),
                    autocomplete = o.optString("autocomplete"), inputMode = o.optString("inputmode"),
                    aria = o.optString("aria"), label = o.optString("label"), text = o.optString("text"),
                    href = o.optString("href"), maxLength = o.optString("maxlength").toIntOrNull() ?: 0,
                    url = o.optString("url"), host = o.optString("host"), buttonAria = o.optString("buttonAria"),
                )
            }.getOrNull()
        }
    }

    // TAMA: a click or type must not proceed if WebView cannot describe its target.
    private const val UNINSPECTED_TARGET =
        "Safety check could not inspect the browser target. The action was not performed. " +
            "Check the page and selector, or ask the person. Do not bypass the check with another action."

    data class Inspection(val target: Target?, val verdict: Verdict)

    suspend fun inspect(typing: Boolean, describe: suspend () -> String): Inspection {
        val raw = try {
            describe()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return Inspection(null, Verdict.Refuse(UNINSPECTED_TARGET))
        }
        val target = Target.parse(raw)
        return Inspection(target, if (typing) judgeType(target) else judgeClick(target))
    }

    // TAMA: use the same normalized secret-field checks as the phone screen.
    private val secretAutocompletes = setOf(
        "one-time-code", "current-password", "new-password", "cc-csc", "cc-number",
        "cc-exp", "cc-exp-month", "cc-exp-year",
    )
    private val numericCodeLabel = Regex("code|c[oó]digo|码", RegexOption.IGNORE_CASE)

    /** The JS that describes the element a click/type will land on. Returns a JSON object. */
    fun describeJs(selector: String?, x: Int?, y: Int?): String {
        val pick = when {
            selector != null -> "document.querySelector(${JSONObject.quote(selector)})"
            x != null && y != null -> "document.elementFromPoint($x, $y)"
            else -> "null"
        }
        return """
            (function(){
              var el = null; try { el = $pick; } catch (e) {}
              if (!el) return JSON.stringify({found:false, url: location.href, host: location.host});
              function s(v){ return ((v == null ? '' : v) + '').replace(/\s+/g,' ').trim().slice(0, 160); }
              var inp = el.closest ? (el.closest('input,textarea,select,[contenteditable=true],[contenteditable=""]') || el) : el;
              var btn = el.closest ? (el.closest('button,a,[role=button],[role=link],input[type=submit],input[type=button],input[type=image],[onclick],summary,label') || el) : el;
              var lbl = '';
              try { if (inp.labels && inp.labels[0]) lbl = inp.labels[0].innerText; else if (inp.id) { var l = document.querySelector('label[for="' + inp.id + '"]'); if (l) lbl = l.innerText; } } catch (e) {}
              var txt = btn.innerText || btn.value || btn.getAttribute('aria-label') || btn.getAttribute('title') || btn.getAttribute('alt') || '';
              if (!s(txt) && btn.querySelector) { var im = btn.querySelector('img[alt]'); if (im) txt = im.getAttribute('alt'); }
              return JSON.stringify({
                found: true, tag: el.tagName, type: s(inp.getAttribute && inp.getAttribute('type')).toLowerCase(),
                name: s(inp.getAttribute && inp.getAttribute('name')), id: s(inp.id), placeholder: s(inp.getAttribute && inp.getAttribute('placeholder')),
                autocomplete: s(inp.getAttribute && inp.getAttribute('autocomplete')).toLowerCase(), inputmode: s(inp.getAttribute && inp.getAttribute('inputmode')).toLowerCase(),
                aria: s(inp.getAttribute && inp.getAttribute('aria-label')), label: s(lbl), text: s(txt),
                buttonAria: s(btn.getAttribute && btn.getAttribute('aria-label')),
                href: s(btn.getAttribute && btn.getAttribute('href')), maxlength: s(inp.getAttribute && inp.getAttribute('maxlength')),
                url: location.href, host: location.host
              });
            })()
        """.trimIndent()
    }

    fun judgeType(t: Target?): Verdict {
        if (t == null || !t.found) return Verdict.Refuse(UNINSPECTED_TARGET)
        val looksSecret = t.type.equals("password", ignoreCase = true) ||
            t.autocomplete.split(Regex("\\s+")).any { it.lowercase() in secretAutocompletes } ||
            listOf(t.name, t.id, t.placeholder, t.aria, t.label).any(TapWords::looksSecret) ||
            (t.inputMode.equals("numeric", ignoreCase = true) && t.maxLength in 4..8 &&
                listOf(t.placeholder, t.aria, t.label).any { numericCodeLabel.containsMatchIn(it) })
        if (!looksSecret) return Verdict.Proceed
        AppLogger.info(TAG, "refused typing into a secret field on ${t.host}")
        return Verdict.Refuse(
            "$HANDOFF_PREFIX Refused: that field looks like a password, verification code or card detail. " +
                "Ask the user to enter it in the in-app browser and tell you when it is done, then continue from the page " +
                "as it is. Do not try another selector or JavaScript for this field.",
        )
    }

    fun judgeClick(t: Target?): Verdict {
        if (t == null || !t.found) return Verdict.Refuse(UNINSPECTED_TARGET)
        // TAMA: check visible text, the selected node and its parent button; prefer the
        // strongest recognized risk so an ordinary label cannot hide a payment or deletion.
        val matches = listOf(t.text, t.aria, t.buttonAria).mapNotNull { label ->
            TapWords.classify(label)?.let { label to it }
        }
        val (label, cls) = matches.minByOrNull { (_, risk) ->
            when (risk) {
                RiskClass.MONEY -> 0
                RiskClass.DESTRUCTIVE -> 1
                RiskClass.OUTBOUND -> 2
                else -> 3
            }
        } ?: return Verdict.Proceed
        val host = t.host.ifBlank { null }
        val short = label.take(40)
        return Verdict.Ask(RiskAssessment(cls, TapWords.reason(cls, short, host), host), short)
    }
}
