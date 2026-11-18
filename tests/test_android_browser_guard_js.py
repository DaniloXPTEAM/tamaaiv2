"""Exercise the Android browser guard's descriptor with a synthetic DOM, not a real page."""

from __future__ import annotations

import json
import re
import shutil
import subprocess
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[1]
GUARD = ROOT / "android/src/android/app/src/main/java/io/github/nanomuse/guard/BrowserGuard.kt"
NODE = shutil.which("node")


@pytest.mark.skipif(NODE is None, reason="Node is needed to exercise the JavaScript descriptor")
def test_parent_button_aria_is_captured_when_a_child_is_clicked() -> None:
    kotlin = GUARD.read_text(encoding="utf-8")
    descriptor = re.search(
        r"fun describeJs\(.*?return \"\"\"\s*(\(function\(\)\{.*?\}\)\(\))\s*\"\"\"\.trimIndent\(\)",
        kotlin,
        re.DOTALL,
    )
    assert descriptor is not None
    body = descriptor.group(1)
    assert body.count("$pick") == 1
    body = body.replace("$pick", 'document.querySelector("#target")')
    harness = r"""
const button = {
  innerText: "Continuar",
  getAttribute: key => key === "aria-label" ? "Confirmar pagamento" : null,
  querySelector: () => null
};
const child = {
  tagName: "SPAN", id: "", getAttribute: key => key === "aria-label" ? "Avançar" : null,
  closest: selector => selector.startsWith("input,") ? null : button
};
global.document = {querySelector: () => child};
global.location = {href: "https://shop.example/x", host: "shop.example"};
const described = eval(DESCRIPTOR);
process.stdout.write(described);
""".replace("DESCRIPTOR", json.dumps(body))
    result = subprocess.run(
        [NODE, "-e", harness], check=True, capture_output=True, text=True, timeout=5
    )
    target = json.loads(result.stdout)
    assert target["found"] is True
    assert target["text"] == "Continuar"
    assert target["aria"] == "Avançar"
    assert target["buttonAria"] == "Confirmar pagamento"


def test_browser_manager_blocks_refusals_before_dispatch() -> None:
    source = (
        ROOT
        / "android/src/android/app/src/main/java/com/openminis/app/browser/BrowserUseManager.kt"
    ).read_text(encoding="utf-8")
    guard = source.split("private suspend fun nmGuard(", 1)[1].split(
        "private suspend fun prevUrlOrNull(", 1
    )[0]
    assert "guard.inspect(input.action == BrowserAction.TYPE)" in guard
    assert "BrowserGuard.Verdict.Refuse" in guard
    assert "BrowserActionResult.error(verdict.text)" in guard
    assert "?: return NmGate()" not in guard
    dispatch = source.split("suspend fun execute(input: BrowserActionInput)", 1)[1]
    assert dispatch.index("nmGate.blocked?.let { return it }") < dispatch.index(
        "when (input.action)"
    )
