#!/usr/bin/env python3
"""External LSP client written in Python stdlib.

Spawns a defport LSP server as a subprocess, speaks LSP 3.17 over
Content-Length-framed JSON-RPC, and asserts real responses.

Exits 0 if every assertion passes, 1 otherwise.

Usage:
    python3 external_lsp_client.py <server-cmd> [args...]

Example:
    python3 external_lsp_client.py \\
        clojure -M:examples -m lsp-server
"""

import json
import subprocess
import sys
import threading
import time


def log(msg):
    print(f"[external-lsp-client] {msg}", file=sys.stderr, flush=True)


def drain_stderr(proc):
    for line in iter(proc.stderr.readline, b""):
        try:
            sys.stderr.write("[server stderr] " + line.decode("utf-8"))
            sys.stderr.flush()
        except Exception:
            pass


def send_framed(proc, msg):
    body = json.dumps(msg).encode("utf-8")
    header = f"Content-Length: {len(body)}\r\n\r\n".encode("ascii")
    proc.stdin.write(header + body)
    proc.stdin.flush()


def recv_framed(proc, timeout_seconds):
    """Read one Content-Length-framed message from proc.stdout."""
    deadline = time.time() + timeout_seconds
    header_buf = b""
    # Read headers until we see \r\n\r\n
    while b"\r\n\r\n" not in header_buf:
        if time.time() > deadline:
            return None
        b = proc.stdout.read(1)
        if not b:
            if proc.poll() is not None:
                return None
            time.sleep(0.01)
            continue
        header_buf += b
    headers_blob, _, rest = header_buf.partition(b"\r\n\r\n")
    content_len = None
    for line in headers_blob.decode("ascii").split("\r\n"):
        if line.lower().startswith("content-length:"):
            content_len = int(line.split(":", 1)[1].strip())
            break
    if content_len is None:
        return None
    # Read content-len bytes (may have partial in `rest`; we consumed
    # exactly the header, so start from 0).
    body = b""
    while len(body) < content_len:
        if time.time() > deadline:
            return None
        chunk = proc.stdout.read(content_len - len(body))
        if not chunk:
            if proc.poll() is not None:
                return None
            time.sleep(0.01)
            continue
        body += chunk
    return json.loads(body.decode("utf-8"))


def run_client(argv):
    if len(argv) < 2:
        log("usage: external_lsp_client.py <server-cmd> [args...]")
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
        send_framed(proc, {
            "jsonrpc": "2.0", "id": 1, "method": "initialize",
            "params": {
                "processId": None,
                "rootUri": "file:///tmp",
                "clientInfo": {"name": "external-python-client", "version": "0.1.0"},
                "capabilities": {},
            },
        })
        resp = recv_framed(proc, 60)
        assert resp is not None, "no response to initialize"
        assert resp.get("id") == 1, f"initialize id mismatch: {resp}"
        assert "result" in resp, f"initialize has no :result: {resp}"
        caps = resp["result"].get("capabilities", {})
        log(f"initialize ok: capabilities={list(caps.keys())}")

        # 2. initialized notification
        send_framed(proc, {"jsonrpc": "2.0", "method": "initialized", "params": {}})

        # 3. hover at a position (expect a valid LSP hover or null)
        send_framed(proc, {
            "jsonrpc": "2.0", "id": 2, "method": "textDocument/hover",
            "params": {
                "textDocument": {"uri": "file:///tmp/example.clj"},
                "position": {"line": 0, "character": 0},
            },
        })
        resp = recv_framed(proc, 30)
        assert resp is not None, "no response to hover"
        assert resp.get("id") == 2, f"hover id mismatch: {resp}"
        assert "result" in resp, f"hover has no :result: {resp}"
        log(f"hover → {resp['result']}")

        # 4. document symbols (always returns a list for the example server)
        send_framed(proc, {
            "jsonrpc": "2.0", "id": 3, "method": "textDocument/documentSymbol",
            "params": {"textDocument": {"uri": "file:///tmp/example.clj"}},
        })
        resp = recv_framed(proc, 30)
        assert resp is not None, "no response to documentSymbol"
        assert resp.get("id") == 3, f"documentSymbol id mismatch: {resp}"
        result = resp.get("result", [])
        log(f"documentSymbol → {len(result)} symbols")
        for sym in result[:3]:
            log(f"  - {sym.get('name')}")

        # 5. shutdown + exit
        send_framed(proc, {"jsonrpc": "2.0", "id": 4, "method": "shutdown"})
        recv_framed(proc, 30)
        send_framed(proc, {"jsonrpc": "2.0", "method": "exit"})

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
