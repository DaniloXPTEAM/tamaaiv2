package com.openminis.app.sandbox

import android.content.Context
import android.util.Log
import com.openminis.app.data.repository.EnvVarRepository
import io.github.nanomuse.sandbox.FeedSandboxScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages per-session persistent shell processes.
 *
 * Architecture:
 * - PRootKernel (rootfs + proot binary): global singleton, booted once
 * - PersistentShell: one per sessionId, owns its bind mounts and /bin/sh process
 * - Per-session Mutex: different sessions can run commands concurrently
 * - ConcurrentHashMap: thread-safe shell/mutex registry
 *
 * Concurrency guarantees:
 * - Same session: commands are serialized by the per-session Mutex
 * - Different sessions: run concurrently (each has its own Mutex)
 * - Shell creation: protected by globalLock to prevent duplicate shells
 * - Shell death: detected on next command, shell is recreated with same bind mounts
 */
object ExecutionCoordinator {

    private const val TAG = "ExecutionCoordinator"
    // nanoMuse: shell commands and paths can contain private input or credentials.
    private val nmParallel: Boolean get() = com.openminis.app.BuildConfig.APPLICATION_ID == "ai.tama.assistant.tamav2test"

    data class CommandResult(
        val output: String,
        val exitCode: Int,
        val durationMs: Long
    )

    private lateinit var appContext: Context
    var envVarRepository: EnvVarRepository? = null

    /** Thread-safe per-session shell registry. */
    private val shells = ConcurrentHashMap<String, PersistentShell>()

    // nanoMuse: a command queued before Stop must not create a new shell after it.
    private class ShellEpoch(var current: Boolean = true)
    private val shellRegistryLock = Any()
    private val shellEpochs = mutableMapOf<String, ShellEpoch>()

    private fun requireCurrentEpoch(sessionId: String, epoch: ShellEpoch?, job: Job?) {
        if (epoch == null) return // normal package keeps the legacy path
        synchronized(shellRegistryLock) {
            if (!epoch.current || shellEpochs[sessionId] !== epoch || job?.isActive == false)
                throw CancellationException("Shell command stopped")
        }
    }

    /** Thread-safe per-session mutex registry. */
    private val mutexes = ConcurrentHashMap<String, Mutex>()

    /**
     * Per-session snapshot of the env-var keys injected on the previous
     * `applyEnvironment` call. Used to issue `unset` for keys the user has
     * since deleted from EnvVarRepository. Long-lived shells would otherwise
     * keep the stale value around indefinitely.
     */
    private val lastInjectedKeys = ConcurrentHashMap<String, Set<String>>()

    /**
     * Global lock used only for shell creation to prevent duplicate shells
     * when the same session's first command arrives concurrently.
     */
    private val globalLock = Mutex()

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    // nanoMuse: test-only view of the per-session registry, so the instrumentation test can
    // observe a shell's lifetime without reflection (the old single mountedSessionId is gone).
    internal fun hasShell(sessionId: String): Boolean = shells.containsKey(sessionId)

    // nanoMuse: bounded diagnostics for isolated instrumentation; does not change shell state.
    internal fun testShellDiagnostics(sessionId: String): String = shells[sessionId]?.let {
        "alive=${it.isAlive}; exit=${it.lastExitCode}; output=" +
            ai.tama.net.SandboxLogPrivacy.captured(nmParallel, it.deathTail().take(400)) // nanoMuse: no prior output in diagnostics.
    } ?: "no shell"

    /**
     * Execute a command in the session's persistent shell.
     *
     * Flow:
     * 1. Get or create per-session Mutex (thread-safe via ConcurrentHashMap)
     * 2. Acquire per-session Mutex (serializes commands within same session)
     * 3. Get or create PersistentShell (protected by globalLock on creation)
     * 4. Execute command
     */
    suspend fun execute(
        sessionId: String,
        command: String,
        timeout: Long = 600_000L,
        lineCallback: ((String) -> Unit)? = null
    ): CommandResult {
        // nanoMuse: capture before waiting for a session slot; Stop invalidates
        // this exact object even when a later command opens the same session.
        val job = currentCoroutineContext()[Job]
        val epoch = if (nmParallel) synchronized(shellRegistryLock) {
            if (job?.isActive == false) throw CancellationException("Shell command stopped")
            shellEpochs.getOrPut(sessionId) { ShellEpoch() }
        } else null
        // ConcurrentHashMap.getOrPut is not atomic, use putIfAbsent pattern
        val mutex = mutexes.getOrPut(sessionId) { Mutex() }

        return mutex.withLock {
            requireCurrentEpoch(sessionId, epoch, job)
            val startTime = System.currentTimeMillis()

            // Auto-boot PRoot if not already booted
            if (!PRootKernel.isBooted) {
                Log.i(TAG, if (nmParallel) "Auto-booting PRootKernel" else "[$sessionId] Auto-booting PRootKernel") // nanoMuse: omit session id.
                PRootKernel.boot(appContext)
            }

            // nanoMuse: rebuild the disposable Feed view before every shell command. The real
            // app-owned Feed is never bound, even if a command bypasses the read-only wrappers.
            FeedSandboxScope.refresh(appContext, sessionId)

            // [diag] trace the sessionId that shell_execute is dispatched with —
            // suspected source of the Chinese-emoji filename vanishing bug
            // nanoMuse: never put commands or session ids in parallel-package logcat.
            Log.w(TAG, ai.tama.net.SandboxLogPrivacy.dispatch(nmParallel, sessionId, command))

            // Get or create shell — protected by globalLock to avoid duplicate creation
            val shell = getOrCreateShell(sessionId, epoch, job)
            requireCurrentEpoch(sessionId, epoch, job) // nanoMuse: Stop may have won during startup.

            // Inject user-defined environment variables as a *full snapshot*
            // (T124a). Pass the previously-injected key set so applyEnvironment
            // can `unset` anything the user has since removed from settings;
            // otherwise the long-lived shell would keep stale values.
            val configuredEnv = envVarRepository?.allAsDict() ?: emptyMap()
            // nanoMuse: restore this shell's session before each command, even if a
            // stored environment variable or a prior command changed it. A guest can
            // still override its own env for a particular exec; this is not authentication.
            val envVars = if (nmParallel) configuredEnv + ("MINIS_CHAT_SESSION_ID" to sessionId) else configuredEnv
            val previousKeys = lastInjectedKeys[sessionId] ?: emptySet()
            if (envVars.isNotEmpty() || previousKeys.isNotEmpty()) {
                shell.applyEnvironment(envVars, previousKeys = previousKeys)
                lastInjectedKeys[sessionId] = envVars.keys.toSet()
            }

            requireCurrentEpoch(sessionId, epoch, job) // nanoMuse: do not dispatch a queued, stopped command.
            val (rawOutput, exitCode) = shell.executeCommand(
                command = command,
                timeout = timeout,
                lineCallback = lineCallback,
            )

            val durationMs = System.currentTimeMillis() - startTime
            val sanitized = TerminalSanitizer.sanitize(rawOutput)
            val truncated = TerminalSanitizer.truncateIfNeeded(sanitized)
            val output = if (exitCode != 0 && exitCode != 124) {
                "$truncated\n(exit code: $exitCode)"
            } else {
                truncated
            }

            CommandResult(output = output, exitCode = exitCode, durationMs = durationMs)
        }
    }

    /**
     * Get the existing shell for this session, or create a new one.
     * Uses globalLock to prevent two coroutines from simultaneously creating
     * a shell for the same session (e.g. if the old shell just died).
     */
    private suspend fun getOrCreateShell(sessionId: String, epoch: ShellEpoch?, job: Job?): PersistentShell {
        requireCurrentEpoch(sessionId, epoch, job)
        // Fast path: existing alive shell
        val existing = shells[sessionId]
        if (existing != null && existing.isAlive) {
            Log.w(TAG, if (nmParallel) "reuse existing shell" else
                "[diag] reuse existing shell for sessionId=$sessionId attachmentsMount=${existing.debugBindMount("/var/minis/attachments")}") // nanoMuse: omit session and host path.
            return existing
        }

        // Slow path: need to create (or recreate after crash)
        return globalLock.withLock {
            requireCurrentEpoch(sessionId, epoch, job) // nanoMuse: Stop may have won while waiting.
            // Double-check after acquiring lock
            val recheck = shells[sessionId]
            if (recheck != null && recheck.isAlive) {
                Log.w(TAG, if (nmParallel) "reuse existing shell" else
                    "[diag] reuse existing shell (post-lock) for sessionId=$sessionId attachmentsMount=${recheck.debugBindMount("/var/minis/attachments")}") // nanoMuse: omit session and host path.
                return@withLock recheck
            }

            // Shell is dead or missing — clean up and create fresh
            if (recheck != null) {
                Log.w(TAG, if (nmParallel) "Shell died unexpectedly, recreating" else "[$sessionId] Shell died unexpectedly, recreating") // nanoMuse: omit session id.
                recheck.stop()
            }

            val bindMounts = buildSessionBindMounts(sessionId)
            val shell = PersistentShell(appContext, sessionId, bindMounts)
            // nanoMuse: publish and validate under the same lock as Stop. Once
            // published, PersistentShell.stop() also closes a racing startup.
            synchronized(shellRegistryLock) {
                requireCurrentEpoch(sessionId, epoch, job)
                shells[sessionId] = shell
            }
            try {
                shell.ensureStarted()
            } catch (t: Throwable) {
                if (nmParallel) {
                    synchronized(shellRegistryLock) { shells.remove(sessionId, shell) }
                    shell.stop()
                }
                throw t
            }
            requireCurrentEpoch(sessionId, epoch, job)
            Log.i(TAG, if (nmParallel) "Shell created" else "[$sessionId] Shell created with ${bindMounts.size} bind mounts") // nanoMuse: omit session id.
            Log.w(TAG, if (nmParallel) "new shell created" else
                "[diag] new shell created sessionId=$sessionId attachmentsMount=${bindMounts["/var/minis/attachments"]}") // nanoMuse: omit session and host path.
            shell
        }
    }

    /**
     * Build bind mounts for a session:
     * - Session-level: workspace, attachments, offloads, browser → per-session dirs
     * - Global: memory, skills, shared → shared dirs across all sessions
     */
    private fun buildSessionBindMounts(sessionId: String): Map<String, String> {
        val filesDir = appContext.filesDir
        val mounts = linkedMapOf<String, String>()

        // [diag] previous attachments mount target — exposes cross-session
        // overwrite of the global bindMounts map (the suspected cause of
        // the "file disappears after first download" bug)
        val prevAttachments = PRootKernel.bindMounts["/var/minis/attachments"]

        // Session-specific directories
        val sessionBase = File(filesDir, "minis-sessions/$sessionId")
        listOf("attachments", "offloads", "workspace", "browser").forEach { subdir ->
            val hostDir = File(sessionBase, subdir).also { it.mkdirs() }
            val linuxPath = "/var/minis/$subdir"
            mounts[linuxPath] = hostDir.absolutePath
            PRootKernel.addBindMount(linuxPath, hostDir.absolutePath)
        }

        Log.w(TAG, if (nmParallel) "session bind mounts prepared" else
            "[diag] buildSessionBindMounts sessionId=$sessionId " +
                "attachments: prev=$prevAttachments new=${mounts["/var/minis/attachments"]}") // nanoMuse: omit session and host paths.

        // Global shared directories.
        // [T-android-mcp-bind-mount] mcp-servers MUST be here, not only in
        // PRootKernel.registerGlobalBindMounts: PersistentShell builds PRoot's
        // `-b` argv from THIS map, so a subdir missing here is invisible to the
        // shell that runs minis-mcp-cli — /var/minis/mcp-servers/servers.json
        // then resolves to the empty rootfs placeholder and `minis-mcp-cli list`
        // returns {"servers": [], "count": 0} even though the UI wrote the
        // server (the UI / debug.ls read via resolveHostPath, a separate map,
        // which is why they disagreed). Same trap as the external-mounts note
        // below.
        val globalBase = File(filesDir, "minis-global")
        listOf("memory", "skills", "shared", "mcp-servers").forEach { subdir ->
            val hostDir = File(globalBase, subdir).also { it.mkdirs() }
            val linuxPath = "/var/minis/$subdir"
            mounts[linuxPath] = hostDir.absolutePath
            PRootKernel.addBindMount(linuxPath, hostDir.absolutePath)
        }
        // nanoMuse: unlike the writable global dirs above, Feed uses a disposable mirror because
        // PRoot's bind syntax cannot make the app-private source read-only.
        val feedMirror = FeedSandboxScope.refresh(appContext, sessionId)
        mounts[FeedSandboxScope.LINUX_ROOT] = feedMirror.absolutePath

        // T277: user-mounted external folders (SAF-picked trees). PersistentShell
        // uses this map verbatim as PRoot's `-b` argv, so any mount missing here
        // is invisible to the shell — `ls /var/minis/mounts/<name>/` then shows
        // only the empty rootfs placeholder. PRootKernel.bindMounts is kept in
        // sync separately by applyMountedFoldersSnapshot for the resolveHostPath
        // path (debug.ls, file_read, …) but does NOT feed the live PRoot argv.
        // Skip entries whose SAF tree URI didn't decode to a POSIX path
        // (cloud providers, unmounted removable storage).
        PRootKernel.mountedFoldersStore?.entries?.value?.forEach { entry ->
            val host = entry.resolvedHostPath ?: return@forEach
            val linuxPath = "/var/minis/mounts/${entry.name}"
            mounts[linuxPath] = host
        }

        return mounts
    }

    /**
     * Called when a session is closed. Stops and removes the shell.
     */
    fun sessionDidTerminate(sessionId: String) {
        val shell = synchronized(shellRegistryLock) {
            if (nmParallel) shellEpochs.remove(sessionId)?.current = false
            shells.remove(sessionId)
        }
        mutexes.remove(sessionId)
        // nanoMuse: this cache contains only the session's disposable, read-only Feed mirror.
        FeedSandboxScope.clear(appContext, sessionId)
        // T124a: drop the snapshot too — a future shell for the same id
        // restarts from a clean baseline, so the next applyEnvironment
        // shouldn't try to `unset` keys that don't exist in the new shell.
        lastInjectedKeys.remove(sessionId)
        shell?.stop()
        if (shell != null) Log.i(TAG, if (nmParallel) "Shell terminated" else "[$sessionId] Shell terminated") // nanoMuse: omit session id.
    }

    /**
     * Stop the shell for a specific session (e.g. user tapped cancel).
     * The shell process is killed; next command will recreate it.
     */
    fun stopCurrentCommand(sessionId: String? = null) {
        if (sessionId != null) {
            val shell = synchronized(shellRegistryLock) {
                if (nmParallel) shellEpochs.remove(sessionId)?.current = false
                shells.remove(sessionId)
            }
            // T124a: snapshot belongs to the now-dead shell.
            lastInjectedKeys.remove(sessionId)
            shell?.stop()
            Log.i(TAG, if (nmParallel) "Shell stopped by user" else "[$sessionId] Shell stopped by user") // nanoMuse: omit session id.
        } else {
            // Stop all sessions (legacy/fallback)
            val oldShells = synchronized(shellRegistryLock) {
                if (nmParallel) {
                    shellEpochs.values.forEach { it.current = false }
                    shellEpochs.clear()
                }
                shells.values.toList().also { shells.clear() }
            }
            oldShells.forEach { it.stop() }
            lastInjectedKeys.clear()
            ShellExecutor.destroyCurrent()
        }
    }

    /** Legacy overload for callers without sessionId. */
    fun stopCurrentCommand() = stopCurrentCommand(sessionId = null)

    /**
     * Propagate a system-timezone change to every live shell.
     *
     * - Updates [PRootKernel.customEnvironment]["TZ"] so future shells inherit
     *   the new value at spawn time.
     * - Exports the new TZ into every already-running [PersistentShell] via
     *   `export TZ=...` on stdin.
     * - Asks [TerminalSession] to do the same for every live interactive PTY.
     *
     * Safe to call before PRoot has booted — it's a no-op in that case.
     */
    suspend fun broadcastTimezoneChange() {
        if (!PRootKernel.isBooted) return
        val tz = PRootKernel.updateTimezone()
        val tzMap = mapOf("TZ" to tz)
        for ((_, shell) in shells) {
            if (shell.isAlive) shell.applyEnvironment(tzMap)
        }
        TerminalSession.broadcastTimezone(tz)
    }

    /**
     * Propagate a system-proxy change to every live shell. Exports all six
     * proxy keys as a block — empty strings when no proxy is configured, so
     * a disable transition clears the old values in-place without needing
     * a separate `unset`.
     *
     * Safe to call before PRoot has booted — it's a no-op in that case.
     */
    suspend fun broadcastProxyChange() {
        if (!PRootKernel.isBooted) return
        val env = PRootKernel.updateProxy(appContext)
        for ((_, shell) in shells) {
            if (shell.isAlive) shell.applyEnvironment(env)
        }
        TerminalSession.broadcastProxy(env)
    }
}
