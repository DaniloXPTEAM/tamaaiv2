"""Only fixed labels and numeric profile counts can enter the CI log."""

import importlib.util
import xml.etree.ElementTree as ET
from pathlib import Path

import pytest

SCRIPT = Path(__file__).resolve().parents[1] / "scripts/android/publish-profile-sizes.py"
SPEC = importlib.util.spec_from_file_location("publish_profile_sizes", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(MODULE)


def write_xml(path: Path, text: str) -> None:
    root = ET.Element("testsuite")
    ET.SubElement(root, "system-out").text = text
    ET.ElementTree(root).write(path, encoding="unicode")


def test_publishes_only_whitelisted_count_rows(tmp_path: Path) -> None:
    rows = [
        f"TAMA_PROFILE {profile} {scope} TOTAL 1 2 3 4"
        for profile in sorted(MODULE.PROFILES)
        for scope in ("SYSTEM", "TOOL")
    ]
    xml = tmp_path / "results.xml"
    write_xml(
        xml,
        "\n".join(
            rows
            + [
                "TAMA_PROFILE LEAN SYSTEM PRIVATE 1 2 3 4",
                "TAMA_PROFILE LEAN SYSTEM IDENTITY private-content 2 3 4",
                "private-content",
            ]
        ),
    )
    assert MODULE.extract(xml) == rows


def test_incomplete_report_is_not_published(tmp_path: Path) -> None:
    xml = tmp_path / "results.xml"
    write_xml(xml, "TAMA_PROFILE LEAN SYSTEM TOTAL 1 2 3 4")
    with pytest.raises(ValueError, match="incomplete"):
        MODULE.extract(xml)
