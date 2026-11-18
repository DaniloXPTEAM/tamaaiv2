package ai.tama.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticReportTest {
    private fun snapshot() = DiagnosticReport.Snapshot(
        version = DiagnosticReport.version("1.0.9-tama.1"),
        versionCode = 530,
        variant = DiagnosticReport.variant("ai.tama.assistant.tamav2test", "tamaTest"),
        androidApi = 35,
        architecture = DiagnosticReport.architecture(listOf("arm64-v8a", "armeabi-v7a")),
        selectedProfile = DiagnosticReport.SelectedProfile.AUTO,
        lastProfile = DiagnosticReport.LastProfile.FULL,
        streaming = DiagnosticReport.TriState.NO,
        compacting = DiagnosticReport.TriState.NO,
        lastFailure = DiagnosticReport.Failure(DiagnosticReport.FailureKind.RATE_LIMITED, 429),
    )

    @Test fun `the report contains only the documented fixed fields`() {
        val report = DiagnosticReport.format(snapshot())
        assertEquals(
            setOf(
                "app_version", "app_version_code", "build", "application_id", "android_api",
                "architecture", "profile_selected", "profile_last", "response_in_progress",
                "compacting", "last_error_kind", "last_error_http",
            ),
            report.lineSequence().drop(1).map { it.substringBefore('=') }.toSet(),
        )
        assertTrue(report.startsWith("TAMA_DIAGNOSTIC v1\n"))
        assertTrue(report.contains("app_version=1.0.9-tama.1"))
        assertTrue(report.contains("application_id=ai.tama.assistant.tamav2test"))
        assertTrue(report.contains("profile_last=FULL"))
        assertTrue(report.contains("last_error_kind=RATE_LIMITED\nlast_error_http=429"))
    }

    @Test fun `arbitrary build and device strings cannot enter the report`() {
        val conversation = "private-conversation-canary"
        val credential = "fake-credential-canary"
        val address = "private-host-canary"
        val bad = snapshot().copy(
            version = DiagnosticReport.version("$conversation-$credential"),
            variant = DiagnosticReport.variant(credential, address),
            architecture = DiagnosticReport.architecture(listOf(conversation, address)),
        )
        val report = DiagnosticReport.format(bad)
        listOf(conversation, credential, address).forEach { assertFalse(report.contains(it)) }
        assertTrue(report.contains("app_version=UNAVAILABLE"))
        assertTrue(report.contains("application_id=UNAVAILABLE"))
        assertTrue(report.contains("architecture=OTHER"))
    }

    @Test fun `invalid numbers and missing state are explicit instead of guessed`() {
        val bad = snapshot().copy(
            version = DiagnosticReport.Version(-1, 0, 0, null),
            versionCode = -1,
            androidApi = 999,
            lastFailure = DiagnosticReport.Failure(DiagnosticReport.FailureKind.OTHER, 999),
        )
        val report = DiagnosticReport.format(bad)
        assertTrue(report.contains("app_version=UNAVAILABLE"))
        assertTrue(report.contains("app_version_code=UNAVAILABLE"))
        assertTrue(report.contains("android_api=UNAVAILABLE"))
        assertTrue(report.endsWith("last_error_http=UNAVAILABLE"))
        assertEquals(DiagnosticReport.TriState.UNAVAILABLE, DiagnosticReport.flag(null))
        assertNull(DiagnosticReport.version("1.0.9-suffix-with-untrusted-data"))
    }

    @Test fun `only known variants and processor names are shown`() {
        assertEquals(DiagnosticReport.Variant.NORMAL_DEBUG,
            DiagnosticReport.variant("ai.tama.assistant", "debug"))
        assertEquals(DiagnosticReport.Variant.NORMAL_RELEASE,
            DiagnosticReport.variant("ai.tama.assistant", "release"))
        assertEquals(DiagnosticReport.Variant.OTHER,
            DiagnosticReport.variant("ai.tama.assistant", "tamaTest"))
        assertEquals(DiagnosticReport.Architecture.OTHER, DiagnosticReport.architecture(emptyList()))
        assertEquals(DiagnosticReport.Architecture.X86_64,
            DiagnosticReport.architecture(listOf("unknown", "x86_64")))
    }
}
