"""Normal APK identity verification rejects altered and incomplete badging."""

import importlib.util
import sys
from dataclasses import replace
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location(
    "tama_normal_builds", ROOT / "scripts/android/verify-normal-builds.py"
)
assert SPEC and SPEC.loader
verify = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = verify
SPEC.loader.exec_module(verify)

BADGING = """\
package: name='ai.tama.assistant' versionCode='530' versionName='1.0.9-tama.1' platformBuildVersionName='16'
sdkVersion:'26'
targetSdkVersion:'35'
application-label:'TAMA AI'
application: label='TAMA AI' icon='res/mipmap-anydpi-v26/ic_launcher.xml'
native-code: 'arm64-v8a'
"""


def test_matching_debug_and_release_are_verified_separately() -> None:
    debug = verify.parse_badging(BADGING + "application-debuggable\n", "mipmap/ic_launcher")
    release = verify.parse_badging(BADGING, "mipmap/ic_launcher")
    verify.validate(debug, release)
    assert debug.debuggable and not release.debuggable
    assert debug.package == release.package == "ai.tama.assistant"
    assert debug.label == release.label == "TAMA AI"


@pytest.mark.parametrize(
    "change",
    [
        {"package": "ai.tama.assistant.tamav2test"},
        {"label": "Tama V2 Test"},
        {"icon_resource": "mipmap/other"},
        {"version_code": "531"},
        {"version_name": "other"},
        {"min_sdk": "27"},
        {"target_sdk": "36"},
        {"debuggable": True},
        {"abis": ("x86_64",)},
    ],
)
def test_unexpected_release_identity_is_rejected(change: dict) -> None:
    debug = verify.parse_badging(BADGING + "application-debuggable\n", "mipmap/ic_launcher")
    release = verify.parse_badging(BADGING, "mipmap/ic_launcher")
    with pytest.raises(ValueError):
        verify.validate(debug, replace(release, **change))


def test_missing_badging_never_passes() -> None:
    with pytest.raises(ValueError):
        verify.parse_badging("package: name='ai.tama.assistant'", "mipmap/ic_launcher")


MANIFEST = """\
E: manifest
    E: application
      A: android:label(0x01010001)=@0x7f110001
      A: android:icon(0x01010002)=@0x7f0e0000
      E: activity-alias
        A: android:icon(0x01010002)=@0x7f0e0001
"""
RESOURCES = """\
      spec resource 0x7f0e0000 ai.tama.assistant:mipmap/ic_launcher: flags=0x00001100
      spec resource 0x7f0e0001 ai.tama.assistant:mipmap/another_icon: flags=0x00001100
"""


def test_icon_resource_is_resolved_from_application_not_alias() -> None:
    assert verify.resolve_application_icon(MANIFEST, RESOURCES) == "mipmap/ic_launcher"
    with pytest.raises(ValueError):
        verify.resolve_application_icon(MANIFEST.replace("@0x7f0e0000", "@0x7f0e0042"), RESOURCES)
    with pytest.raises(ValueError):
        verify.resolve_application_icon(
            MANIFEST.replace("A: android:icon", "A: android:other", 1), RESOURCES
        )


def test_release_can_shorten_the_icon_path_without_changing_icon_identity() -> None:
    debug = verify.parse_badging(BADGING + "application-debuggable\n", "mipmap/ic_launcher")
    release = verify.parse_badging(BADGING, "mipmap/ic_launcher")
    verify.validate(debug, replace(release, icon_path="res/Ab.xml"))
