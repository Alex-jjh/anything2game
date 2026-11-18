#!/usr/bin/env python3
"""Runs the corpus through the REAL server pipeline (as shipped) and saves every artefact for scoring.

Starts: the local Bedrock proxy (bedrock_proxy.py) and the server (server/build/install/server), both on 127.0.0.1.
For each document: install → upload → compile → poll → fetch package → generate frames (escape, rookie, companion;
case is a default frame) → save. Writes out/<run>/<doc>/{status.json, package.json, frame.<id>.json}, plus
out/<run>/server.log and out/<run>/usage.jsonl. EVAL TOOLING ONLY.
Usage: run_eval.py --model us.anthropic.claude-sonnet-5 [--only attention,zh-spaced]
"""
import argparse, json, os, subprocess, sys, time, urllib.request, urllib.error, uuid
from concurrent.futures import ThreadPoolExecutor

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))
SERVER_BIN = os.path.join(ROOT, "server/build/install/server/bin/server")
FRAMES = ["escape", "rookie", "companion"]


def http(method, url, token=None, body=None, content_type="application/json", timeout=900):
    data = body if isinstance(body, (bytes, type(None))) else json.dumps(body, ensure_ascii=False).encode()
    req = urllib.request.Request(url, data=data, method=method)
    if data is not None:
        req.add_header("Content-Type", content_type)
    if token:
        req.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            raw = r.read()
            return r.status, (json.loads(raw) if raw else None)
    except urllib.error.HTTPError as e:
        raw = e.read()
        try:
            return e.code, json.loads(raw)
        except Exception:
            return e.code, {"raw": raw[:300].decode(errors="replace")}


def wait_up(url, seconds=60):
    for _ in range(seconds * 4):
        try:
            if http("GET", url, timeout=2)[0] == 200:
                return
        except Exception:
            pass
        time.sleep(0.25)
    raise SystemExit("server did not come up: " + url)


def multipart(filename, data):
    b = "----a2g" + uuid.uuid4().hex
    head = (f"--{b}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"{filename}\"\r\n"
            "Content-Type: application/pdf\r\n\r\n").encode()
    return head + data + f"\r\n--{b}--\r\n".encode(), "multipart/form-data; boundary=" + b


def run_doc(base, doc, out):
    d = os.path.join(out, doc["id"])
    os.makedirs(d, exist_ok=True)
    rec = {"id": doc["id"], "genre": doc["genre"], "language": doc["language"]}
    t0 = time.time()
    _, inst = http("POST", base + "/installs", body={})
    tok = inst["token"]
    path = os.path.join(HERE, "corpus", doc["file"])
    if doc["kind"] == "pdf":
        body, ct = multipart(doc["file"], open(path, "rb").read())
        st, up = http("POST", base + "/documents", tok, body, ct)
    else:
        text = open(path, encoding="utf-8").read()
        title, _, rest = text.partition("\n")
        # split the article into ~1500-char pseudo pages on paragraph boundaries, like OCR'd pages would arrive
        pages, cur = [], ""
        for para in rest.split("\n"):
            if len(cur) + len(para) > 1500 and cur:
                pages.append(cur); cur = ""
            cur += para + "\n"
        pages.append(cur)
        st, up = http("POST", base + "/documents", tok, {"kind": "text", "title": title.strip(),
                                                         "pages": [{"pageIndex": i, "text": p} for i, p in enumerate(pages)]})
    rec["upload_status"] = st
    rec["upload"] = up
    if st not in (200, 201):
        rec["result"] = "rejected at upload"
        json.dump(rec, open(os.path.join(d, "status.json"), "w"), ensure_ascii=False, indent=2)
        return rec
    st, job = http("POST", base + "/compile-jobs", tok, {"documentId": up["documentId"],
                   "options": {"minutesPerLevel": 5, "language": doc["language"]}, "clientRequestId": str(uuid.uuid4())})
    t_level1 = None
    while True:
        st, job = http("GET", base + "/compile-jobs/" + job["jobId"], tok)
        if job.get("levelsReady", 0) >= 1 and t_level1 is None:
            t_level1 = time.time() - t0
        if job["state"] in ("ready", "partial", "failed", "cancelled"):
            break
        time.sleep(2)
    rec.update({"job": job, "seconds_to_level1": t_level1 and round(t_level1, 1), "seconds_to_done": round(time.time() - t0, 1)})
    if job.get("packageId"):
        st, pkg = http("GET", base + "/packages/" + job["packageId"], tok)
        json.dump(pkg, open(os.path.join(d, "package.json"), "w"), ensure_ascii=False, indent=2)
        rec["frames"] = {}
        st, case = 404, None
        for _ in range(120):  # the default frame is generated AFTER the job reports ready (eval finding F3)
            st, case = http("GET", base + f"/packages/{job['packageId']}/frames/case", tok)
            if st == 200:
                break
            time.sleep(2)
        if st != 200:
            st, case = http("POST", base + f"/packages/{job['packageId']}/frames/case", tok, {})
        rec["frames"]["case"] = st
        if st == 200:
            json.dump(case, open(os.path.join(d, "frame.case.json"), "w"), ensure_ascii=False, indent=2)
        for f in FRAMES:
            tf = time.time()
            st, ov = http("POST", base + f"/packages/{job['packageId']}/frames/{f}", tok, {})
            rec["frames"][f] = st
            rec.setdefault("frame_seconds", {})[f] = round(time.time() - tf, 1)
            if st == 200:
                json.dump(ov, open(os.path.join(d, f"frame.{f}.json"), "w"), ensure_ascii=False, indent=2)
            else:
                rec.setdefault("frame_errors", {})[f] = ov
    json.dump(rec, open(os.path.join(d, "status.json"), "w"), ensure_ascii=False, indent=2)
    return rec


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", default="us.anthropic.claude-sonnet-5")
    ap.add_argument("--only", default="")
    ap.add_argument("--run", default=time.strftime("%Y%m%d-%H%M%S"))
    ap.add_argument("--parallel", type=int, default=3)
    ap.add_argument("--pipeline", default="classic", choices=["classic", "layered"])
    ap.add_argument("--gate", default="off", choices=["off", "on"])
    ap.add_argument("--capture", action="store_true", help="save raw model answers to out/<run>/raw (debugging)")
    ap.add_argument("--port-base", type=int, default=18000, help="server = base+80, proxy = base+765 (lets arms run side by side)")
    a = ap.parse_args()
    out = os.path.join(HERE, "out", a.run)
    os.makedirs(out, exist_ok=True)
    corpus = [c for c in json.load(open(os.path.join(HERE, "corpus.json"))) if not a.only or c["id"] in a.only.split(",")]
    env = dict(os.environ)
    proxy = subprocess.Popen([os.path.join(HERE, ".venv/bin/python"), os.path.join(HERE, "bedrock_proxy.py"),
                              "--usage-log", os.path.join(out, "usage.jsonl"), "--port", str(a.port_base + 765)] + (["--capture", os.path.join(out, "raw")] if a.capture else []), env=env)
    senv = {k: v for k, v in env.items() if not k.startswith("AWS_")}  # the server never sees cloud credentials
    senv.update({"A2G_HOST": "127.0.0.1", "A2G_PORT": str(a.port_base + 80), "A2G_STORE": "file", "A2G_DATA_DIR": os.path.join(out, "data"),
                 "A2G_LLM_TIMEOUT_SECONDS": "600", "A2G_DEFAULT_FRAMES": "case", "A2G_WORKER_CONCURRENCY": "4",
                 "A2G_PIPELINE": a.pipeline, "A2G_SUPPORT_GATE": a.gate})
    for t in ("COMPILE", "FRAME", "FLASH"):
        senv.update({f"A2G_{t}_PROVIDER": "openai-compatible", f"A2G_{t}_BASE_URL": f"http://127.0.0.1:{a.port_base + 765}",
                     f"A2G_{t}_MODEL": a.model, f"A2G_{t}_API_KEY": "local-eval-proxy"})
    server = subprocess.Popen([SERVER_BIN], env=senv, stdout=open(os.path.join(out, "server.log"), "w"), stderr=subprocess.STDOUT)
    try:
        base = f"http://127.0.0.1:{a.port_base + 80}"
        wait_up(base + "/v1/health")
        with ThreadPoolExecutor(a.parallel) as pool:
            results = list(pool.map(lambda c: run_doc(base + "/v1", c, out), corpus))
        json.dump({"model": a.model, "pipeline": a.pipeline, "gate": a.gate, "results": results}, open(os.path.join(out, "run.json"), "w"), ensure_ascii=False, indent=2)
        for r in results:
            print(r["id"], r.get("result") or r["job"]["state"], r.get("seconds_to_level1"), r.get("seconds_to_done"), r.get("frames"))
        print("out:", out)
    finally:
        server.terminate(); proxy.terminate()


if __name__ == "__main__":
    sys.exit(main())
