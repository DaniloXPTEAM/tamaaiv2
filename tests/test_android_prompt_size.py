"""Only complete, count-only Android fixture measurements may be published."""

from __future__ import annotations

import importlib.util
import re
import xml.etree.ElementTree as ET
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location(
    "tama_prompt_size", ROOT / "scripts/android/publish-prompt-size.py"
)
assert SPEC and SPEC.loader
report = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(report)


def fixture() -> str:
    def metric(scope: str, label: str, before: int, after: int) -> str:
        return f"v1 {scope} {label} {before} {after} {report.estimate(before)} {report.estimate(after)}"

    sections = sorted(report.REQUIRED_SYSTEM - {"TOTAL", "SEPARATORS"})
    system = [metric("system", label, 10, 8) for label in sections]
    system.append(metric("system", "SEPARATORS", 4, 4))
    before, after = len(sections) * 10 + 4, len(sections) * 8 + 4
    system.append(metric("system", "TOTAL", before, after))
    tools = [
        metric("tool", "SHELL_EXECUTE", 100, 100),
        metric("tool", "BROWSER_USE", 300, 150),
        metric("tool", "ARRAY_SEPARATORS", 3, 3),
        metric("tool", "TOTAL", 403, 253),
    ]
    return (
        "\n".join(
            system
            + tools
            + [
                "v1 image READY 0",
                f"v1 request SYSTEM {after}",
                f"v1 wire SYSTEM {after}",
                "v1 wire TOOLS 253",
            ]
        )
        + "\n"
    )


def test_complete_evidence_is_published_without_user_content(tmp_path, monkeypatch, capsys) -> None:
    monkeypatch.chdir(tmp_path)
    monkeypatch.setenv("GITHUB_STEP_SUMMARY", str(tmp_path / "summary.md"))
    (tmp_path / "prompt-size.log").write_text(fixture(), encoding="utf-8")
    (tmp_path / "journey-instrumentation.log").write_text("OK (1 test)\n", encoding="utf-8")
    assert report.main() == 0
    output = capsys.readouterr().out
    assert "::notice title=Prompt sections" in output
    assert "::notice title=Prompt tools" in output
    text = (tmp_path / "prompt-size-summary.md").read_text(encoding="utf-8")
    assert "BROWSER_USE" in text
    assert "300 | 150" in text
    assert "UTF-16" in text
    assert "Modelo de imagem resolvido localmente: não" in text
    assert (tmp_path / "summary.md").read_text(encoding="utf-8") == text


@pytest.mark.parametrize("log", ["FAILURES!!!\n", "INSTRUMENTATION_STATUS_CODE: -4\nOK (1 test)\n"])
def test_valid_counts_from_an_incomplete_journey_are_labelled_as_partial(
    tmp_path, monkeypatch, log
) -> None:
    monkeypatch.chdir(tmp_path)
    (tmp_path / "prompt-size.log").write_text(fixture(), encoding="utf-8")
    (tmp_path / "journey-instrumentation.log").write_text(log, encoding="utf-8")
    (tmp_path / "journey-system-anr-recovery.txt").write_text("recovered\n", encoding="utf-8")
    assert report.main() == 0
    text = (tmp_path / "prompt-size-summary.md").read_text(encoding="utf-8")
    assert "Jornada instrumentada: não concluída ou não comprovada" in text
    assert "o smoke permanece em falha" in text
    assert "não substitui uma jornada completa" in text


def test_unknown_or_private_lines_are_rejected_without_being_repeated(
    tmp_path, monkeypatch, capsys
) -> None:
    monkeypatch.chdir(tmp_path)
    private = "chave-privada-NAO-PUBLICAR"
    (tmp_path / "prompt-size.log").write_text(fixture() + private + "\n", encoding="utf-8")
    (tmp_path / "journey-instrumentation.log").write_text("OK (1 test)\n", encoding="utf-8")
    assert report.main() == 1
    output = capsys.readouterr().out
    assert "::warning title=Prompt baseline" in output
    assert private not in output
    assert not (tmp_path / "prompt-size-summary.md").exists()


@pytest.mark.parametrize(
    "change",
    [
        lambda text: text.replace("v1 wire TOOLS 253", "v1 wire TOOLS 254"),
        lambda text: text.replace(
            "v1 tool BROWSER_USE 300 150 82 41", "v1 tool BROWSER_USE 300 150 81 41"
        ),
        lambda text: text.replace("v1 tool TOTAL 403 253 109 69", "v1 tool TOTAL 404 253 110 69"),
        lambda text: text.replace("v1 tool SHELL_EXECUTE", "v1 tool PRIVATE_NAME"),
        lambda text: text.replace("v1 system IDENTITY", "v1 system BASE_UNCLASSIFIED"),
        lambda text: text.replace("v1 image READY 0\n", ""),
    ],
)
def test_mismatched_or_incomplete_evidence_is_not_accepted(change) -> None:
    with pytest.raises(ValueError):
        report.validate(change(fixture()))


def test_each_locale_has_a_label_for_every_fixed_system_section() -> None:
    source = (
        ROOT / "android/src/android/app/src/main/java/io/github/nanomuse/chat/PromptSize.kt"
    ).read_text(encoding="utf-8")
    section = re.search(r"enum class Section \{(.*?)\n    }", source, re.DOTALL)
    assert section
    count = len([label for label in section.group(1).split(",") if label.strip()])
    files = sorted((ROOT / "android/src/android/app/src/main/res").glob("values*/nm_strings.xml"))
    assert len(files) >= 17
    for file in files:
        node = ET.parse(file).find("./string[@name='nm_prompt_size_section_names']")
        assert node is not None and node.text
        names = node.text.split("|")
        assert len(names) == count, file
        assert all(name.strip() for name in names), file


def test_missing_metrics_do_not_mask_an_earlier_journey_failure(
    tmp_path, monkeypatch, capsys
) -> None:
    monkeypatch.chdir(tmp_path)
    (tmp_path / "journey-instrumentation.log").write_text("FAILURES!!!\n", encoding="utf-8")
    assert report.main() == 0
    assert "::warning title=Prompt baseline" in capsys.readouterr().out
