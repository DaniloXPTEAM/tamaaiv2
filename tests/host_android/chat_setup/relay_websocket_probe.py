"""Reproduce one relay id-reuse race through in-memory ASGI WebSockets.

Requires the cloud Python dependencies. All accounts and data are synthetic;
TestClient uses an in-process app and no external network. Exit 1 means the
old synthetic answer was delivered to the new caller; 0 is not a full proof.
"""

import logging
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "cloud"))

from fastapi.testclient import TestClient  # noqa: E402
from nanomuse_cloud.api import create_app  # noqa: E402
from nanomuse_cloud.config import Settings  # noqa: E402
from nanomuse_cloud.db import Database  # noqa: E402
from nanomuse_cloud.senders import LogSender  # noqa: E402
from nanomuse_cloud.service import Cloud  # noqa: E402


def sign_up(client: TestClient, sender: LogSender) -> str:
    identifier = "synthetic@example.test"
    assert client.post("/v1/auth/code", json={"identifier": identifier}).status_code == 204
    _, code = sender.sent[-1]
    response = client.post(
        "/v1/auth/verify", json={"identifier": identifier, "code": code, "device": "fixture"}
    )
    assert response.status_code == 200
    return response.json()["api_key"]


def hello(kind: str, device_id: str) -> dict:
    return {
        "type": "hello",
        "device": {
            "id": device_id,
            "name": device_id,
            "kind": kind,
            "os": "test",
            "version": "test",
            "actions": ["task"],
        },
    }


def probe() -> bool:
    settings = Settings(
        database=":memory:",
        secret="synthetic-test-secret",
        public_base="http://cloud.test",
        hub_frame_limit=4096,
    )
    sender = LogSender()
    cloud = Cloud(settings, Database(":memory:"), sender)
    with TestClient(create_app(settings, cloud)) as client:
        target_key, old_key, new_key = (sign_up(client, sender) for _ in range(3))
        with (
            client.websocket_connect(
                "/v1/hub", headers={"Authorization": f"Bearer {target_key}"}
            ) as target,
            client.websocket_connect(
                "/v1/hub", headers={"Authorization": f"Bearer {old_key}"}
            ) as old,
            client.websocket_connect(
                "/v1/hub", headers={"Authorization": f"Bearer {new_key}"}
            ) as new,
        ):
            target.send_json(hello("phone", "phone-1"))
            assert target.receive_json()["type"] == "welcome"
            assert target.receive_json()["type"] == "devices"
            old.send_json(hello("computer", "desk-old"))
            assert old.receive_json()["type"] == "welcome"
            assert old.receive_json()["type"] == "devices"
            assert target.receive_json()["type"] == "devices"
            new.send_json(hello("computer", "desk-new"))
            assert new.receive_json()["type"] == "welcome"
            assert new.receive_json()["type"] == "devices"
            assert target.receive_json()["type"] == "devices"
            assert old.receive_json()["type"] == "devices"

            frame = {
                "type": "call",
                "id": "reused-id",
                "to": "phone-1",
                "action": "task",
                "args": {"text": "synthetic"},
            }
            old.send_json(frame)
            original = target.receive_json()
            assert original["type"] == "call"
            old.close(1000)
            assert target.receive_json()["type"] == "devices"
            assert new.receive_json()["type"] == "devices"
            new.send_json(frame)
            replacement = target.receive_json()
            assert replacement["type"] == "call"
            target.send_json(
                {
                    "type": "result",
                    "id": original["id"],
                    "ok": True,
                    "body": {"text": "old synthetic answer"},
                }
            )
            received = new.receive_json()
            return received.get("body", {}).get("text") == "old synthetic answer"


if __name__ == "__main__":
    logging.getLogger("nanomuse_cloud.send").setLevel(logging.CRITICAL)
    if probe():
        print("FAIL: the old synthetic answer reached the new caller over an in-memory WebSocket")
        sys.exit(1)
    print("PASS for this one in-memory scenario: no old synthetic answer reached the new caller")
