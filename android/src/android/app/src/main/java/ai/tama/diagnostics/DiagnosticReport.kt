package ai.tama.diagnostics

/**
 * A deliberately closed diagnostic schema. Nothing here accepts a conversation, provider,
 * account, exception, log, path or credential. Inputs that originate as strings are parsed into
 * numbers or fixed enum values and discarded before a report can be formatted.
 */
internal object DiagnosticReport {
    enum class Variant { NORMAL_DEBUG, NORMAL_RELEASE, PARALLEL_TEST, OTHER }
    enum class Architecture { ARM64, ARM32, X86_64, X86, OTHER }
    enum class SelectedProfile { AUTO, LEAN, BASIC, FULL, UNAVAILABLE }
    enum class LastProfile { LEAN, BASIC, FULL, UNAVAILABLE }
    enum class TriState { YES, NO, UNAVAILABLE }
    enum class FailureKind {
        UNREACHABLE, REGION_BLOCKED, SIGNED_OUT, QUOTA, RATE_LIMITED, NO_CREDIT,
        AUTH_REJECTED, ACCESS_DENIED, REQUEST_TOO_LARGE, RESPONSE_LIMIT,
        SERVER_ERROR, PROVIDER_ERROR, OTHER,
    }

    data class Version(val major: Int, val minor: Int, val patch: Int, val tamaRevision: Int?)
    data class Failure(val kind: FailureKind, val httpStatus: Int?)
    data class Snapshot(
        val version: Version?,
        val versionCode: Int,
        val variant: Variant,
        val androidApi: Int,
        val architecture: Architecture,
        val selectedProfile: SelectedProfile = SelectedProfile.UNAVAILABLE,
        val lastProfile: LastProfile = LastProfile.UNAVAILABLE,
        val streaming: TriState = TriState.UNAVAILABLE,
        val compacting: TriState = TriState.UNAVAILABLE,
        val lastFailure: Failure? = null,
    )

    /** Only the project's numeric version convention is accepted, never an arbitrary string. */
    fun version(value: String): Version? {
        val match = Regex("""(\d{1,3})\.(\d{1,3})\.(\d{1,3})(?:-tama\.(\d{1,3}))?""")
            .matchEntire(value) ?: return null
        val (major, minor, patch, revision) = match.destructured
        return Version(major.toInt(), minor.toInt(), patch.toInt(), revision.toIntOrNull())
    }

    /** Unknown or altered identifiers are never echoed. */
    fun variant(applicationId: String, buildType: String): Variant = when {
        applicationId == "ai.tama.assistant" && buildType == "debug" -> Variant.NORMAL_DEBUG
        applicationId == "ai.tama.assistant" && buildType == "release" -> Variant.NORMAL_RELEASE
        applicationId == "ai.tama.assistant.tamav2test" && buildType == "tamaTest" -> Variant.PARALLEL_TEST
        else -> Variant.OTHER
    }

    /** Do not print device-provided ABI strings outside this explicit list. */
    fun architecture(abis: List<String>): Architecture = abis.firstNotNullOfOrNull {
        when (it) {
            "arm64-v8a" -> Architecture.ARM64
            "armeabi-v7a" -> Architecture.ARM32
            "x86_64" -> Architecture.X86_64
            "x86" -> Architecture.X86
            else -> null
        }
    } ?: Architecture.OTHER

    fun flag(value: Boolean?): TriState = when (value) {
        true -> TriState.YES
        false -> TriState.NO
        null -> TriState.UNAVAILABLE
    }

    /** Fixed labels and fixed enum values make clipboard output independent of user content. */
    fun format(snapshot: Snapshot): String = buildString {
        val v = snapshot.version
        val validVersion = v?.takeIf {
            it.major in 0..999 && it.minor in 0..999 && it.patch in 0..999 &&
                (it.tamaRevision == null || it.tamaRevision in 0..999)
        }
        val version = validVersion?.let {
            "${it.major}.${it.minor}.${it.patch}" + (it.tamaRevision?.let { rev -> "-tama.$rev" } ?: "")
        } ?: "UNAVAILABLE"
        val packageId = when (snapshot.variant) {
            Variant.NORMAL_DEBUG, Variant.NORMAL_RELEASE -> "ai.tama.assistant"
            Variant.PARALLEL_TEST -> "ai.tama.assistant.tamav2test"
            Variant.OTHER -> "UNAVAILABLE"
        }
        val status = snapshot.lastFailure?.httpStatus?.takeIf { it in 100..599 }?.toString()
            ?: "UNAVAILABLE"
        appendLine("TAMA_DIAGNOSTIC v1")
        appendLine("app_version=$version")
        appendLine("app_version_code=${snapshot.versionCode.takeIf { it > 0 } ?: "UNAVAILABLE"}")
        appendLine("build=${snapshot.variant.name}")
        appendLine("application_id=$packageId")
        appendLine("android_api=${snapshot.androidApi.takeIf { it in 1..100 } ?: "UNAVAILABLE"}")
        appendLine("architecture=${snapshot.architecture.name}")
        appendLine("profile_selected=${snapshot.selectedProfile.name}")
        appendLine("profile_last=${snapshot.lastProfile.name}")
        appendLine("response_in_progress=${snapshot.streaming.name}")
        appendLine("compacting=${snapshot.compacting.name}")
        appendLine("last_error_kind=${snapshot.lastFailure?.kind?.name ?: "UNAVAILABLE"}")
        append("last_error_http=$status")
    }
}
