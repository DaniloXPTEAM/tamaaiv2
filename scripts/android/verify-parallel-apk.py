#!/usr/bin/env python3
"""Verify the debug-only side-by-side APK against the normal APK, without installing either."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import subprocess
from pathlib import Path

TEST_ID = "ai.tama.assistant.tamav2test"
NORMAL_ID = "ai.tama.assistant"
TEST_LABEL = "Tama V2 Test"
ALIASES = {"MainActivityIconAuto", "MainActivityIconLight", "MainActivityIconDark"}


def run(*args: str) -> str:
    try:
        return subprocess.run(args, check=True, capture_output=True, text=True).stdout
    except subprocess.CalledProcessError as exc:
        detail = " ".join((exc.stderr or exc.stdout or "").split())[:700]
        raise ValueError(
            f"{Path(args[0]).name} failed with exit {exc.returncode}: {detail}"
        ) from exc


def package(badging: str) -> str:
    match = re.search(r"^package: name='([^']+)'", badging, re.MULTILINE)
    if not match:
        raise ValueError("APK package missing from badging")
    return match.group(1)


def authorities(tree: str) -> set[str]:
    lines = re.findall(r"(?m)^[ \t]*A: android:authorities\([^)]*\)=(.*)$", tree)
    values = set()
    for line in lines:
        match = re.match(r'"([^"]+)"', line)
        if not match:
            raise ValueError("A provider authority could not be read from the manifest")
        values.update(value for value in match.group(1).split(";") if value)
    return values


def element_blocks(tree: str, tag: str) -> list[str]:
    """Return each AAPT element and its descendants, bounded by indentation."""
    lines = tree.splitlines()
    element = re.compile(r"^([ \t]*)E: ([\w-]+)\b")
    blocks = []
    for start, line in enumerate(lines):
        match = element.match(line)
        if not match or match.group(2) != tag:
            continue
        indent = len(match.group(1))
        end = start + 1
        while end < len(lines):
            sibling = element.match(lines[end])
            if sibling and len(sibling.group(1)) <= indent:
                break
            end += 1
        blocks.append("\n".join(lines[start:end]))
    return blocks


def direct_attributes(block: str) -> str:
    lines = block.splitlines()[1:]
    return "\n".join(
        line
        for line in lines[
            : next(
                (index for index, line in enumerate(lines) if re.match(r"^[ \t]*E: ", line)),
                len(lines),
            )
        ]
    )


def string_attribute(attrs: str, name: str) -> str | None:
    pattern = rf'(?m)^[ \t]*A: android:{re.escape(name)}\([^)]*\)="([^"]*)"'
    match = re.search(pattern, attrs)
    return match.group(1) if match else None


def numeric_attribute(attrs: str, name: str) -> int | None:
    pattern = rf"(?m)^[ \t]*A: android:{re.escape(name)}\([^)]*\)=\(type 0x1[12]\)(0x[0-9a-fA-F]+)"
    match = re.search(pattern, attrs)
    return int(match.group(1), 16) if match else None


def declared_permissions(tree: str) -> dict[str, str]:
    result = {}
    for block in element_blocks(tree, "permission"):
        attrs = direct_attributes(block)
        name = string_attribute(attrs, "name")
        if not name or name in result:
            raise ValueError("A declared permission could not be read uniquely")
        result[name] = attrs
    return result


def validate(test_badging: str, normal_badging: str, test_tree: str, normal_tree: str) -> None:
    if package(test_badging) != TEST_ID or package(normal_badging) != NORMAL_ID:
        raise ValueError("Unexpected APK package IDs")
    if f"application-label:'{TEST_LABEL}'" not in test_badging:
        raise ValueError("The parallel APK application label is not distinct")
    if "application-debuggable" not in test_badging:
        raise ValueError("Parallel APK is not debuggable")
    native = re.search(r"^native-code: (.+)$", test_badging, re.MULTILINE)
    if not native or re.findall(r"'([^']+)'", native.group(1)) != ["arm64-v8a"]:
        raise ValueError("Parallel APK is not arm64-v8a-only")
    test_authorities, normal_authorities = authorities(test_tree), authorities(normal_tree)
    expected = {f"{TEST_ID}.shizuku", f"{TEST_ID}.fileprovider"}
    if not expected <= test_authorities or not normal_authorities:
        raise ValueError("A required provider authority is missing")
    if test_authorities & normal_authorities:
        raise ValueError("The two APKs have conflicting provider authorities")

    # AAPT badging can omit launchable-activity for an activity-alias. Check the
    # merged manifest itself instead, including the default alias's MAIN/LAUNCHER filter.
    found = {}
    for block in element_blocks(test_tree, "activity-alias"):
        attrs = direct_attributes(block)
        name = string_attribute(attrs, "name")
        short = name.rsplit(".", 1)[-1] if name else ""
        if short not in ALIASES:
            continue
        if name != f"com.openminis.app.{short}" or short in found:
            raise ValueError("A launcher alias has an unexpected or duplicate name")
        if string_attribute(attrs, "label") != TEST_LABEL:
            raise ValueError("A launcher alias lacks the distinct test label")
        if string_attribute(attrs, "targetActivity") != "com.openminis.app.MainActivity":
            raise ValueError("A launcher alias has an unexpected target")
        found[short] = (attrs, block)
    if set(found) != ALIASES:
        raise ValueError("Not all launcher aliases were found")
    attrs, block = found["MainActivityIconAuto"]
    if not numeric_attribute(attrs, "enabled") or not numeric_attribute(attrs, "exported"):
        raise ValueError("The default launcher alias is not enabled and exported")
    filters = element_blocks(block, "intent-filter")
    if not any(
        "android.intent.action.MAIN"
        in {
            string_attribute(direct_attributes(action), "name")
            for action in element_blocks(item, "action")
        }
        and "android.intent.category.LAUNCHER"
        in {
            string_attribute(direct_attributes(category), "name")
            for category in element_blocks(item, "category")
        }
        for item in filters
    ):
        raise ValueError("The default alias has no MAIN/LAUNCHER intent filter")

    test_permissions, normal_permissions = (
        declared_permissions(test_tree),
        declared_permissions(normal_tree),
    )
    alarm = test_permissions.get(f"{TEST_ID}.permission.ALARM")
    if not alarm or (numeric_attribute(alarm, "protectionLevel") or 0) & 0xF != 2:
        raise ValueError("The parallel signature permission is missing or not signature-protected")
    if set(test_permissions) & set(normal_permissions):
        raise ValueError("The two APKs declare conflicting permissions")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--aapt", required=True)
    parser.add_argument("--apksigner", required=True)
    parser.add_argument("--apk", type=Path, required=True)
    parser.add_argument("--normal-apk", type=Path, required=True)
    parser.add_argument("--out-dir", type=Path, required=True)
    args = parser.parse_args()
    test_badging = run(args.aapt, "dump", "badging", str(args.apk))
    normal_badging = run(args.aapt, "dump", "badging", str(args.normal_apk))
    test_tree = run(args.aapt, "dump", "xmltree", str(args.apk), "AndroidManifest.xml")
    normal_tree = run(args.aapt, "dump", "xmltree", str(args.normal_apk), "AndroidManifest.xml")
    validate(test_badging, normal_badging, test_tree, normal_tree)
    signature = run(args.apksigner, "verify", "--verbose", "--print-certs", str(args.apk))
    cert = re.search(r"Signer #1 certificate SHA-256 digest: ([0-9a-fA-F]+)", signature)
    if not cert or len(cert.group(1)) != 64:
        raise ValueError("Could not verify the APK signing certificate")
    digest = hashlib.sha256(args.apk.read_bytes()).hexdigest()
    args.out_dir.mkdir(parents=True, exist_ok=True)
    metadata = {
        "applicationId": TEST_ID,
        "label": TEST_LABEL,
        "abi": "arm64-v8a",
        "debuggable": True,
        "sha256": digest,
        "certificateSha256": cert.group(1).lower(),
        "sizeBytes": args.apk.stat().st_size,
        "authorities": sorted(authorities(test_tree)),
    }
    (args.out_dir / "tama-test-metadata.json").write_text(json.dumps(metadata, indent=2) + "\n")
    (args.out_dir / "tama-test.sha256").write_text(f"{digest}  {args.apk.name}\n")
    (args.out_dir / "tama-test-signature.txt").write_text(signature)
    print(
        f"::notice title=TAMA_PARALLEL_APK::sha256={digest} cert={cert.group(1).lower()} size={metadata['sizeBytes']}"
    )
    print("Verified distinct package, labels, ABI, signature permission and provider authorities.")


if __name__ == "__main__":
    main()
