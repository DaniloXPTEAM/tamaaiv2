"""The TAMA palette and generator must preserve existing Android identity data."""

from __future__ import annotations

import re
import runpy
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DELTA = runpy.run_path(str(ROOT / "scripts/tama_delta.py"), run_name="tama_test")
JAVA = ROOT / "android/src/android/app/src/main/java/com/openminis/app"


def test_material_and_chat_palettes_match_the_rebrand_delta() -> None:
    theme = (JAVA / "ui/theme/Theme.kt").read_text()
    chat = (JAVA / "ui/theme/ChatColors.kt").read_text()
    for name, rgb in DELTA["TAMA_THEME_COLOURS"].items():
        assert f"private val {name} = Color(0xFF{rgb})" in theme
    for palette, colours in DELTA["TAMA_CHAT_COLOURS"].items():
        match = re.search(rf"val {palette} = ChatPalette\(\n(.*?)\n\)", chat, re.S)
        assert match, palette
        for name, rgb in colours.items():
            assert f"    {name} = Color(0xFF{rgb})," in match.group(1), (palette, name)


def test_rebrand_does_not_change_the_legacy_cloud_group_name() -> None:
    assert DELTA["brand_in_kotlin"]('val LEGACY_LABELS = listOf("nanoMuse Cloud")') == (
        'val LEGACY_LABELS = listOf("nanoMuse Cloud")'
    )
    assert DELTA["brand_in_kotlin"]('val title = "nanoMuse"') == 'val title = "TAMA AI"'
    cloud = (ROOT / "android/src/android/app/src/main/java/io/github/nanomuse/cloud/NanoMuseCloud.kt").read_text()
    assert 'val LEGACY_LABELS = listOf("nanoMuse Cloud")' in cloud
