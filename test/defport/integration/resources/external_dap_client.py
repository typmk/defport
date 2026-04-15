#!/usr/bin/env python3
"""External DAP client written in Python stdlib.

Spawns a defport DAP server as a subprocess, speaks DAP 1.65 over
Content-Length-framed JSON messages, and asserts real responses.

Exits 0 if every assertion passes, 1 otherwise.

Usage:
    python3 external_dap_client.py <server-cmd> [args...]
"""

import json
import subprocess
import sys
import threading
import time


def log(msg):
    print(f"[external-dap-client] {msg}", file=sys.stderr, flush=True)


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
    deadline = time.time() + timeout_seconds
    header_buf = b""
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
    headers_blob, _, _ = header_buf.partition(b"\r\n\r\n")
    content_len = None
    for line in headers_blob.decode("ascii").split("\r\n"):
        if line.lower().startswith("content-length:"):
            content_len = int(line.split(":", 1)[1].strip())
            break
    if content_len is None:
        return None
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
        log("usage: external_dap_client.py <server-cmd> [args...]")
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

    seq = 0
    def next_seq():
        nonlocal seq
        seq += 1
        return seq

    try:
        # 1. initialize
        init_seq = next_seq()
        send_framed(proc, {
            "seq": init_seq, "type": "request", "command": "initialize",
            "arguments": {
                "clientID": "external-python-client",
                "clientName": "external-python",
                "adapterID": "defport-example",
                "pathFormat": "path",
                "linesStartAt1": True,
                "columnsStartAt1": True,
            },
        })
        resp = recv_framed(proc, 60)
        assert resp is not None, "no response to initialize"
        assert resp.get("request_seq") == init_seq, f"initialize request_seq mismatch: {resp}"
        assert resp.get("success") is True, f"initialize failed: {resp}"
        log(f"initialize ok: body={list((resp.get('body') or {}).keys())[:5]}...")

        # 2. launch
        launch_seq = next_seq()
        send_framed(proc, {
            "seq": launch_seq, "type": "request", "command": "launch",
            "arguments": {"program": "noop"},
        })
        resp = recv_framed(proc, 30)
        assert resp is not None, "no response to launch"
        assert resp.get("request_seq") == launch_seq, f"launch request_seq mismatch: {resp}"
        assert resp.get("success") is True, f"launch failed: {resp}"
        log("launch ok")

        # 3. threads — the example server returns one main thread
        threads_seq = next_seq()
        send_framed(proc, {
            "seq": threads_seq, "type": "request", "command": "threads",
        })
        resp = recv_framed(proc, 30)
        assert resp is not None, "no response to threads"
        assert resp.get("request_seq") == threads_seq
        assert resp.get("success") is True
        threads = resp.get("body", {}).get("threads", [])
        log(f"threads → {threads}")
        assert len(threads) >= 1, "expected at least one thread"

        # 4. evaluate — the example server echoes the expression back
        eval_seq = next_seq()
        send_framed(proc, {
            "seq": eval_seq, "type": "request", "command": "evaluate",
            "arguments": {"expression": "(+ 1 2)", "frameId": 1, "context": "repl"},
        })
        resp = recv_framed(proc, 30)
        assert resp is not None, "no response to evaluate"
        assert resp.get("request_seq") == eval_seq
        assert resp.get("success") is True
        result = resp.get("body", {}).get("result", "")
        log(f"evaluate (+ 1 2) → {result!r}")

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
