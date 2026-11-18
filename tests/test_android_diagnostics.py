"""Privacy boundaries and Android entry points for the local diagnostic."""

import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / "android/src/android/app/src/main"
JAVA = APP / "java"
RES = APP / "res"


def text(relative: str) -> str:
    return (JAVA / relative).read_text(encoding="utf-8")


def test_report_has_no_data_access_or_open_ended_text_fields() -> None:
    report = text("ai/tama/diagnostics/DiagnosticReport.kt")
    capture = text("ai/tama/diagnostics/DiagnosticCapture.kt")
    for forbidden in (
        "ChatRepository",
        "ProviderRepository",
        "SharedPreferences",
        "ClipboardManager",
        "java.io.",
        "java.net.",
        "AppLogger",
        "Log.",
        "originalMessage",
        "modelId",
    ):
        assert forbidden not in report
        assert forbidden not in capture
    snapshot = report.split("    data class Snapshot(", 1)[1].split("\n    )", 1)[0]
    assert ": String" not in snapshot
    assert "val lastFailure: Failure?" in snapshot
    assert "BuildConfig.APPLICATION_ID" in capture
    assert "BuildConfig.VERSION_NAME" in capture
    assert "Build.SUPPORTED_ABIS?.toList().orEmpty()" in capture


def test_slash_command_opens_preview_before_any_send_path() -> None:
    vm = text("com/openminis/app/ui/chat/ChatViewModel.kt")
    screen = text("com/openminis/app/ui/chat/ChatScreen.kt")
    slash = text("com/openminis/app/ui/chat/ChatViewModelSlashExt.kt")
    assert 'id = "diagnostico"' in vm
    assert '"diagnostico" -> _nmDiagnosticOpen.value = true' in vm
    assert "DiagnosticCommand.matches(text)" in vm
    assert 'executeSlashCommand(availableSlashCommands.first { it.id == "diagnostico" })' in vm
    assert (
        '"diagnostico" -> cmd.copy(subtitle = context.getString(R.string.nm_diagnostic_slash_subtitle))'
        in slash
    )
    assert screen.count("if (viewModel.tryExecuteInputAsSlashCommand(") >= 2
    assert "ai.tama.diagnostics.DiagnosticDialog(" in screen
    assert "DiagnosticCapture.chat(selected, last, isStreaming, compacting, failure)" in screen
    assert "DiagnosticCapture.failure(reach?.kind, reach?.httpStatus)" in vm
    assert "_nmDiagnosticFailure.value = null" in vm.split("fun clearChat()", 1)[1]


def test_preview_only_copies_a_closed_schema_after_user_action() -> None:
    dialog = text("ai/tama/diagnostics/DiagnosticDialog.kt")
    about = text("com/openminis/app/ui/settings/AboutScreen.kt")
    assert "val report = DiagnosticReport.format(snapshot)" in dialog
    assert 'clipboard.setPlainText("TAMA diagnostic", report)' in dialog
    assert dialog.count("setPlainText(") == 1
    assert "onClick = {" in dialog.split("clipboard.setPlainText", 1)[0]
    for forbidden in ("AppLogger", "Log.", "java.net.", "ChatRepository", "ProviderRepository"):
        assert forbidden not in dialog
    assert "onClick = { nmDiagnosticOpen = true }" in about
    assert "DiagnosticCapture.settings()" in about


def test_diagnostic_strings_exist_in_every_locale() -> None:
    names = {
        "nm_diagnostic_title",
        "nm_diagnostic_subtitle",
        "nm_diagnostic_slash_subtitle",
        "nm_diagnostic_privacy",
        "nm_diagnostic_copy",
        "nm_diagnostic_copied",
        "nm_diagnostic_copy_failed",
    }
    files = sorted(RES.glob("values*/nm_strings.xml"))
    assert len(files) >= 17
    for path in files:
        root = ET.parse(path).getroot()
        found = {node.get("name") for node in root.findall("string")}
        assert names <= found, path
    pt = ET.parse(RES / "values-pt-rBR/nm_strings.xml").getroot()
    assert pt.find("./string[@name='nm_diagnostic_copy']").text == "Copiar diagnóstico"
    privacy = pt.find("./string[@name='nm_diagnostic_privacy']").text
    assert "Não inclui conversas, chaves" in privacy


def test_normal_identity_and_variant_are_separate_at_source() -> None:
    gradle = (APP.parent.parent / "build.gradle.kts").read_text(encoding="utf-8")
    manifest = ET.parse(APP / "AndroidManifest.xml").getroot()
    ns = "{http://schemas.android.com/apk/res/android}"
    app = manifest.find("application")
    assert 'applicationId = "ai.tama.assistant"' in gradle
    assert 'applicationIdSuffix = ".tamav2test"' in gradle
    assert app.get(ns + "label") == "@string/app_name"
    assert app.get(ns + "icon") == "@mipmap/ic_launcher"
    strings = ET.parse(RES / "values/strings.xml").getroot()
    assert strings.find("./string[@name='app_name']").text == "TAMA AI"
    variant = ET.parse(APP.parent / "tamaTest/AndroidManifest.xml").getroot()
    assert variant.find("application").get(ns + "label") == "Tama V2 Test"
