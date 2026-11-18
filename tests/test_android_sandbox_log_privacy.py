"""Targeted shell log wiring checks; not a complete log or process privacy audit."""

from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "android/src/android/app/src/main/java"


def test_shell_commands_and_failures_use_parallel_safe_messages() -> None:
    coordinator = (JAVA / "com/openminis/app/sandbox/ExecutionCoordinator.kt").read_text(
        encoding="utf-8"
    )
    executor = (JAVA / "com/openminis/app/sandbox/ShellExecutor.kt").read_text(encoding="utf-8")
    assert "SandboxLogPrivacy.dispatch(nmParallel, sessionId, command)" in coordinator
    assert 'configuredEnv + ("MINIS_CHAT_SESSION_ID" to sessionId)' in coordinator
    assert "shell.applyEnvironment(envVars, previousKeys = previousKeys)" in coordinator
    assert "SandboxLogPrivacy.start(nmParallel, command)" in executor
    assert "SandboxLogPrivacy.timeout(nmParallel, timeout, command)" in executor
    assert "SandboxLogPrivacy.failure(nmParallel, command)" in executor
    assert "if (nmParallel) Log.e(TAG, message) else Log.e(TAG, message, e)" in executor
    assert "SandboxLogPrivacy.failureOutput(nmParallel, e.message)" in executor


def test_parallel_shell_executor_consults_retry_safety_before_relaunch() -> None:
    executor = (JAVA / "com/openminis/app/sandbox/ShellExecutor.kt").read_text(encoding="utf-8")
    gate = executor.index("ShellRetrySafety.shouldRetry(")
    retry = executor.index("val retried = runOnce(")
    assert gate < retry
    assert "parallel = nmParallel" in executor[gate:retry]
    assert "return@withContext first" in executor[gate:retry]
    assert (JAVA.parent.parent / "test/java/ai/tama/sandbox/ShellRetrySafetyTest.kt").is_file()


def test_persistent_shell_drains_but_does_not_log_output_or_override_session() -> None:
    shell = (JAVA / "com/openminis/app/sandbox/PersistentShell.kt").read_text(encoding="utf-8")
    assert 'for (line in br.lineSequence()) if (!nmParallel) Log.d("PRootStderr", line)' in shell
    assert "SandboxLogPrivacy.spawnFailure(nmParallel" in shell
    assert "SandboxLogPrivacy.readerFailure(nmParallel" in shell
    assert "SandboxLogPrivacy.writeFailure(nmParallel, e.message)" in shell
    assert shell.count("SandboxLogPrivacy.captured(nmParallel") >= 3
    custom = shell.index("for ((key, value) in PRootKernel.customEnvironment)")
    bound = shell.index('if (nmParallel) env["MINIS_CHAT_SESSION_ID"] = sessionId', custom)
    assert custom < bound < shell.index("val p = processBuilder.start()", bound)
    assert (JAVA.parent.parent / "test/java/ai/tama/net/SandboxLogPrivacyTest.kt").is_file()
