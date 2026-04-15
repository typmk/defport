#!/usr/bin/env python3
"""Minimal fake rosbridge_suite server for defport integration testing.

Listens on ws://localhost:<port>/ and responds to the subset of
rosbridge v2.0 ops defport's client exercises:

- subscribe  → echoes one fake publish back on the requested topic
- call_service → returns a canned service_response with the request id
- send_action_goal → returns a canned action_result with the request id

Enough to prove defport's client speaks correct rosbridge wire.

Requires `websockets` (stdlib compatibility layer, usually
pre-installed on modern Debian/Ubuntu/Kali).

Usage:
    python3 fake_rosbridge_server.py <port>
"""

import asyncio
import json
import sys

import websockets


async def handler(ws):
    async for raw in ws:
        try:
            msg = json.loads(raw)
        except Exception:
            continue
        op = msg.get("op")

        if op == "subscribe":
            topic = msg.get("topic")
            # Echo a fake publish back so the client's on-topic
            # handler fires.
            await ws.send(json.dumps({
                "op": "publish",
                "topic": topic,
                "msg": {"data": "hello from fake rosbridge"},
            }))

        elif op == "call_service":
            await ws.send(json.dumps({
                "op": "service_response",
                "service": msg.get("service"),
                "id": msg.get("id"),
                "values": {"sum": 42},
                "result": True,
            }))

        elif op == "send_action_goal":
            # Emit a feedback op then a result op.
            await ws.send(json.dumps({
                "op": "action_feedback",
                "action": msg.get("action"),
                "values": {"sequence": [0, 1, 1]},
            }))
            await ws.send(json.dumps({
                "op": "action_result",
                "action": msg.get("action"),
                "id": msg.get("id"),
                "values": {"sequence": [0, 1, 1, 2, 3]},
                "result": True,
            }))

        # Everything else (advertise, publish, unsubscribe, etc.)
        # we silently accept. Sufficient for a round-trip test.


async def main():
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 9090
    async with websockets.serve(handler, "127.0.0.1", port):
        print(f"fake-rosbridge listening on ws://127.0.0.1:{port}", file=sys.stderr, flush=True)
        await asyncio.Future()   # run forever


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        pass
