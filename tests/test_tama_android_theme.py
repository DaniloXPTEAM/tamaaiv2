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
    cloud = (
        ROOT / "android/src/android/app/src/main/java/io/github/nanomuse/cloud/NanoMuseCloud.kt"
    ).read_text()
    assert 'val LEGACY_LABELS = listOf("nanoMuse Cloud")' in cloud


def test_home_accent_and_white_on_actions_have_readable_contrast() -> None:
    tones = (
        ROOT / "android/src/android/app/src/main/java/io/github/nanomuse/ui/home/MuseTones.kt"
    ).read_text()
    action = re.search(r"val action: Color = Color\(0xFF([0-9A-F]{6})\)", tones)
    dark_accent = re.search(
        r"val accent: Color\s+@Composable @ReadOnlyComposable get\(\) = if \(isDark\) Color\(0xFF([0-9A-F]{6})\) else action",
        tones,
    )
    assert action and dark_accent

    def luminance(rgb: str) -> float:
        values = [int(rgb[i : i + 2], 16) / 255 for i in (0, 2, 4)]
        linear = [
            value / 12.92 if value <= 0.04045 else ((value + 0.055) / 1.055) ** 2.4
            for value in values
        ]
        return sum(
            value * weight for value, weight in zip(linear, (0.2126, 0.7152, 0.0722), strict=True)
        )

    def contrast(first: str, second: str) -> float:
        a, b = sorted((luminance(first), luminance(second)))
        return (b + 0.05) / (a + 0.05)

    # Surface, canvas and filled cards, not just the body background.
    for bg in ("FFF8E9", "FFFCF5", "F7E7C9"):
        assert contrast(action.group(1), bg) >= 4.5
    for bg in ("19130F", "30251D", "49372A"):
        assert contrast(dark_accent.group(1), bg) >= 4.5
    assert contrast(action.group(1), "FFFFFF") >= 4.5


def test_filled_buttons_use_white_and_surface_text_uses_adaptive_accent() -> None:
    root = ROOT / "android/src/android/app/src/main/java"
    for relative in (
        "io/github/nanomuse/ui/reach/ComputersScreen.kt",
        "io/github/nanomuse/ui/connectors/ConnectorsScreen.kt",
    ):
        source = (root / relative).read_text()
        assert (
            "buttonColors(containerColor = MuseTones.action, contentColor = Color.White)" in source
        )
    for relative in (
        "com/openminis/app/ui/settings/SettingsScreen.kt",
        "io/github/nanomuse/ui/models/ModelsScreen.kt",
        "io/github/nanomuse/ui/cloud/DataControlsScreen.kt",
    ):
        source = (root / relative).read_text()
        assert "MuseTones.accent" in source
        assert "color = MuseTones.action," not in source


def test_chinese_rebrand_terminology_does_not_change_our_resources() -> None:
    rebrand = runpy.run_path(str(ROOT / "scripts/rebrand.py"), run_name="rebrand_test")
    for folder, replacements in rebrand["TERMS"].items():
        resource = ROOT / "android/src/android/app/src/main/res" / folder / "nm_strings.xml"
        source = resource.read_text()
        for old, _ in replacements:
            assert old not in source, (folder, old)
