"""Probe one known Hub relay id-reuse race with fakes; no socket or network.

Run from the checkout with the cloud's Python dependencies installed:
    python tests/host_android/chat_setup/relay_reuse_probe.py
Exit 1 means this scenario routed an old synthetic answer to the new caller.
Exit 0 means only that this one scenario did not reproduce; it is not a security proof.
"""

import asyncio
import sys
from pathlib import Path
from types import SimpleNamespace

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT))

from cloud.nanomuse_cloud.hub import Hub  # noqa: E402


class FakeDevice:
    def __init__(self, device_id: str, kind: str) -> None:
        self.account_id = "synthetic-account"
        self.device_id = device_id
        self.name = device_id
        self.kind = kind
        self.closed = False
        self.frames: list[dict] = []

    async def send(self, frame: dict) -> None:
        self.frames.append(frame)

    def brief(self) -> dict:
        return {"id": self.device_id, "name": self.name, "kind": self.kind}


async def probe() -> bool:
    hub = Hub(SimpleNamespace(db=None))
    old = FakeDevice("old-desk", "computer")
    target = FakeDevice("phone", "phone")
    new = FakeDevice("new-desk", "computer")
    hub.online[old.account_id] = {device.device_id: device for device in (old, target, new)}
    frame = {
        "id": "reused-id",
        "to": target.device_id,
        "action": "task",
        "args": {"text": "synthetic"},
    }
    await hub._call(old, frame)
    if not target.frames:
        raise RuntimeError("The initial synthetic call was not delivered to the fake target")
    old_target_id = target.frames[-1]["id"]
    hub.online[old.account_id].pop(old.device_id)
    await hub._fail_pending_for(old)
    await hub._call(new, frame)
    # The old target worker is still running and answers after the id was reused.
    await hub._answer(
        target,
        {
            "type": "result",
            "id": old_target_id,
            "ok": True,
            "body": {"text": "old synthetic answer"},
        },
    )
    return any(item.get("body", {}).get("text") == "old synthetic answer" for item in new.frames)


if __name__ == "__main__":
    misrouted = asyncio.run(probe())
    if misrouted:
        print("FAIL: the old synthetic answer reached the new caller after id reuse")
        sys.exit(1)
    print("PASS for this one fake scenario: no old synthetic answer reached the new caller")
