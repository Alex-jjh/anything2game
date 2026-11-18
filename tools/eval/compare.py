#!/usr/bin/env python3
"""Compares eval runs (arms) on the same corpus: pipeline health, latency, cost, judge metrics. EVAL TOOLING ONLY.
Usage: compare.py <run-A> <run-B> [<run-C> ...]   → prints markdown and writes out/compare-<runs>.md"""
import json, os, re, statistics, sys

HERE = os.path.dirname(os.path.abspath(__file__))
PRICE = {"us.anthropic.claude-sonnet-5": (2.0, 10.0)}
CRIT = ["grounded", "unambiguous", "important", "feedback", "mechanic_fit", "mobile_clarity"]


def load(run):
    out = os.path.join(HERE, "out", run)
    r = json.load(open(os.path.join(out, "run.json")))
    usage = [json.loads(l) for l in open(os.path.join(out, "usage.jsonl"))]
    j = json.load(open(os.path.join(out, "judge.json")))
    log = open(os.path.join(out, "server.log"), errors="replace").read()
    return out, r, usage, j, log


def m(xs):
    xs = [x for x in xs if isinstance(x, (int, float))]
    return round(statistics.mean(xs), 2) if xs else None


def main(runs):
    rows = []
    for run in runs:
        out, r, usage, j, log = load(run)
        res = [x for x in r["results"] if x.get("job")]
        units = [x for x in j["rows"] if x["kind"] == "unit" and "error" not in x["verdict"]]
        parts = sum(int(u["verdict"].get("parts_total") or 0) for u in units)
        wrong = sum(len(u["verdict"].get("wrong_parts") or []) for u in units)
        pi, po = PRICE.get(r["model"], (0, 0))
        tin = sum(x.get("in") or 0 for x in usage); tout = sum(x.get("out") or 0 for x in usage)
        n_docs = max(1, sum(1 for x in res if x["job"]["state"] in ("ready", "partial")))
        frames = [x for x in j["rows"] if x["kind"] == "frame" and "error" not in x["verdict"]]
        row = {
            "arm": f"{run} ({r.get('pipeline', 'classic')}, gate {r.get('gate', 'off')})",
            "docs ok": f"{n_docs}/{len(res)}",
            "units": len(units),
            "level 1 s (median)": statistics.median([x["seconds_to_level1"] for x in res if x.get("seconds_to_level1")] or [0]),
            "done s (median)": statistics.median([x["seconds_to_done"] for x in res if x.get("seconds_to_done")] or [0]),
            "USD / doc (gen)": round((tin / 1e6 * pi + tout / 1e6 * po) / n_docs, 2),
            **{c: m([u["verdict"].get(c) for u in units]) for c in CRIT},
            "units with a wrong answer": f"{sum(1 for u in units if u['verdict'].get('wrong_parts'))}/{len(units)}",
            "wrong parts / parts": f"{wrong}/{parts}" + (f" ({round(100 * wrong / parts, 1)}%)" if parts else ""),
            "factual_error flags": sum(1 for u in units if u["verdict"].get("factual_error")),
            "source conflicts": sum(1 for u in units if u["verdict"].get("source_conflict")),
            "leaks": sum(1 for u in units if u["verdict"].get("answer_leaked_in_prompt")),
            "frame checks (mean)": m([v for f in frames for v in f["verdict"].get("checks", {}).values()]),
        }
        stats = re.findall(r"layered stats (\{.*\})", log)
        if stats:
            row["layered stats (sum)"] = summarize_stats(stats)
        row["gate lines"] = len(re.findall(r"gate", log, re.I))
        rows.append(row)
    keys = list(dict.fromkeys(k for r in rows for k in r))
    md = ["| metric | " + " | ".join(r["arm"] for r in rows) + " |", "|---" * (len(rows) + 1) + "|"]
    for k in keys[1:]:
        md.append(f"| {k} | " + " | ".join(str(r.get(k, "")) for r in rows) + " |")
    text = "\n".join(md)
    open(os.path.join(HERE, "out", "compare-" + "-vs-".join(runs) + ".md"), "w").write(text + "\n")
    print(text)


def summarize_stats(lines):
    tot = {}
    for l in lines:
        try:
            d = json.loads(l)
        except Exception:
            continue
        for k, v in d.items():
            if isinstance(v, (int, float)):
                tot[k] = tot.get(k, 0) + v
    return ", ".join(f"{k} {v}" for k, v in tot.items())


if __name__ == "__main__":
    main(sys.argv[1:])
