#!/usr/bin/env python3
"""Local OpenAI-compatible endpoint that forwards to Amazon Bedrock (Converse). EVAL TOOLING ONLY — not part of the product.

The server's existing `openai-compatible` provider points here (http://127.0.0.1:<port>), so the harness is tested exactly as
shipped, with no server code change. Binds to 127.0.0.1 only. Credentials come from the AWS SDK chain (AWS_PROFILE /
AWS_CONFIG_FILE with credential_process); no credential value is read, printed or written by this script.
Logs one JSON line per call to --usage-log: model, step (first line of our own system prompt), tokens, latency, stop reason.
"""
import argparse, json, threading, time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import boto3
from botocore.config import Config

REGION_BY_PREFIX = {"us.anthropic.": "us-west-2", "us.openai.": "us-east-2", "global.openai.": "us-east-2"}
_clients, _lock = {}, threading.Lock()


def client_for(model):
    region = next((r for p, r in REGION_BY_PREFIX.items() if model.startswith(p)), "us-west-2")
    with _lock:
        if region not in _clients:
            _clients[region] = boto3.Session(region_name=region).client(
                "bedrock-runtime", config=Config(retries={"max_attempts": 6, "mode": "adaptive"}, read_timeout=600))
        return _clients[region]


class Handler(BaseHTTPRequestHandler):
    usage_log = None

    def log_message(self, *args):  # silence default access log (it would print paths only, but keep stdout clean)
        pass

    def _send(self, status, obj):
        body = json.dumps(obj).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_POST(self):
        if not self.path.rstrip("/").endswith("/chat/completions"):
            return self._send(404, {"error": "not found"})
        req = json.loads(self.rfile.read(int(self.headers.get("Content-Length", 0))))
        model = req["model"]
        system = "\n".join(m["content"] for m in req["messages"] if m["role"] == "system")
        msgs = [{"role": m["role"], "content": [{"text": m["content"]}]} for m in req["messages"] if m["role"] != "system"]
        step = (system.strip().splitlines() or [""])[0][:80]
        t0 = time.time()
        try:
            r = client_for(model).converse(modelId=model, system=[{"text": system}] if system else [], messages=msgs,
                                           inferenceConfig={"maxTokens": int(req.get("max_tokens", 4096))})
        except Exception as e:  # report the class only; provider messages can echo request content
            self._log({"model": model, "step": step, "error": type(e).__name__, "ms": int((time.time() - t0) * 1000)})
            status = 429 if "Throttl" in type(e).__name__ else 502
            return self._send(status, {"error": {"type": type(e).__name__}})
        text = "".join(b.get("text", "") for b in r["output"]["message"]["content"])
        u = r.get("usage", {})
        self._log({"model": model, "step": step, "in": u.get("inputTokens"), "out": u.get("outputTokens"),
                   "ms": int((time.time() - t0) * 1000), "stop": r.get("stopReason")})
        finish = "length" if r.get("stopReason") == "max_tokens" else "stop"
        self._send(200, {"choices": [{"index": 0, "message": {"role": "assistant", "content": text}, "finish_reason": finish}],
                         "usage": {"prompt_tokens": u.get("inputTokens"), "completion_tokens": u.get("outputTokens")}})

    def _log(self, row):
        row["t"] = time.strftime("%H:%M:%S")
        with _lock, open(self.usage_log, "a") as f:
            f.write(json.dumps(row, ensure_ascii=False) + "\n")


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=18765)
    ap.add_argument("--usage-log", required=True)
    a = ap.parse_args()
    Handler.usage_log = a.usage_log
    ThreadingHTTPServer(("127.0.0.1", a.port), Handler).serve_forever()
