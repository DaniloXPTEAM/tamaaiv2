"""Start the local relay for the separate JVM HubClient probe, then tear it down.

Every key and account is synthetic and kept in memory/environment only. The
listener binds to 127.0.0.1, and no Android or external endpoint is involved.
"""

import asyncio
import logging
import os
import socket
import sys

import httpx
import uvicorn
from relay_tcp_probe import Cloud, Database, LogSender, ReadyServer, Settings, create_app, sign_up


async def run(classpath: str) -> int:
    logging.getLogger("nanomuse_cloud.send").setLevel(logging.CRITICAL)
    settings = Settings(
        database=":memory:",
        secret="synthetic-test-secret",
        public_base="http://cloud.test",
        hub_frame_limit=4096,
    )
    sender = LogSender()
    cloud = Cloud(settings, Database(":memory:"), sender)
    app = create_app(settings, cloud)
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
    process = None
    try:
        await asyncio.wait_for(server.ready.wait(), 5)
        env = {
            **os.environ,
            "TAMA_SYNTHETIC_HUB_URL": f"ws://127.0.0.1:{port}/v1/hub",
            "TAMA_SYNTHETIC_TARGET_KEY": target_key,
            "TAMA_SYNTHETIC_OLD_KEY": old_key,
            "TAMA_SYNTHETIC_NEW_KEY": new_key,
        }
        process = await asyncio.create_subprocess_exec(
            "java", "-cp", classpath, "io.github.nanomuse.hub.HubRelayTcpProbe", env=env
        )
        return await asyncio.wait_for(process.wait(), 35)
    finally:
        if process is not None and process.returncode is None:
            process.kill()
            await process.wait()
        server.should_exit = True
        await asyncio.wait_for(server_task, 5)
        listener.close()


if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit("Usage: relay_client_driver.py <jvm-classpath>")
    sys.exit(asyncio.run(run(sys.argv[1])))
