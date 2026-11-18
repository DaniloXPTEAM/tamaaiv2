package ai.tama.diagnostics

import android.os.Build
import ai.tama.router.Profile
import com.openminis.app.BuildConfig
import io.github.nanomuse.net.ProviderReach

/** Only build constants, OS API/ABI and already-classified chat state enter the report. */
internal object DiagnosticCapture {
    fun settings(): DiagnosticReport.Snapshot = snapshot()

    fun chat(
        selected: Profile?,
        last: Profile?,
        streaming: Boolean,
        compacting: Boolean,
        failure: DiagnosticReport.Failure?,
    ): DiagnosticReport.Snapshot = snapshot().copy(
        selectedProfile = when (selected) {
            null -> DiagnosticReport.SelectedProfile.AUTO
            Profile.LEAN -> DiagnosticReport.SelectedProfile.LEAN
            Profile.BASIC -> DiagnosticReport.SelectedProfile.BASIC
            Profile.FULL -> DiagnosticReport.SelectedProfile.FULL
        },
        lastProfile = when (last) {
            null -> DiagnosticReport.LastProfile.UNAVAILABLE
            Profile.LEAN -> DiagnosticReport.LastProfile.LEAN
            Profile.BASIC -> DiagnosticReport.LastProfile.BASIC
            Profile.FULL -> DiagnosticReport.LastProfile.FULL
        },
        streaming = DiagnosticReport.flag(streaming),
        compacting = DiagnosticReport.flag(compacting),
        lastFailure = failure,
    )

    /** Do not accept the error body, model, provider, host or exception. */
    fun failure(kind: ProviderReach.Kind?, status: Int?): DiagnosticReport.Failure = DiagnosticReport.Failure(
        kind = when (kind) {
            ProviderReach.Kind.UNREACHABLE -> DiagnosticReport.FailureKind.UNREACHABLE
            ProviderReach.Kind.REGION_BLOCKED -> DiagnosticReport.FailureKind.REGION_BLOCKED
            ProviderReach.Kind.SIGNED_OUT -> DiagnosticReport.FailureKind.SIGNED_OUT
            ProviderReach.Kind.QUOTA -> DiagnosticReport.FailureKind.QUOTA
            ProviderReach.Kind.RATE_LIMITED -> DiagnosticReport.FailureKind.RATE_LIMITED
            ProviderReach.Kind.NO_CREDIT -> DiagnosticReport.FailureKind.NO_CREDIT
            ProviderReach.Kind.AUTH_REJECTED -> DiagnosticReport.FailureKind.AUTH_REJECTED
            ProviderReach.Kind.ACCESS_DENIED -> DiagnosticReport.FailureKind.ACCESS_DENIED
            ProviderReach.Kind.REQUEST_TOO_LARGE -> DiagnosticReport.FailureKind.REQUEST_TOO_LARGE
            ProviderReach.Kind.RESPONSE_LIMIT -> DiagnosticReport.FailureKind.RESPONSE_LIMIT
            ProviderReach.Kind.SERVER_ERROR -> DiagnosticReport.FailureKind.SERVER_ERROR
            ProviderReach.Kind.PROVIDER_ERROR -> DiagnosticReport.FailureKind.PROVIDER_ERROR
            null -> DiagnosticReport.FailureKind.OTHER
        },
        httpStatus = status?.takeIf { it in 100..599 },
    )

    private fun snapshot(): DiagnosticReport.Snapshot = DiagnosticReport.Snapshot(
        version = DiagnosticReport.version(BuildConfig.VERSION_NAME),
        versionCode = BuildConfig.VERSION_CODE,
        variant = DiagnosticReport.variant(BuildConfig.APPLICATION_ID, BuildConfig.BUILD_TYPE),
        androidApi = Build.VERSION.SDK_INT,
        // Android's JVM test stubs may return null; an unknown ABI must never crash a report.
        architecture = DiagnosticReport.architecture(Build.SUPPORTED_ABIS?.toList().orEmpty()),
    )
}
