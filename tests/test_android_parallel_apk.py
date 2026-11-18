"""The phone-test variant must not take the installed app's identity or authorities."""

from __future__ import annotations

import importlib.util
import subprocess
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "scripts/android/verify-parallel-apk.py"
SPEC = importlib.util.spec_from_file_location("verify_parallel_apk", SCRIPT)
assert SPEC and SPEC.loader
verify = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(verify)


def fixture() -> tuple[str, str, str, str]:
    # AAPT may omit launchable-activity for an activity-alias, even with MAIN/LAUNCHER.
    badging = "\n".join(
        [
            "package: name='ai.tama.assistant.tamav2test' versionCode='530'",
            "application-label:'Tama V2 Test'",
            "application-debuggable",
            "native-code: 'arm64-v8a'",
        ]
    )
    normal = "package: name='ai.tama.assistant' versionCode='530'"
    aliases = []
    for name in sorted(verify.ALIASES):
        enabled = "0xffffffff" if name == "MainActivityIconAuto" else "0x0"
        aliases.append(
            f"      E: activity-alias (line=1)\n"
            f'        A: android:name(0x01)="com.openminis.app.{name}"\n'
            '        A: android:label(0x02)="Tama V2 Test"\n'
            f"        A: android:enabled(0x03)=(type 0x12){enabled}\n"
            "        A: android:exported(0x04)=(type 0x12)0xffffffff\n"
            '        A: android:targetActivity(0x05)="com.openminis.app.MainActivity"\n'
            "        E: intent-filter (line=2)\n"
            "          E: action (line=3)\n"
            '            A: android:name(0x01)="android.intent.action.MAIN"\n'
            "          E: category (line=4)\n"
            '            A: android:name(0x01)="android.intent.category.LAUNCHER"'
        )
    test_tree = (
        "  E: manifest (line=1)\n"
        "    E: permission (line=2)\n"
        '      A: android:name(0x01)="ai.tama.assistant.tamav2test.permission.ALARM"\n'
        "      A: android:protectionLevel(0x06)=(type 0x11)0x2\n"
        "    E: application (line=3)\n"
        "      E: provider (line=4)\n"
        '        A: android:authorities(0x01)="ai.tama.assistant.tamav2test.fileprovider"\n'
        "      E: provider (line=5)\n"
        '        A: android:authorities(0x01)="ai.tama.assistant.tamav2test.shizuku"\n'
        + "\n".join(aliases)
    )
    normal_tree = (
        "  E: manifest (line=1)\n"
        "    E: permission (line=2)\n"
        '      A: android:name(0x01)="ai.tama.assistant.permission.ALARM"\n'
        "      A: android:protectionLevel(0x06)=(type 0x11)0x2\n"
        "    E: application (line=3)\n"
        "      E: provider (line=4)\n"
        '        A: android:authorities(0x01)="ai.tama.assistant.fileprovider"'
    )
    return badging, normal, test_tree, normal_tree


def test_accepts_only_disjoint_parallel_identity() -> None:
    verify.validate(*fixture())


def test_failed_tool_exposes_error_without_hiding_failure(monkeypatch: pytest.MonkeyPatch) -> None:
    def fail(*args, **kwargs):
        raise subprocess.CalledProcessError(2, args[0], stderr="bad APK\nmissing manifest")

    monkeypatch.setattr(verify.subprocess, "run", fail)
    with pytest.raises(ValueError, match="aapt failed with exit 2: bad APK missing manifest"):
        verify.run("/sdk/aapt", "dump")


@pytest.mark.parametrize(
    "change",
    [
        "package",
        "label",
        "abi",
        "authority",
        "unreadable_authority",
        "alias",
        "alias_disabled",
        "alias_unexported",
        "alias_target",
        "launcher_missing",
        "launcher_split_filters",
        "alarm",
        "alarm_unprotected",
        "permission_conflict",
        "debuggable",
    ],
)
def test_rejects_install_conflicts_or_wrong_variant(change: str) -> None:
    badging, normal, tree, normal_tree = fixture()
    if change == "package":
        badging = badging.replace("ai.tama.assistant.tamav2test", "ai.tama.assistant")
    elif change == "label":
        badging = badging.replace("application-label:'Tama V2 Test'", "application-label:'TAMA AI'")
    elif change == "abi":
        badging = badging.replace("arm64-v8a", "x86_64")
    elif change == "authority":
        normal_tree += (
            '\n        A: android:authorities(0x01)="ai.tama.assistant.tamav2test.fileprovider"'
        )
    elif change == "unreadable_authority":
        tree = tree.replace(
            'android:authorities(0x01)="ai.tama.assistant.tamav2test.fileprovider"',
            "android:authorities(0x01)=@0x7f000001",
        )
    elif change == "alias":
        tree = tree.replace(
            'android:label(0x02)="Tama V2 Test"', 'android:label(0x02)="TAMA AI"', 1
        )
    elif change == "alias_disabled":
        tree = tree.replace(
            "android:enabled(0x03)=(type 0x12)0xffffffff",
            "android:enabled(0x03)=(type 0x12)0x0",
            1,
        )
    elif change == "alias_unexported":
        tree = tree.replace(
            "android:exported(0x04)=(type 0x12)0xffffffff",
            "android:exported(0x04)=(type 0x12)0x0",
            1,
        )
    elif change == "alias_target":
        tree = tree.replace(
            'android:targetActivity(0x05)="com.openminis.app.MainActivity"',
            'android:targetActivity(0x05)="other.Activity"',
            1,
        )
    elif change == "launcher_missing":
        tree = tree.replace("android.intent.category.LAUNCHER", "android.intent.category.DEFAULT")
    elif change == "launcher_split_filters":
        tree = tree.replace(
            "          E: category (line=4)\n"
            '            A: android:name(0x01)="android.intent.category.LAUNCHER"',
            "        E: intent-filter (line=5)\n"
            "          E: category (line=6)\n"
            '            A: android:name(0x01)="android.intent.category.LAUNCHER"',
            1,
        )
    elif change == "alarm":
        tree = tree.replace(
            "ai.tama.assistant.tamav2test.permission.ALARM", "wrong.permission.ALARM"
        )
    elif change == "alarm_unprotected":
        tree = tree.replace(
            "android:protectionLevel(0x06)=(type 0x11)0x2",
            "android:protectionLevel(0x06)=(type 0x11)0x0",
            1,
        )
    elif change == "permission_conflict":
        normal_tree += (
            "\n    E: permission (line=5)\n"
            '      A: android:name(0x01)="ai.tama.assistant.tamav2test.permission.ALARM"'
        )
    else:
        badging = badging.replace("application-debuggable", "")
    with pytest.raises(ValueError):
        verify.validate(badging, normal, tree, normal_tree)
