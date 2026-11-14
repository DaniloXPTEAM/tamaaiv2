"""Battery settings must not promise that a background task can never be interrupted."""

from __future__ import annotations

import xml.etree.ElementTree as ET
from pathlib import Path

RES = Path(__file__).resolve().parents[1] / "android/src/android/app/src/main/res"
KEYS = {
    "battery_opt_section_footer",
    "battery_opt_row_title",
    "battery_opt_already_exempt",
    "battery_opt_request_subtitle",
}
DISCLAIMER = {
    "values": "does not guarantee",
    "values-de": "garantiert aber keinen",
    "values-es": "no garantiza",
    "values-fil": "hindi nito ginagarantiyang",
    "values-fr": "sans garantir",
    "values-in": "tidak menjamin",
    "values-ja": "保証されません",
    "values-ko": "보장하지 않습니다",
    "values-ms": "tidak menjamin",
    "values-pl": "nie gwarantuje",
    "values-pt-rBR": "não garante",
    "values-ro": "nu garantează",
    "values-ru": "не гарантирует",
    "values-th": "ไม่รับประกัน",
    "values-tr": "garanti etmez",
    "values-zh": "不能保证",
    "values-zh-rTW": "不能保證",
}


def strings(path: Path) -> dict[str, str]:
    root = ET.parse(path).getroot()
    found: dict[str, str] = {}
    for item in root.findall("string"):
        name = item.attrib.get("name")
        if name in KEYS:
            assert name not in found, (path, name)
            found[name] = "".join(item.itertext()).strip()
    return found


def test_battery_exemption_copy_exists_in_all_locales_without_format_arguments() -> None:
    files = sorted(RES.glob("values*/strings.xml"))
    assert {path.parent.name for path in files} == DISCLAIMER.keys()
    for path in files:
        found = strings(path)
        assert found.keys() == KEYS, path
        assert all(text and "%" not in text for text in found.values()), path
        assert DISCLAIMER[path.parent.name] in found["battery_opt_section_footer"].lower(), path


def test_english_and_portuguese_make_the_limit_and_permission_clear() -> None:
    en = strings(RES / "values/strings.xml")
    pt = strings(RES / "values-pt-rBR/strings.xml")
    assert "may reduce restrictions" in en["battery_opt_section_footer"].lower()
    assert "does not guarantee" in en["battery_opt_section_footer"].lower()
    assert "request" in en["battery_opt_request_subtitle"].lower()
    assert "may still stop" in en["battery_opt_request_subtitle"].lower()
    assert "pode reduzir restrições" in pt["battery_opt_section_footer"].lower()
    assert "não garante" in pt["battery_opt_section_footer"].lower()
    assert "solicitar" in pt["battery_opt_request_subtitle"].lower()
    assert "ainda pode ser interrompida" in pt["battery_opt_request_subtitle"].lower()
    assert "isenção" in pt["battery_opt_already_exempt"].lower()
    assert "sem interrupções" not in pt["battery_opt_request_subtitle"].lower()


def test_service_rejection_warnings_exist_in_every_locale() -> None:
    names = {
        "nm_background_start_rejected",
        "nm_background_stop_rejected",
        "nm_background_service_retry",
    }
    files = sorted(RES.glob("values*/nm_strings.xml"))
    assert {path.parent.name for path in files} == DISCLAIMER.keys()
    for path in files:
        root = ET.parse(path).getroot()
        found = {
            item.attrib["name"]: "".join(item.itertext()).strip()
            for item in root.findall("string")
            if item.attrib.get("name") in names
        }
        assert found.keys() == names, path
        assert all(value and "%" not in value for value in found.values()), path


def test_service_warning_distinguishes_failed_start_from_failed_stop() -> None:
    def load(locale: str) -> dict[str, str]:
        root = ET.parse(RES / locale / "nm_strings.xml").getroot()
        return {
            item.attrib["name"]: "".join(item.itertext()).strip().lower()
            for item in root.findall("string")
            if item.attrib.get("name", "").startswith("nm_background_")
        }

    en, pt = load("values"), load("values-pt-rBR")
    assert "keep tama open" in en["nm_background_start_rejected"]
    assert "may stop" in en["nm_background_start_rejected"]
    assert "notification may remain" in en["nm_background_stop_rejected"]
    assert "mantenha o tama aberto" in pt["nm_background_start_rejected"]
    assert "pode parar" in pt["nm_background_start_rejected"]
    assert "notificação pode continuar visível" in pt["nm_background_stop_rejected"]
