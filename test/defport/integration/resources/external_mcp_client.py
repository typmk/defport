#!/usr/bin/env python3
"""External MCP client written in Python stdlib.

Spawns a defport MCP server as a subprocess (any `-main` that calls
`defport.sugar/run! {:protocol :mcp}`), speaks JSON-lines per the
MCP 2025-11-25 stdio transport, and asserts real responses.

Exits 0 if every assertion passes, 1 otherwise. Prints a short
report on stderr.

Usage:
    python3 external_mcp_client.py <server-command> [args...]

Example:
    python3 external_mcp_client.py \\
        clojure -M:examples -m mcp-server
"""

import json
import os
import subprocess
import sys
import threading
import time

TIMEOUT_SECONDS = 120


def log(msg):
    print(f"[external-mcp-client] {msg}", file=sys.stderr, flush=True)


def drain_stderr(proc):
    for line in iter(proc.stderr.readline, b""):
        try:
            sys.stderr.write("[server stderr] " + line.decode("utf-8"))
            sys.stderr.flush()
        except Exception:
            pass


def send_jsonline(proc, msg):
    data = (json.dumps(msg) + "\n").encode("utf-8")
    proc.stdin.write(data)
    proc.stdin.flush()


def recv_jsonline(proc, timeout_seconds):
    """Read one JSON-line response from proc.stdout with a timeout."""
    deadline = time.time() + timeout_seconds
    buf = b""
    while time.time() < deadline:
        fd = proc.stdout.fileno()
        # Poll with a 200ms window. Can't use select on a Popen pipe's
        # fileno on all platforms, so just try a readline and rely on
        # the parent timeout.
        line = proc.stdout.readline()
        if line:
            return json.loads(line.decode("utf-8"))
        if proc.poll() is not None:
            return None
        time.sleep(0.05)
    return None


def run_client(argv):
    if len(argv) < 2:
        log("usage: external_mcp_client.py <server-cmd> [args...]")
        return 2

    log(f"spawning: {' '.join(argv[1:])}")
    proc = subprocess.Popen(
        argv[1:],
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )
    t = threading.Thread(target=drain_stderr, args=(proc,), daemon=True)
    t.start()

    try:
        # 1. initialize handshake
        send_jsonline(proc, {
            "jsonrpc": "2.0",
            "id": 1,
            "method": "initialize",
            "params": {
                "protocolVersion": "2025-11-25",
                "clientInfo": {"name": "external-python-client", "version": "0.1.0"},
                "capabilities": {},
            },
        })
        resp = recv_jsonline(proc, 60)
        assert resp is not None, "no response to initialize"
        assert resp.get("id") == 1, f"initialize id mismatch: {resp}"
        assert "result" in resp, f"initialize has no :result: {resp}"
        log(f"initialize ok: serverInfo={resp['result'].get('serverInfo')}")

        # 2. initialized notification (no response expected)
        send_jsonline(proc, {
            "jsonrpc": "2.0",
            "method": "notifications/initialized",
            "params": {},
        })

        # 3. tools/list
        send_jsonline(proc, {
            "jsonrpc": "2.0", "id": 2, "method": "tools/list", "params": {},
        })
        resp = recv_jsonline(proc, 30)
        assert resp is not None, "no response to tools/list"
        assert resp.get("id") == 2, f"tools/list id mismatch: {resp}"
        tools = resp.get("result", {}).get("tools", [])
        log(f"tools/list: {len(tools)} tools")
        for t in tools:
            log(f"  - {t.get('name')}")

        # 4. tools/call against the first tool (if any)
        if tools:
            tool_name = tools[0]["name"]
            # Best-effort: the example mcp-server exposes `add` with
            # {a,b} integer args.
            args = {"a": 3, "b": 4} if tool_name == "add" else {}
            send_jsonline(proc, {
                "jsonrpc": "2.0", "id": 3, "method": "tools/call",
                "params": {"name": tool_name, "arguments": args},
            })
            resp = recv_jsonline(proc, 30)
            assert resp is not None, "no response to tools/call"
            assert resp.get("id") == 3, f"tools/call id mismatch: {resp}"
            assert "result" in resp, f"tools/call has no :result: {resp}"
            log(f"tools/call {tool_name}({args}) → {resp['result']}")

        log("PASS")
        return 0

    except AssertionError as e:
        log(f"FAIL: {e}")
        return 1
    except Exception as e:
        log(f"ERROR: {type(e).__name__}: {e}")
        return 2
    finally:
        try:
            proc.stdin.close()
        except Exception:
            pass
        try:
            proc.terminate()
            proc.wait(timeout=5)
        except Exception:
            try:
                proc.kill()
            except Exception:
                pass


if __name__ == "__main__":
    sys.exit(run_client(sys.argv))
