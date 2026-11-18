"""Session-scoped offload approvals must end with the conversation that earned them.

These source checks cover the Android lifecycle wiring; behavior of the permission
queue and invalidation is exercised by OffloadPermissionQueueTest.
"""

from pathlib import Path

import pytest

ANDROID = Path(__file__).resolve().parents[1] / "android/src/android/app/src/main/java"


def method_source(path: str, start: str, end: str) -> str:
    source = (ANDROID / path).read_text(encoding="utf-8")
    return source.split(start, 1)[1].split(end, 1)[0]


@pytest.mark.parametrize(
    ("path", "start", "end", "session", "mutation"),
    [
        (
            "com/openminis/app/ui/chat/ChatViewModel.kt",
            "    fun clearChat() {",
            "    // ─── Share Injection",
            "sid",
            "_messages.value = emptyList()",
        ),
        (
            "com/openminis/app/data/repository/ChatRepository.kt",
            "    suspend fun deleteSession(id: String) {",
            "    // ─── Session groups",
            "id",
            "dao.deleteMessages(id)",
        ),
        (
            "io/github/nanomuse/sync/LocalChats.kt",
            "    override suspend fun deleteSession(id: String) {",
            "    override suspend fun clearMessages",
            "id",
            "dao.deleteMessages(id)",
        ),
        (
            "io/github/nanomuse/sync/LocalChats.kt",
            "    override suspend fun clearMessages(sessionId: String) {",
            "    override suspend fun insertMessage",
            "sessionId",
            "dao.deleteMessages(sessionId)",
        ),
        (
            "io/github/nanomuse/account/AccountData.kt",
            "    private suspend fun deleteChats(ctx: Context, account: String) {",
            "    private fun move(",
            "id",
            "repo.dao.deleteMessages(id)",
        ),
    ],
)
def test_conversation_clear_or_delete_withdraws_offload_approvals(
    path: str, start: str, end: str, session: str, mutation: str
) -> None:
    body = method_source(path, start, end)
    cleanup = f"OffloadPermissionManager.clearSessionGrants({session})"
    assert cleanup in body
    assert body.index(cleanup) < body.index(mutation)


def test_stop_invalidates_registered_video_and_surfaces_possible_charge() -> None:
    body = method_source(
        "com/openminis/app/ui/chat/ChatViewModel.kt",
        "    fun cancelStream() {",
        "    /**\n",
    )
    assert "listOf(activeSessionId, sessionId) else listOf(activeSessionId)" in body
    pause = body.index("VideoAttemptTracker.pauseSession(sid)")
    cancel = body.index("nmTrackedLocalRuns.stop(job) { job.cancel() }", pause)
    stop = body.index("ExecutionCoordinator.stopCurrentCommand(sid)", cancel)
    resume = body.index("VideoAttemptTracker.resumeSession(it)", stop)
    assert pause < cancel < stop < resume
    assert "if (stoppedVideoMayHaveSubmitted)" in body
    assert "R.string.nm_video_request_failed" in body
    manager = (ANDROID / "com/openminis/app/offload/OffloadPermissionManager.kt").read_text(
        encoding="utf-8"
    )
    pause = manager.index("VideoAttemptTracker.pauseSession(sessionId)")
    stop = manager.index("ExecutionCoordinator.stopCurrentCommand(sessionId)", pause)
    resume = manager.index("VideoAttemptTracker.resumeSession(sessionId)", stop)
    assert pause < stop < resume


def test_stop_invalidates_registered_images_and_surfaces_possible_charge() -> None:
    body = method_source(
        "com/openminis/app/ui/chat/ChatViewModel.kt",
        "    fun cancelStream() {",
        "    /**\n",
    )
    assert "listOf(activeSessionId, sessionId) else listOf(activeSessionId)" in body
    pause = body.index("ImageAttemptTracker.pauseSession(sid)")
    cancel = body.index("nmTrackedLocalRuns.stop(job) { job.cancel() }", pause)
    stop = body.index("ExecutionCoordinator.stopCurrentCommand(sid)", cancel)
    resume = body.index("ImageAttemptTracker.resumeSession(it)", stop)
    assert pause < cancel < stop < resume
    assert "stoppedImageMayHaveSubmitted" in body
    assert "R.string.nm_image_request_failed" in body
    manager = (ANDROID / "com/openminis/app/offload/OffloadPermissionManager.kt").read_text(
        encoding="utf-8"
    )
    pause = manager.index("ImageAttemptTracker.pauseSession(sessionId)")
    pause_jobs = manager.index("ChatSessionJobs.pauseSession(sessionId)", pause)
    stop = manager.index("ExecutionCoordinator.stopCurrentCommand(sessionId)", pause_jobs)
    resume_jobs = manager.index("ChatSessionJobs.resumeSession(sessionId)", stop)
    resume = manager.index("ImageAttemptTracker.resumeSession(sessionId)", resume_jobs)
    assert pause < pause_jobs < stop < resume_jobs < resume


def test_stop_retires_managed_model_use_and_shows_possible_charge() -> None:
    body = method_source(
        "com/openminis/app/ui/chat/ChatViewModel.kt",
        "    fun cancelStream() {",
        "    /**\n",
    )
    pause = body.index("ModelUseAttemptTracker.pauseSession(sid)")
    cancel = body.index("nmTrackedLocalRuns.stop(job) { job.cancel() }", pause)
    stop = body.index("ExecutionCoordinator.stopCurrentCommand(sid)", cancel)
    resume = body.index("ModelUseAttemptTracker.resumeSession(it)", stop)
    assert pause < cancel < stop < resume
    assert "if (stoppedModelUseMayHaveSubmitted)" in body
    assert "R.string.nm_model_use_stop_warning" in body
    manager = (ANDROID / "com/openminis/app/offload/OffloadPermissionManager.kt").read_text(
        encoding="utf-8"
    )
    pause = manager.index("ModelUseAttemptTracker.pauseSession(sessionId)")
    stop = manager.index("ExecutionCoordinator.stopCurrentCommand(sessionId)", pause)
    resume = manager.index("ModelUseAttemptTracker.resumeSession(sessionId)", stop)
    assert pause < stop < resume


def test_session_cleanup_also_retires_registered_chat_slot_waiters() -> None:
    source = (ANDROID / "com/openminis/app/service/SessionConcurrencyManager.kt").read_text(
        encoding="utf-8"
    )
    body = source.split("    suspend fun acquireSlot(sessionId: String):", 1)[1].split(
        "    fun diagSnapshot()", 1
    )[0]
    assert body.index("ChatSessionJobs.bind(sessionId, job)") < body.index(
        "slots.acquire(sessionId)"
    )
    assert "ChatSessionJobs.unbind(sessionId, it)" in body
    assert 'APPLICATION_ID == "ai.tama.assistant.tamav2test"' in body


@pytest.mark.parametrize(
    ("start", "end", "primary_setup_step"),
    [
        (
            "    fun rerunFromToolBlock(",
            "    fun retryFromMessage(",
            "chatRepository.loadMessages(sid)",
        ),
        (
            "    fun retryFromMessage(",
            "    private suspend fun resolveDeleteCutoffSortOrder(",
            "chatRepository.loadMessages(sid)",
        ),
        (
            "    fun retryLast() {",
            "    /**\n     * Unwrap exceptions",
            "chatRepository.loadMessages(sid)",
        ),
        (
            "    private fun sendMessage(\n",
            "    private fun setInlineError(error: Throwable)",
            "val activeSessionId = ensureSession(promotionOwner)",
        ),
        (
            "    private fun nmStartImageShortcut(",
            "    private fun nmContinueShortcutQueue()",
            "sessionLoaded.first { it }",
        ),
        (
            "    fun resume() {",
            "    override fun onCleared() {",
            "val nmPromptInput = nmCapturePromptInput()",
        ),
        (
            "    private fun resumeQueueAfterCancel() {",
            "    private fun handleUserCancelledCleanup()",
            "kotlinx.coroutines.delay(200)",
        ),
    ],
)
def test_chat_main_stream_reserves_before_its_primary_setup_step(
    start: str, end: str, primary_setup_step: str
) -> None:
    body = method_source("com/openminis/app/ui/chat/ChatViewModel.kt", start, end)
    assert body.index("ChatSetupLaunch.launch(") < body.index(primary_setup_step)
    assert "ai.tama.assistant.tamav2test" in body


@pytest.mark.parametrize(
    ("start", "end", "promote"),
    [
        (
            "    private fun sendMessage(\n",
            "    private fun setInlineError(error: Throwable)",
            "val activeSessionId = ensureSession(promotionOwner)",
        ),
        (
            "    private fun nmStartImageShortcut(",
            "    private fun nmContinueShortcutQueue()",
            "val sid = ensureSession(promotionOwner)",
        ),
    ],
)
def test_draft_send_uses_a_cleanup_witness_before_slot(start: str, end: str, promote: str) -> None:
    body = method_source("com/openminis/app/ui/chat/ChatViewModel.kt", start, end)
    assert body.index("watchPromotion = true") < body.index(promote)
    assert body.index(promote) < body.index("SessionConcurrencyManager.acquireSlot(")
    assert '"ai.tama.assistant.tamav2test"' in body


def test_draft_id_is_bound_before_the_session_row_is_inserted() -> None:
    path = "com/openminis/app/ui/chat/ChatViewModel.kt"
    bind = method_source(
        path, "    private fun nmBindPromotedSession(", "    private suspend fun ensureSession("
    )
    assert "ChatSessionJobs.bindPromoted(id, owner)" in bind
    ensure = method_source(
        path, "    private suspend fun ensureSession(", "    /**\n     * Move every per-session"
    )
    assert "nmBindPromotedSession(existingId, promotionOwner)" in ensure
    assert "beforeInsert = if (promotionOwner == null) null else" in ensure
    assert "nmBindPromotedSession(id, promotionOwner)" in ensure
    repository = method_source(
        "com/openminis/app/data/repository/ChatRepository.kt",
        "    suspend fun createSession(",
        "    suspend fun getSession(",
    )
    assert repository.index("beforeInsert?.invoke(session.id)") < repository.index(
        "dao.insertSession(session)"
    )


def test_retry_and_resume_auxiliary_writes_are_owned_by_parallel_setup() -> None:
    path = "com/openminis/app/ui/chat/ChatViewModel.kt"
    retry = method_source(path, "    fun retryLast() {", "    /**\n     * Unwrap exceptions")
    assert "if (!parallelRetry) clearPersistedLastAssistantError()" in retry
    retry_launch = retry.index("ChatSetupLaunch.launch(")
    assert retry.index("nmClearPersistedLastAssistantError(sid)", retry_launch) > retry_launch

    resume = method_source(path, "    fun resume() {", "    override fun onCleared() {")
    assert "if (!parallelResume) clearPersistedLastAssistantError()" in resume
    normal_reminder = resume.split("if (!parallelResume) {", 1)[1].split("val resumeSessionId", 1)[
        0
    ]
    assert "viewModelScope.launch(Dispatchers.IO)" in normal_reminder
    resume_launch = resume.index("ChatSetupLaunch.launch(")
    assert (
        resume.index("nmClearPersistedLastAssistantError(resumeSessionId)", resume_launch)
        > resume_launch
    )
    reminder_write = resume.index("chatRepository.appendMessage(resumeSessionId", resume_launch)
    assert resume.index("agentHistory.add(", reminder_write) > reminder_write


def test_cancelled_setup_only_restores_its_own_composer() -> None:
    path = "com/openminis/app/ui/chat/ChatViewModel.kt"
    send = method_source(
        path, "    private fun sendMessage(\n", "    private fun setInlineError(error: Throwable)"
    )
    never_started = send.split("onNeverStarted =", 1)[1].split("watchPromotion = true", 1)[0]
    assert "streamJob === owner" in never_started
    assert "headless == null && _inputText.value.isEmpty()" in never_started
    cancelled = send.rsplit("} catch (e: CancellationException) {", 1)[-1].split("} finally {", 1)[
        0
    ]
    assert "nmMayFinishSetup(coroutineContext[Job])" in cancelled
    assert "headless == null || normalPackage" in cancelled
    assert "_messages.value.any { it.id == editingId }" in cancelled
    image = method_source(
        path, "    private fun nmStartImageShortcut(", "    private fun nmContinueShortcutQueue()"
    )
    assert image.count("!userRowSaved && nmMayFinishSetup(ownJob)") == 2


def test_parallel_send_checks_cancellation_between_refresh_edit_and_stream() -> None:
    body = method_source(
        "com/openminis/app/ui/chat/ChatViewModel.kt",
        "    private fun sendMessage(\n",
        "    private fun setInlineError(error: Throwable)",
    )
    refresh = body.split("val refreshed = nmRefreshSelectedOAuth(provider)", 1)[1].split(
        "if (refreshed == null)", 1
    )[0]
    assert "coroutineContext.ensureActive()" in refresh
    edit = body.split("if (editingId != null) {\n                truncateBeforeEdit(editingId)", 1)[
        1
    ].split("val prepared = prepareUserAttachments", 1)[0]
    assert "if (promotionOwner != null) coroutineContext.ensureActive()" in edit
    launch = body.split("val nmPromptInput = nmCapturePromptInput()", 1)[1].split(
        "nmLaunchStream(this)", 1
    )[0]
    assert "if (promotionOwner != null) coroutineContext.ensureActive()" in launch


def test_parallel_headless_receipt_is_tied_to_the_terminal_loop_write() -> None:
    path = "com/openminis/app/ui/chat/ChatViewModel.kt"
    source = (ANDROID / path).read_text(encoding="utf-8")
    send = method_source(
        path, "    private fun sendMessage(\n", "    private fun setInlineError(error: Throwable)"
    )
    assert (
        "if (parallelHeadless) headlessTerminal = io.github.nanomuse.hub.HeadlessTurn.TerminalAssistant(id, reason)"
        in send
    )
    assert (
        "headless.complete(chatRepository.dao.loadMessages(activeSessionId), headlessTerminal)"
        in send
    )
    final_branch = source.split("            // If no tool calls, we're done", 1)[1].split(
        '            AppLogger.info(TAG_STREAM, "runAgentLoop turn=$turn dispatching', 1
    )[0]
    assert final_branch.index("if (toolCalls.isEmpty())") < final_branch.index(
        "val terminalAssistantId = persistAssistantTurn("
    )
    assert final_branch.index(
        "val terminalAssistantId = persistAssistantTurn("
    ) < final_branch.index("onTerminalAssistantSaved(terminalAssistantId, turnFinishReason)")
    assert "continue\n" in final_branch[: final_branch.index("onTerminalAssistantSaved(")]
    runner = (ANDROID / "com/openminis/app/debug/HeadlessChatRunner.kt").read_text(encoding="utf-8")
    assert "strict = strictPersistent" in runner


def test_headless_auto_compaction_uses_the_outgoing_request_guard() -> None:
    path = "com/openminis/app/ui/chat/ChatViewModel.kt"
    admission = method_source(
        path,
        "    internal fun nmSendHeadless(",
        "    // nanoMuse: an expired wait cannot cancel a later local or remote turn.",
    )
    assert "PreSendContextAction.ASK_USER" in admission
    assert "sendMessage(text, skipContextCheck = true, headless = receipt)" in admission
    source = (ANDROID / path).read_text(encoding="utf-8")
    loop = source.split("    private suspend fun runAgentLoop(", 1)[1].split(
        "    private suspend fun persistAssistantTurn(", 1
    )[0]
    first = loop.index("prepareContext()")
    outgoing = loop.index("inLoopContextCheck(contextMaintenance, requestBudget,", first)
    assert first < outgoing
    assert loop.count("allowAutomaticCompaction = nmAutoTurn == null") >= 2


def test_rerun_stream_remains_a_child_of_registered_setup() -> None:
    body = method_source(
        "com/openminis/app/ui/chat/ChatViewModel.kt",
        "    private suspend fun runRerunStreamTail(",
        "    fun editMessage(",
    )
    assert "CoroutineScope(kotlin.coroutines.coroutineContext) else viewModelScope" in body
    assert "nmLaunchStream(streamScope)" in body


def test_releasing_view_model_clears_both_original_and_persisted_session() -> None:
    body = method_source(
        "com/openminis/app/ui/chat/ChatViewModel.kt",
        "    override fun onCleared() {",
        "    /**\n     * T-android-new-chat-empty-residue",
    )
    assert "OffloadPermissionManager.clearSessionGrants(activeSessionId)" in body
    assert "OffloadPermissionManager.clearSessionGrants(sessionId)" in body
