"""Socket log and exception wiring checks, not a general privacy guarantee."""

from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "android/src/android/app/src/main/java"


def test_parallel_offload_socket_uses_safe_messages_without_exception_traces() -> None:
    server = (JAVA / "com/openminis/app/sandbox/NativeOffload.kt").read_text(encoding="utf-8")
    assert 'BuildConfig.APPLICATION_ID == "ai.tama.assistant.tamav2test"' in server
    assert "NativeOffloadPrivacy.incoming(nmParallel, pid, name, argc, argv, cwd, envc)" in server
    assert "NativeOffloadPrivacy.missingLog(nmParallel, name, handlers.keys)" in server
    assert "NativeOffloadPrivacy.missingOutput(nmParallel, name)" in server
    assert "NativeOffloadPrivacy.failureLog(nmParallel, name, e.message)" in server
    assert "NativeOffloadPrivacy.failureOutput(nmParallel, e.message)" in server
    assert "if (nmParallel) Log.w(TAG, message) else Log.w(TAG, message, e)" in server
    assert 'if (nmParallel) Log.w(TAG, "offload worker failed")' in server
    assert "NativeOffloadPrivacy.reply(" in server


def test_parallel_socket_rejects_unsafe_session_components_before_handler_dispatch() -> None:
    server = (JAVA / "com/openminis/app/sandbox/NativeOffload.kt").read_text(encoding="utf-8")
    check = server.index("NativeOffloadSession.valid(nmParallel, sessionId)")
    dispatch = server.index("handler.handle(NativeOffloadRequest(")
    assert check < dispatch
    assert (
        'NativeOffloadResult(exitCode = 2, output = "native_offload: invalid chat session\\n")'
        in server
    )
    assert "sessionId = sessionId" in server
    assert (JAVA.parent.parent / "test/java/ai/tama/net/NativeOffloadSessionTest.kt").is_file()


def test_parallel_offload_diagnostics_do_not_interpolate_private_values() -> None:
    policy = (JAVA / "ai/tama/net/NativeOffloadPrivacy.kt").read_text(encoding="utf-8")
    for fixed in (
        'if (parallel) "offload request received"',
        'if (parallel) "offload handler not registered"',
        'if (parallel) "offload handler failed"',
        'if (parallel) "offload request completed"',
    ):
        assert fixed in policy
    assert (JAVA.parent.parent / "test/java/ai/tama/net/NativeOffloadPrivacyTest.kt").is_file()
