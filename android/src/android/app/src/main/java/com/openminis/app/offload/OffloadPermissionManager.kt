package com.openminis.app.offload

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Manages offload permissions for privacy-sensitive agent tools.
 * Three levels: BYPASS (auto-allow), ASK_ONCE (per-session), NOT_ALLOWED.
 *
 * Mirrors iOS OffloadPermissionManager behavior.
 */
object OffloadPermissionManager {

    enum class PermissionLevel {
        BYPASS,      // Always allowed
        ASK_ONCE,    // Ask once per session
        NOT_ALLOWED, // Always denied
    }

    data class PermissionRequest(
        /** Unique for this process; a stale dialog must not answer the next request. */
        val requestId: Long,
        val toolName: String,
        val toolTitle: String,
        val description: String,
        val sessionId: String,
        /** A legacy shell image call has no trustworthy person-turn provenance. */
        val oneTimeOnly: Boolean = false,
    )

    enum class PermissionCategory(val displayName: String) {
        PRIVACY("Privacy"),
        MEDIA("Media"),
        SYSTEM("System"),
        // T330: privileged automation CLIs (Shizuku binder / Accessibility
        // service). These run via the offload bridge as shell tools, not as
        // named LLM tool calls, so the gate happens inside the
        // NativeOffloadHandler entry point rather than ChatViewModel.
        INTEGRATIONS("Integrations"),
    }

    data class ToolPermissionInfo(
        val toolName: String,
        val displayName: String,
        val category: PermissionCategory,
        val defaultLevel: PermissionLevel,
        /**
         * Mirrors iOS `OffloadCommandInfo.showInSettings`. When false the tool
         * is registered (so the dialog/check pipeline still recognises its
         * name) but hidden from the Permissions settings page — Media + System
         * tools carry no personal data and have no reason to clutter the UI.
         */
        val showInSettings: Boolean = true,
    )

    /**
     * Registry of tools and their permission categories. Most defaults mirror
     * iOS BYPASS; paid image generation asks first, and privileged integrations
     * default to NOT_ALLOWED. Unknown tool names still fall through to BYPASS,
     * so new sensitive actions must be explicitly registered and gated.
     */
    val toolRegistry: List<ToolPermissionInfo> = listOf(
        // Privacy — user-configurable, visible in Settings.
        ToolPermissionInfo("calendar", "Calendar", PermissionCategory.PRIVACY, PermissionLevel.BYPASS),
        ToolPermissionInfo("location", "Location", PermissionCategory.PRIVACY, PermissionLevel.BYPASS),
        ToolPermissionInfo("clipboard", "Clipboard", PermissionCategory.PRIVACY, PermissionLevel.BYPASS),
        ToolPermissionInfo("contacts", "Contacts", PermissionCategory.PRIVACY, PermissionLevel.BYPASS),
        ToolPermissionInfo("photos", "Photos", PermissionCategory.PRIVACY, PermissionLevel.BYPASS),
        // Images can incur a per-picture charge and therefore need a visible,
        // explicit permission instead of unknown-tool BYPASS.
        ToolPermissionInfo("image_generation", "Image generation", PermissionCategory.MEDIA, PermissionLevel.ASK_ONCE),
        // Other media tools carry no personal data and stay hidden from Settings.
        ToolPermissionInfo("speak", "Text-to-Speech", PermissionCategory.MEDIA, PermissionLevel.BYPASS, showInSettings = false),
        ToolPermissionInfo("media_player", "Media Player", PermissionCategory.MEDIA, PermissionLevel.BYPASS, showInSettings = false),
        ToolPermissionInfo("speech_recognition", "Speech Recognition", PermissionCategory.MEDIA, PermissionLevel.BYPASS, showInSettings = false),
        // System — no personal data, hidden from Settings.
        ToolPermissionInfo("alarm", "Alarms & Timers", PermissionCategory.SYSTEM, PermissionLevel.BYPASS, showInSettings = false),
        ToolPermissionInfo("weather", "Weather", PermissionCategory.SYSTEM, PermissionLevel.BYPASS, showInSettings = false),
        ToolPermissionInfo("notification", "Notifications", PermissionCategory.SYSTEM, PermissionLevel.BYPASS, showInSettings = false),
        ToolPermissionInfo("device_info", "Device Info", PermissionCategory.SYSTEM, PermissionLevel.BYPASS, showInSettings = false),
        // T330: integrations — opt-in by default. These tools can drive
        // other apps and read on-screen content, so the safer posture is
        // NOT_ALLOWED until the user picks otherwise even when the
        // underlying system layer (Shizuku binder / Accessibility service)
        // is already authorized.
        ToolPermissionInfo("a11y_cli", "android-a11y-cli", PermissionCategory.INTEGRATIONS, PermissionLevel.NOT_ALLOWED),
        ToolPermissionInfo("shizuku_cli", "android-shizuku-cli", PermissionCategory.INTEGRATIONS, PermissionLevel.NOT_ALLOWED),
    )

    /** Stable session-id used by NativeOffloadHandlers when calling
     *  [checkPermission] from the offload IPC thread. The shell-CLI gate
     *  (T330) lives outside ChatViewModel.executeTool, so there's no
     *  per-chat-session id available to scope ASK_ONCE grants. We use
     *  one process-lifetime grant slot instead — the user "Always Allow"
     *  upgrades naturally to BYPASS via [respondToRequest]. */
    const val OFFLOAD_GLOBAL_SESSION_ID = "offload-global"

    private lateinit var prefs: SharedPreferences

    /** Optional scope keeps a paid model's session grant bound to the route shown in its dialog. */
    private data class SessionGrant(val toolName: String, val scope: String?)

    /** Session-scoped grants for ASK_ONCE tools, kept only in process memory. */
    private val sessionGrants = mutableMapOf<String, MutableSet<SessionGrant>>()

    /** T338: session-scoped denials. Populated by the "Deny in this
     *  session" dialog response. Once a tool is in here for a given
     *  session, [checkPermission] returns false without re-prompting
     *  — prevents the agent from spamming the user with the same
     *  request after they already said no. Cleared with
     *  [clearSessionGrants]. */
    private val sessionDenials = mutableMapOf<String, MutableSet<String>>() // sessionId -> set of toolNames

    /** Active permission request waiting for user response. */
    private val _pendingRequest = MutableStateFlow<PermissionRequest?>(null)
    val pendingRequest: StateFlow<PermissionRequest?> = _pendingRequest.asStateFlow()

    // Only one dialog/continuation may be active, even if two sessions invoke
    // different tools concurrently. A waiting request must never replace the
    // continuation for the dialog already shown to the person.
    private val permissionQueue = Mutex()
    private val permissionStateLock = Any()
    // Any settings change invalidates an answer to an older, still-open dialog.
    private val permissionVersions = mutableMapOf<String, Long>()
    // nanoMuse: retain an epoch only while checks or transport observations are active.
    private data class SessionEpoch(var version: Long = 0, var checks: Int = 0)
    private val activeSessionEpochs = mutableMapOf<String, SessionEpoch>()
    // Incremented under permissionStateLock; never reused while this process lives.
    private var nextPermissionRequestId = 0L
    private var pendingContinuation: kotlin.coroutines.Continuation<Response>? = null

    // ── Android system runtime permission request (for location etc.) ──────────

    /** Result of a runtime permission or settings-gate flow. */
    enum class AndroidPermissionResult { GRANTED, DENIED, TIMEOUT }

    /** Timeout for a single system permission dialog round-trip. */
    const val SYSTEM_DIALOG_TIMEOUT_MS: Long = 120_000L

    /** Timeout for the "bounce the user to a settings page" flow. */
    const val SETTINGS_GATE_TIMEOUT_MS: Long = 120_000L

    data class AndroidPermissionRequest(val permissions: List<String>)

    private val _pendingAndroidPermission = MutableStateFlow<AndroidPermissionRequest?>(null)
    val pendingAndroidPermission: StateFlow<AndroidPermissionRequest?> = _pendingAndroidPermission.asStateFlow()

    private var androidPermissionContinuation: kotlin.coroutines.Continuation<AndroidPermissionResult>? = null

    /**
     * Ask the UI layer to drive the system runtime-permission flow for the
     * given permissions. The UI is responsible for:
     *
     *  - Detecting whether the permission has already been permanently denied
     *    (so the system dialog would be a no-op). If so, responding with
     *    [AndroidPermissionResult.DENIED] so the caller can fall back to the
     *    in-app "go to settings" flow via [requestSettingsGate].
     *  - Otherwise launching the `RequestMultiplePermissions` contract and
     *    returning the result.
     *
     * The call is suspended until the UI responds or [SYSTEM_DIALOG_TIMEOUT_MS]
     * elapses.
     */
    suspend fun requestAndroidPermission(permissions: List<String>): AndroidPermissionResult {
        val timed = withTimeoutOrNull(SYSTEM_DIALOG_TIMEOUT_MS) {
            suspendCancellableCoroutine<AndroidPermissionResult> { cont ->
                androidPermissionContinuation = cont
                _pendingAndroidPermission.value = AndroidPermissionRequest(permissions)
                cont.invokeOnCancellation {
                    _pendingAndroidPermission.value = null
                    androidPermissionContinuation = null
                }
            }
        }
        if (timed == null) {
            // Timed out — clear the pending request so the UI doesn't fire a
            // stale permission dialog later.
            _pendingAndroidPermission.value = null
            androidPermissionContinuation = null
            return AndroidPermissionResult.TIMEOUT
        }
        return timed
    }

    /** Called from UI after the system permission dialog returns. */
    fun respondToAndroidPermission(result: AndroidPermissionResult) {
        _pendingAndroidPermission.value = null
        androidPermissionContinuation?.resume(result)
        androidPermissionContinuation = null
    }

    /** Overload kept for callers that only have a granted/denied bool. */
    fun respondToAndroidPermission(granted: Boolean) =
        respondToAndroidPermission(
            if (granted) AndroidPermissionResult.GRANTED else AndroidPermissionResult.DENIED
        )

    // ── In-app "go to settings" gate ───────────────────────────────────────────

    /**
     * Describes a gate we can't resolve with the standard permission dialog:
     * either the user has already permanently denied the runtime permission,
     * or the capability (e.g. Notification Access) requires a trip to a
     * system settings page.
     */
    data class SettingsGateRequest(
        /** Stable id for the gate (e.g. "POST_NOTIFICATIONS" or "notification_access"). */
        val id: String,
        /** Title shown in the in-app AlertDialog. */
        val title: String,
        /** Body shown in the in-app AlertDialog. */
        val message: String,
        /** Intent action used to open the appropriate settings page. */
        val settingsAction: String,
        /**
         * Set when the target settings page is "Application details" and the
         * Intent must include a `package:<pkg>` data URI.
         */
        val requiresPackageUri: Boolean,
        /** "Allow" button label for the in-app dialog. */
        val positiveLabel: String = "Open Settings",
        /** "Cancel" button label for the in-app dialog. */
        val negativeLabel: String = "Cancel",
    )

    /** What the UI actor decided after the dialog closed. */
    enum class SettingsGateDecision { OPEN, CANCEL }

    private val _pendingSettingsGate = MutableStateFlow<SettingsGateRequest?>(null)
    val pendingSettingsGate: StateFlow<SettingsGateRequest?> = _pendingSettingsGate.asStateFlow()

    private var settingsGateContinuation: kotlin.coroutines.Continuation<SettingsGateDecision>? = null

    /**
     * Show an in-app dialog explaining why a settings trip is needed, then
     * (if the user accepts) wait up to [SETTINGS_GATE_TIMEOUT_MS] polling
     * [check] for success.
     *
     * Returns:
     *  - [AndroidPermissionResult.GRANTED] if [check] succeeds within the
     *    polling window.
     *  - [AndroidPermissionResult.DENIED] if the user cancels the dialog.
     *  - [AndroidPermissionResult.TIMEOUT] if the user accepts but doesn't
     *    complete the grant within the window.
     */
    suspend fun requestSettingsGate(
        request: SettingsGateRequest,
        check: () -> Boolean,
    ): AndroidPermissionResult {
        val decision = suspendCancellableCoroutine<SettingsGateDecision> { cont ->
            settingsGateContinuation = cont
            _pendingSettingsGate.value = request
            cont.invokeOnCancellation {
                _pendingSettingsGate.value = null
                settingsGateContinuation = null
            }
        }
        if (decision == SettingsGateDecision.CANCEL) return AndroidPermissionResult.DENIED

        val granted = withTimeoutOrNull(SETTINGS_GATE_TIMEOUT_MS) {
            while (true) {
                if (check()) return@withTimeoutOrNull true
                delay(500L)
            }
            @Suppress("UNREACHABLE_CODE") false
        }
        return when (granted) {
            true -> AndroidPermissionResult.GRANTED
            else -> AndroidPermissionResult.TIMEOUT
        }
    }

    /**
     * Poll [check] every [intervalMs] for up to [timeoutMs] after a DENIED
     * result from [requestAndroidPermission]. Catches the "grant propagation
     * race" — the system dialog returned before the PackageManager fully
     * committed the grant, or the user was still mid-tap when DENIED fired.
     */
    suspend fun pollForPermissionGrant(
        check: () -> Boolean,
        timeoutMs: Long = 5_000L,
        intervalMs: Long = 500L,
    ): Boolean {
        val granted = withTimeoutOrNull(timeoutMs) {
            while (true) {
                if (check()) return@withTimeoutOrNull true
                delay(intervalMs)
            }
            @Suppress("UNREACHABLE_CODE") false
        }
        return granted == true
    }

    /** Called from UI after the in-app "go to settings" dialog closes. */
    fun respondToSettingsGate(decision: SettingsGateDecision) {
        _pendingSettingsGate.value = null
        settingsGateContinuation?.resume(decision)
        settingsGateContinuation = null
    }

    /**
     * Android's `shouldShowRequestPermissionRationale` returns false in two
     * cases: (a) the app has never asked, and (b) the user permanently
     * denied. We track (a) ourselves so the UI can distinguish them.
     */
    fun hasAskedForPermission(context: Context, permission: String): Boolean {
        val p = context.getSharedPreferences("offload_permissions_asked", Context.MODE_PRIVATE)
        return p.getBoolean(permission, false)
    }

    fun markPermissionAsked(context: Context, permission: String) {
        context.getSharedPreferences("offload_permissions_asked", Context.MODE_PRIVATE)
            .edit().putBoolean(permission, true).apply()
    }

    fun init(context: Context) {
        prefs = context.getSharedPreferences("offload_permissions", Context.MODE_PRIVATE)
    }

    fun getLevel(toolName: String): PermissionLevel {
        val info = toolRegistry.find { it.toolName == toolName }
            ?: return PermissionLevel.BYPASS // Unknown tools are bypassed

        val stored = prefs.getString("level_$toolName", null)
        return if (stored != null) {
            try { PermissionLevel.valueOf(stored) } catch (_: Exception) { info.defaultLevel }
        } else {
            info.defaultLevel
        }
    }

    fun setLevel(toolName: String, level: PermissionLevel) = synchronized(permissionStateLock) {
        prefs.edit().putString("level_$toolName", level.name).apply()
        invalidateToolGrants(toolName)
    }

    fun resetAll() = synchronized(permissionStateLock) {
        val editor = prefs.edit()
        for (tool in toolRegistry) {
            editor.remove("level_${tool.toolName}")
            invalidateToolGrants(tool.toolName)
        }
        editor.apply()
    }

    /** Caller holds [permissionStateLock]. */
    private fun invalidateToolGrants(toolName: String) {
        permissionVersions[toolName] = (permissionVersions[toolName] ?: 0L) + 1L
        sessionGrants.values.forEach { grants -> grants.removeAll { it.toolName == toolName } }
        sessionDenials.values.forEach { it.remove(toolName) }
    }

    /**
     * T338: dialog response shape. Replaces the older
     * `(allowed: Boolean, alwaysAllow: Boolean)` pair, which encoded
     * "Always Allow" as a persistent BYPASS upgrade. The new model is
     * strictly session-scoped — the user goes to Settings → Permissions
     * to make a permanent change.
     *
     *   ALLOW_SESSION  — grant for the rest of this session, no more
     *                    prompts for this tool until session ends.
     *   ALLOW_ONCE     — grant just this call; next call re-prompts.
     *   DENY_SESSION   — deny + remember the deny so the agent can't
     *                    keep nagging. Cleared with the session.
     */
    enum class Response { ALLOW_SESSION, ALLOW_ONCE, DENY_SESSION }

    // nanoMuse: a revocation observation is not an approval. It must be opened
    // before checkPermission, used only after that check succeeds, and closed
    // after the operation. Keeping the session epoch alive also invalidates
    // one-time approvals when a chat is cleared after its dialog has closed.
    internal interface PermissionObservation : AutoCloseable {
        fun isCurrent(): Boolean
    }

    internal fun observePermission(toolName: String, sessionId: String): PermissionObservation {
        val (epoch, sessionVersion, toolVersion) = synchronized(permissionStateLock) {
            val current = activeSessionEpochs.getOrPut(sessionId) { SessionEpoch() }
            current.checks++
            Triple(current, current.version, permissionVersions[toolName] ?: 0L)
        }
        return object : PermissionObservation {
            private var closed = false

            override fun isCurrent(): Boolean = synchronized(permissionStateLock) {
                !closed && epoch.version == sessionVersion &&
                    (permissionVersions[toolName] ?: 0L) == toolVersion &&
                    getLevel(toolName) != PermissionLevel.NOT_ALLOWED &&
                    toolName !in sessionDenials[sessionId].orEmpty()
            }

            override fun close() = synchronized(permissionStateLock) {
                if (!closed) {
                    closed = true
                    releaseSessionEpoch(sessionId, epoch)
                }
            }
        }
    }

    /** Caller holds [permissionStateLock]. */
    private fun releaseSessionEpoch(sessionId: String, epoch: SessionEpoch) {
        epoch.checks--
        if (epoch.checks == 0 && activeSessionEpochs[sessionId] === epoch) activeSessionEpochs.remove(sessionId)
    }

    /**
     * Check permission for a tool in the given session.
     * For ASK_ONCE, suspends until user responds via the dialog. A non-null
     * grantScope limits a remembered approval to that exact scope; denials
     * remain tool-wide for the session. The scope is never persisted or shown.
     * Returns true if allowed.
     */
    suspend fun checkPermission(
        toolName: String,
        toolTitle: String,
        sessionId: String,
        requireFreshApproval: Boolean = false,
        grantScope: String? = null,
    ): Boolean {
        val grant = SessionGrant(toolName, grantScope)
        val (epoch, sessionVersion) = synchronized(permissionStateLock) {
            val current = activeSessionEpochs.getOrPut(sessionId) { SessionEpoch() }
            current.checks++
            current to current.version
        }
        try {
            when (getLevel(toolName)) {
                PermissionLevel.BYPASS -> if (!requireFreshApproval) return true
                PermissionLevel.NOT_ALLOWED -> return false
                PermissionLevel.ASK_ONCE -> Unit
            }
            return permissionQueue.withLock {
                // The session or setting may have changed while this request was queued.
                val version = synchronized(permissionStateLock) {
                    if (epoch.version != sessionVersion) return@withLock false
                    when (getLevel(toolName)) {
                        PermissionLevel.BYPASS -> if (!requireFreshApproval) return@withLock true
                        PermissionLevel.NOT_ALLOWED -> return@withLock false
                        PermissionLevel.ASK_ONCE -> Unit
                    }
                    if (toolName in sessionDenials[sessionId].orEmpty()) return@withLock false
                    if (!requireFreshApproval && grant in sessionGrants[sessionId].orEmpty()) return@withLock true
                    permissionVersions[toolName] ?: 0L
                }

                val info = toolRegistry.find { it.toolName == toolName }
                val response = suspendCancellableCoroutine<Response> { cont ->
                    val published = synchronized(permissionStateLock) {
                        if (epoch.version != sessionVersion ||
                            (permissionVersions[toolName] ?: 0L) != version) false
                        else {
                            pendingContinuation = cont
                            _pendingRequest.value = PermissionRequest(
                                requestId = nextPermissionRequestId++,
                                toolName = toolName,
                                toolTitle = toolTitle,
                                description = "Allow ${info?.displayName ?: toolName} access?",
                                sessionId = sessionId,
                                oneTimeOnly = requireFreshApproval,
                            )
                            true
                        }
                    }
                    if (!published) cont.resume(Response.DENY_SESSION)
                    else cont.invokeOnCancellation {
                        synchronized(permissionStateLock) {
                            if (pendingContinuation === cont) {
                                _pendingRequest.value = null
                                pendingContinuation = null
                            }
                        }
                    }
                }
                // A settings or session change while the dialog was open
                // invalidates its answer, including an ABA settings change.
                synchronized(permissionStateLock) {
                    if (epoch.version != sessionVersion || getLevel(toolName) == PermissionLevel.NOT_ALLOWED ||
                        (permissionVersions[toolName] ?: 0L) != version) return@withLock false
                    when (response) {
                        Response.ALLOW_SESSION -> {
                            if (!requireFreshApproval) sessionGrants.getOrPut(sessionId) { mutableSetOf() }.add(grant)
                            true
                        }
                        Response.ALLOW_ONCE -> true
                        Response.DENY_SESSION -> {
                            sessionDenials.getOrPut(sessionId) { mutableSetOf() }.add(toolName)
                            false
                        }
                    }
                }
            }
        } finally {
            // nanoMuse: checks and transport observations share the same bounded epoch lifetime.
            synchronized(permissionStateLock) { releaseSessionEpoch(sessionId, epoch) }
        }
    }

    /** Only the dialog that was actually shown may answer its own request. */
    fun respondToRequest(requestId: Long, response: Response) {
        val continuation = synchronized(permissionStateLock) {
            if (_pendingRequest.value?.requestId != requestId) return
            pendingContinuation.also {
                pendingContinuation = null
                _pendingRequest.value = null
            }
        }
        continuation?.resume(response)
    }

    /** Clear grants and denials and withdraw this session's outstanding requests. */
    fun clearSessionGrants(sessionId: String) {
        val continuation = synchronized(permissionStateLock) {
            sessionGrants.remove(sessionId)
            sessionDenials.remove(sessionId)
            activeSessionEpochs[sessionId]?.let { it.version++ }
            if (_pendingRequest.value?.sessionId == sessionId) {
                pendingContinuation.also {
                    pendingContinuation = null
                    _pendingRequest.value = null
                }
            } else null
        }
        // nanoMuse: stop the old managed peer while registrations are paused.
        // Requests already sent cannot be retracted or unbilled here.
        if (com.openminis.app.BuildConfig.APPLICATION_ID == "ai.tama.assistant.tamav2test") {
            try {
                ai.tama.media.VideoAttemptTracker.pauseSession(sessionId)
                try {
                    ai.tama.media.ImageAttemptTracker.pauseSession(sessionId)
                    // nanoMuse: direct model-use calls have their own conservative
                    // submission marker and can also arrive late from the shell.
                    ai.tama.net.ModelUseAttemptTracker.pauseSession(sessionId)
                    try {
                        // Slot owners and waiters may not have reached a native image yet.
                        try {
                            ai.tama.chat.ChatSessionJobs.pauseSession(sessionId)
                        } finally {
                            try { com.openminis.app.sandbox.ExecutionCoordinator.stopCurrentCommand(sessionId) }
                            finally { ai.tama.chat.ChatSessionJobs.resumeSession(sessionId) }
                        }
                    } finally { ai.tama.net.ModelUseAttemptTracker.resumeSession(sessionId) }
                } finally { ai.tama.media.ImageAttemptTracker.resumeSession(sessionId) }
            } finally { ai.tama.media.VideoAttemptTracker.resumeSession(sessionId) }
        }
        // Resume outside the lock. The changed epoch makes this a refusal, not
        // a new cached denial, and advances the queue for other sessions.
        continuation?.resume(Response.DENY_SESSION)
    }
}
