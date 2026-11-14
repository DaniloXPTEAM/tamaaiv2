package io.github.nanomuse.isolated

import android.webkit.WebView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openminis.app.browser.BrowserAction
import com.openminis.app.browser.BrowserActionInput
import com.openminis.app.browser.BrowserUseManager
import io.github.nanomuse.guard.BrowserGuard
import io.github.nanomuse.guard.RiskClass
import io.github.nanomuse.guard.RiskDecision
import io.github.nanomuse.guard.RiskGate
import java.util.UUID
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the real WebView and approval gate on an isolated, network-blocked synthetic page. */
@RunWith(AndroidJUnit4::class)
class BrowserGuardInstrumentedTest {
    companion object {
        @BeforeClass @JvmStatic fun checkIsolation() = requireIsolatedEmulator()
    }

    @Test fun browserClickAndTypeChecksRejectSyntheticRisks() = runBlocking {
        requireIsolatedEmulator()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val session = "tama-ci-browser-${UUID.randomUUID()}"
        lateinit var view: WebView
        lateinit var manager: BrowserUseManager
        var viewCreated = false
        try {
            instrumentation.runOnMainSync {
                view = WebView(instrumentation.targetContext)
                viewCreated = true
                manager = BrowserUseManager(view, sessionIdProvider = { session })
                view.settings.blockNetworkLoads = true
                view.loadDataWithBaseURL(
                    "https://guard.example.invalid/",
                    """<!doctype html><html><body>
                        <input id="search" placeholder="Pesquisar">
                        <input id="card" autocomplete="cc-number">
                        <button id="pay" aria-label="Confirmar pagamento" onclick="window.paid=true">
                          <span id="child" aria-label="Avançar">Continuar</span>
                        </button>
                        <script>window.paid=false;</script>
                    </body></html>""".trimIndent(),
                    "text/html", "UTF-8", null,
                )
            }
            withTimeout(30_000) { manager.currentURL.first { it.startsWith("https://guard.example.invalid/") } }

            val absent = withTimeout(15_000) {
                manager.execute(BrowserActionInput(BrowserAction.CLICK, selector = "#missing"))
            }
            assertFalse(absent.success)
            assertTrue(absent.text.contains("not performed"))

            val typed = withTimeout(15_000) {
                manager.execute(BrowserActionInput(BrowserAction.TYPE, selector = "#search", text = "synthetic"))
            }
            assertTrue(typed.text, typed.success)
            assertEquals(JSONObject.quote("synthetic"), evaluate(view, "document.querySelector('#search').value"))

            val secret = withTimeout(15_000) {
                manager.execute(BrowserActionInput(BrowserAction.TYPE, selector = "#card", text = "fake"))
            }
            assertFalse(secret.success)
            assertTrue(secret.text.contains(BrowserGuard.HANDOFF_PREFIX))
            assertEquals(JSONObject.quote(""), evaluate(view, "document.querySelector('#card').value"))

            val click = async {
                withTimeout(25_000) {
                    manager.execute(BrowserActionInput(BrowserAction.CLICK, selector = "#child"))
                }
            }
            val pending = withTimeout(15_000) { RiskGate.pending.first { it?.sessionId == session } }
                ?: error("No browser approval request")
            assertEquals(RiskClass.MONEY, pending.assessment.riskClass)
            assertEquals("Confirmar pagamento", pending.elementText)
            RiskGate.decide(pending.id, RiskDecision.DENY)
            assertFalse(withTimeout(15_000) { click.await() }.success)
            assertEquals("false", evaluate(view, "window.paid"))
        } finally {
            RiskGate.pending.value?.takeIf { it.sessionId == session }?.let {
                RiskGate.decide(it.id, RiskDecision.DENY)
            }
            if (viewCreated) instrumentation.runOnMainSync { view.destroy() }
        }
    }

    private suspend fun evaluate(view: WebView, script: String): String = withTimeout(15_000) {
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { continuation ->
                view.evaluateJavascript(script) { value ->
                    if (continuation.isActive) continuation.resume(value ?: "null")
                }
            }
        }
    }
}
