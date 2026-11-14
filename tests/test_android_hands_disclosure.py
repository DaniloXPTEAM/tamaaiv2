"""Keep the Android screen-operation disclosures present and honest about their limits."""

from __future__ import annotations

import xml.etree.ElementTree as ET
from pathlib import Path

import pytest

RES = Path(__file__).resolve().parents[1] / "android/src/android/app/src/main/res"
KEYS = {
    "nm_hands_switch_footer",
    "nm_hands_needs_footer",
    "nm_hands_rule_secrets",
    "nm_hands_rule_approvals",
    "nm_hands_rule_stop",
    "nm_hands_rule_screenshots",
}


def strings(locale: str) -> dict[str, str]:
    root = ET.parse(RES / locale / "nm_strings.xml").getroot()
    return {
        item.attrib["name"]: "".join(item.itertext()).strip()
        for item in root.findall("string")
        if item.attrib.get("name") in KEYS
    }


def test_disclosures_exist_and_are_not_empty_in_every_supported_locale() -> None:
    files = sorted(RES.glob("values*/nm_strings.xml"))
    assert len(files) >= 17
    for path in files:
        found = strings(path.parent.name)
        assert found.keys() == KEYS, path
        assert all(found.values()), path


@pytest.mark.parametrize(
    ("key", "english", "portuguese"),
    [
        ("nm_hands_switch_footer", ("sensitive", "stop"), ("sensíveis", "parar")),
        ("nm_hands_needs_footer", ("may read", "labels"), ("pode ler", "rótulos")),
        ("nm_hands_rule_secrets", ("can miss", "stop"), ("pode falhar", "parar")),
        ("nm_hands_rule_approvals", ("grant", "can miss"), ("anteriores", "pode falhar")),
        (
            "nm_hands_rule_stop",
            ("in progress", "may still finish"),
            ("em andamento", "pode terminar"),
        ),
        (
            "nm_hands_rule_screenshots",
            ("remote provider", "sensitive"),
            ("provedor remoto", "sensíveis"),
        ),
    ],
)
def test_english_and_brazilian_portuguese_explain_the_actual_limits(
    key: str, english: tuple[str, ...], portuguese: tuple[str, ...]
) -> None:
    en = strings("values")[key].lower()
    pt = strings("values-pt-rBR")[key].lower()
    assert all(phrase in en for phrase in english)
    assert all(phrase in pt for phrase in portuguese)
    assert "does not read the screen" not in en
    assert "não lê o conteúdo da tela" not in pt
