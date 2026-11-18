package ai.tama.diagnostics

import ai.tama.router.Profile
import io.github.nanomuse.net.ProviderReach
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DiagnosticCaptureTest {
    @Test fun `settings report never reads or guesses chat state`() {
        val settings = DiagnosticCapture.settings()
        assertEquals(DiagnosticReport.SelectedProfile.UNAVAILABLE, settings.selectedProfile)
        assertEquals(DiagnosticReport.LastProfile.UNAVAILABLE, settings.lastProfile)
        assertEquals(DiagnosticReport.TriState.UNAVAILABLE, settings.streaming)
        assertEquals(DiagnosticReport.TriState.UNAVAILABLE, settings.compacting)
        assertNull(settings.lastFailure)
    }

    @Test fun `chat distinguishes automatic selection from an unavailable last profile`() {
        val auto = DiagnosticCapture.chat(null, null, false, true, null)
        assertEquals(DiagnosticReport.SelectedProfile.AUTO, auto.selectedProfile)
        assertEquals(DiagnosticReport.LastProfile.UNAVAILABLE, auto.lastProfile)
        assertEquals(DiagnosticReport.TriState.NO, auto.streaming)
        assertEquals(DiagnosticReport.TriState.YES, auto.compacting)
        for (profile in Profile.entries) {
            val state = DiagnosticCapture.chat(profile, profile, true, false, null)
            assertEquals(profile.name, state.selectedProfile.name)
            assertEquals(profile.name, state.lastProfile.name)
        }
    }

    @Test fun `every provider error is reduced to its enum and a bounded HTTP status`() {
        for (kind in ProviderReach.Kind.entries) {
            val failure = DiagnosticCapture.failure(kind, 429)
            assertEquals(kind.name, failure.kind.name)
            assertEquals(429, failure.httpStatus)
        }
        val generic = DiagnosticCapture.failure(null, 799)
        assertEquals(DiagnosticReport.FailureKind.OTHER, generic.kind)
        assertNull(generic.httpStatus)
    }
}
