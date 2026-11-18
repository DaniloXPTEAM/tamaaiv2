"""The parallel Hub's pre-admission refusals have fixed copy in every Android locale."""

from pathlib import Path
from xml.etree import ElementTree as ET

import pytest

RES = Path(__file__).resolve().parents[1] / "android/src/android/app/src/main/res"
NAMES = {"nm_hub_err_id_reused", "nm_hub_err_bad_call_id", "nm_hub_err_id_limit"}


@pytest.mark.parametrize(
    "strings_file", sorted(RES.glob("values*/nm_strings.xml")), ids=lambda path: path.parent.name
)
def test_admission_refusals_have_unformatted_fixed_copy(strings_file: Path) -> None:
    root = ET.parse(strings_file).getroot()
    for name in NAMES:
        nodes = root.findall(f"string[@name='{name}']")
        assert len(nodes) == 1
        text = "".join(nodes[0].itertext()).strip()
        assert text and "%" not in text


def test_portuguese_copy_says_the_new_request_did_not_reach_the_device() -> None:
    root = ET.parse(RES / "values-pt-rBR/nm_strings.xml").getroot()
    for name in NAMES:
        text = root.find(f"string[@name='{name}']")
        assert text is not None and "não chegou ao dispositivo" in "".join(text.itertext()).lower()
