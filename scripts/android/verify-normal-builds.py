#!/usr/bin/env python3
"""Inspect normal debug/release identities; never publish the release APK built with a CI key."""

from __future__ import annotations

import argparse
import hashlib
import re
import subprocess
from dataclasses import dataclass
from pathlib import Path


@dataclass(frozen=True)
class Identity:
    package: str
    version_code: str
    version_name: str
    label: str
    icon_path: str
    icon_resource: str
    min_sdk: str
    target_sdk: str
    debuggable: bool
    abis: tuple[str, ...]


def parse_badging(text: str, icon_resource: str) -> Identity:
    package = re.search(
        r"^package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'",
        text,
        re.MULTILINE,
    )
    application = re.search(r"^application: label='([^']+)' icon='([^']+)'", text, re.MULTILINE)
    minimum = re.search(r"^sdkVersion:'([^']+)'", text, re.MULTILINE)
    target = re.search(r"^targetSdkVersion:'([^']+)'", text, re.MULTILINE)
    native = re.search(r"^native-code: (.+)$", text, re.MULTILINE)
    if not all((package, application, minimum, target, native)):
        raise ValueError("Required normal APK identity metadata is missing")
    assert package and application and minimum and target and native
    return Identity(
        package=package.group(1),
        version_code=package.group(2),
        version_name=package.group(3),
        label=application.group(1),
        icon_path=application.group(2),
        icon_resource=icon_resource,
        min_sdk=minimum.group(1),
        target_sdk=target.group(1),
        debuggable=bool(re.search(r"^application-debuggable$", text, re.MULTILINE)),
        abis=tuple(re.findall(r"'([^']+)'", native.group(1))),
    )


def resolve_application_icon(manifest: str, resources: str) -> str:
    """Resolve the manifest resource ID, not an APK path shortened by release packaging."""
    application = re.search(r"(?m)^\s*E: application\b[^\n]*\n", manifest)
    if not application:
        raise ValueError("Application element missing from APK manifest")
    direct = re.split(r"(?m)^\s*E: ", manifest[application.end() :], maxsplit=1)[0]
    icon = re.search(r"(?m)^\s*A: android:icon\([^)]*\)=@(0x[0-9a-fA-F]+)", direct)
    if not icon:
        raise ValueError("Application icon reference missing from APK manifest")
    resource = re.search(
        rf"(?m)^\s*spec resource {re.escape(icon.group(1))} [^:]+:([^:]+):\s",
        resources,
    )
    if not resource:
        raise ValueError("Application icon resource ID is missing from the APK resource table")
    return resource.group(1)


def validate(debug: Identity, release: Identity) -> None:
    for label, value, expected_debuggable in (
        ("debug", debug, True),
        ("release", release, False),
    ):
        if (
            value.package != "ai.tama.assistant"
            or value.label != "TAMA AI"
            or not value.version_code.isdecimal()
            or not value.version_name
            or not value.min_sdk.isdecimal()
            or not value.target_sdk.isdecimal()
            or not value.icon_path
            or value.icon_resource != "mipmap/ic_launcher"
            or value.debuggable != expected_debuggable
            or value.abis != ("arm64-v8a",)
        ):
            raise ValueError(f"Unexpected normal {label} APK identity or configuration: {value}")
    if (debug.version_code, debug.version_name, debug.min_sdk, debug.target_sdk) != (
        release.version_code,
        release.version_name,
        release.min_sdk,
        release.target_sdk,
    ):
        raise ValueError("Normal debug and release have different version or SDK metadata")


def command(*arguments: str) -> str:
    try:
        return subprocess.run(arguments, check=True, capture_output=True, text=True).stdout
    except subprocess.CalledProcessError as exc:
        detail = " ".join((exc.stderr or exc.stdout or "").split())[:600]
        raise ValueError(
            f"{Path(arguments[0]).name} failed with exit {exc.returncode}: {detail}"
        ) from exc


def notice(title: str, message: str) -> None:
    safe = " ".join(message.split())[:1200].replace("%", "%25")
    print(f"::notice title={title}::{safe}", flush=True)


def inspect(aapt: str, apk: Path, label: str) -> Identity:
    badging = command(aapt, "dump", "badging", str(apk))
    # Only these build-generated metadata lines go into annotations. A failure remains a failure.
    relevant = [
        line
        for line in badging.splitlines()
        if line.startswith(
            ("package:", "application:", "sdkVersion:", "targetSdkVersion:", "native-code:")
        )
        or line == "application-debuggable"
    ]
    notice(f"normal {label} badging", " | ".join(relevant))
    manifest = command(aapt, "dump", "xmltree", str(apk), "AndroidManifest.xml")
    resources = command(aapt, "dump", "resources", str(apk))
    icon_resource = resolve_application_icon(manifest, resources)
    notice(f"normal {label} icon", icon_resource)
    return parse_badging(badging, icon_resource)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--aapt", required=True)
    parser.add_argument("--apksigner", required=True)
    parser.add_argument("--debug", type=Path, required=True)
    parser.add_argument("--release", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    try:
        debug = inspect(args.aapt, args.debug, "debug")
        release = inspect(args.aapt, args.release, "release")
        validate(debug, release)
        for apk in (args.debug, args.release):
            command(args.apksigner, "verify", str(apk))
        lines = [
            "Normal build identity; CI release is debug-key-signed and not a production release.",
            f"debug: {debug}",
            f"release: {release}",
            f"debug_sha256={hashlib.sha256(args.debug.read_bytes()).hexdigest()}",
            f"release_sha256={hashlib.sha256(args.release.read_bytes()).hexdigest()}",
        ]
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text("\n".join(lines) + "\n")
        notice("normal identity", "Debug and release APK metadata and signatures verified")
        print("\n".join(lines))
    except (OSError, ValueError) as exc:
        # Visible even where Actions log storage is inaccessible; do not mark the check passed.
        notice("normal identity failure", f"{type(exc).__name__}: {exc}")
        raise


if __name__ == "__main__":
    main()
