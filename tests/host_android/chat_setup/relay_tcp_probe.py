"""Reproduce relay ID reuse with actual TCP WebSockets, only on IPv4 loopback.

Starts the checkout's real ASGI relay and three synthetic devices with an
in-memory database. No paid endpoint, real credential, Android or deployed
service is used. Exit 1 means old synthetic frames reached the new caller.
"""

import asyncio
import json
import logging
import socket
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "cloud"))

import httpx  # noqa: E402
import uvicorn  # noqa: E402
from nanomuse_cloud.api import create_app  # noqa: E402
from nanomuse_cloud.config import Settings  # noqa: E402
from nanomuse_cloud.db import Database  # noqa: E402
from nanomuse_cloud.hub import Hub  # noqa: E402
from nanomuse_cloud.senders import LogSender  # noqa: E402
from nanomuse_cloud.service import Cloud  # noqa: E402
from websockets.asyncio.client import connect  # noqa: E402


class ReadyServer(uvicorn.Server):
    def __init__(self, config: uvicorn.Config) -> None:
        super().__init__(config)
        self.ready = asyncio.Event()

    async def startup(self, sockets=None) -> None:
        await super().startup(sockets=sockets)
        self.ready.set()


async def sign_up(client: httpx.AsyncClient, sender: LogSender) -> str:
    identifier = "synthetic@example.test"
    assert (await client.post("/v1/auth/code", json={"identifier": identifier})).status_code == 204
    _, code = sender.sent[-1]
    response = await client.post(
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


async def send(ws, frame: dict) -> None:
    await ws.send(json.dumps(frame))


async def receive(ws, expected: str) -> dict:
    frame = json.loads(await asyncio.wait_for(ws.recv(), 5))
    assert frame["type"] == expected, (frame, expected)
    return frame


async def probe() -> bool:
    settings = Settings(
        database=":memory:",
        secret="synthetic-test-secret",
        public_base="http://cloud.test",
        hub_frame_limit=4096,
    )
    sender = LogSender()
    cloud = Cloud(settings, Database(":memory:"), sender)
    app = create_app(settings, cloud)
    assert isinstance(app.state.hub, Hub)
    async with httpx.AsyncClient(
        transport=httpx.ASGITransport(app=app), base_url="http://cloud.test"
    ) as client:
        target_key, old_key, new_key = [await sign_up(client, sender) for _ in range(3)]

    listener = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    listener.bind(("127.0.0.1", 0))
    listener.listen(128)
    port = listener.getsockname()[1]
    listener.setblocking(False)
    server = ReadyServer(uvicorn.Config(app, lifespan="off", log_level="critical", log_config=None))
    server_task = asyncio.create_task(server.serve(sockets=[listener]))
    try:
        await asyncio.wait_for(server.ready.wait(), 5)
        url = f"ws://127.0.0.1:{port}/v1/hub"
        async with (
            connect(
                url,
                additional_headers={"Authorization": f"Bearer {target_key}"},
                ping_interval=None,
            ) as target,
            connect(
                url, additional_headers={"Authorization": f"Bearer {old_key}"}, ping_interval=None
            ) as old,
            connect(
                url, additional_headers={"Authorization": f"Bearer {new_key}"}, ping_interval=None
            ) as new,
        ):
            await send(target, hello("phone", "phone-1"))
            await receive(target, "welcome")
            await receive(target, "devices")
            await send(old, hello("computer", "desk-old"))
            await receive(old, "welcome")
            await receive(old, "devices")
            await receive(target, "devices")
            await send(new, hello("computer", "desk-new"))
            await receive(new, "welcome")
            await receive(new, "devices")
            await receive(target, "devices")
            await receive(old, "devices")

            request = {
                "type": "call",
                "id": "reused-id",
                "to": "phone-1",
                "action": "task",
                "args": {"text": "synthetic"},
            }
            await send(old, request)
            original = await receive(target, "call")
            await old.close()
            await receive(target, "devices")
            await receive(new, "devices")
            await send(new, request)
            replacement = await receive(target, "call")
            await send(
                target,
                {
                    "type": "event",
                    "id": original["id"],
                    "body": {"stage": "text", "text": "old synthetic event"},
                },
            )
            await send(
                target,
                {
                    "type": "result",
                    "id": original["id"],
                    "ok": True,
                    "body": {"text": "old synthetic answer"},
                },
            )
            await send(
                target,
                {
                    "type": "event",
                    "id": replacement["id"],
                    "body": {"stage": "text", "text": "new synthetic event"},
                },
            )
            await send(
                target,
                {
                    "type": "result",
                    "id": replacement["id"],
                    "ok": True,
                    "body": {"text": "new synthetic answer"},
                },
            )
            event = await receive(new, "event")
            result = await receive(new, "result")
            return (
                event.get("body", {}).get("text") == "old synthetic event"
                or result.get("body", {}).get("text") == "old synthetic answer"
            )
    finally:
        server.should_exit = True
        await asyncio.wait_for(server_task, 5)
        listener.close()


if __name__ == "__main__":
    logging.getLogger("nanomuse_cloud.send").setLevel(logging.CRITICAL)
    if asyncio.run(probe()):
        print("FAIL: old synthetic progress or result reached the new caller over TCP loopback")
        sys.exit(1)
    print("PASS for this one TCP loopback scenario: no old synthetic frames reached the new caller")
