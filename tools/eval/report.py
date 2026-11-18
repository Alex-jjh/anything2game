#!/usr/bin/env python3
"""Turns a run (artefacts + server log + proxy usage + judge verdicts) into out/<run>/report.md. EVAL TOOLING ONLY."""
import collections, json, os, re, statistics, sys

HERE = os.path.dirname(os.path.abspath(__file__))
# USD per million tokens (input, output). Bedrock pricing differs from first-party; update from the Bedrock pricing page.
PRICE = {"us.anthropic.claude-sonnet-5": (2.0, 10.0), "us.openai.gpt-5.6-terra": (1.25, 10.0)}


def mean(xs):
    xs = [x for x in xs if isinstance(x, (int, float))]
    return round(statistics.mean(xs), 2) if xs else None


def main(run):
    out = os.path.join(HERE, "out", run)
    runj = json.load(open(os.path.join(out, "run.json")))
    log = open(os.path.join(out, "server.log"), errors="replace").read()
    usage = [json.loads(l) for l in open(os.path.join(out, "usage.jsonl"))] if os.path.exists(os.path.join(out, "usage.jsonl")) else []
    judge = json.load(open(os.path.join(out, "judge.json"))) if os.path.exists(os.path.join(out, "judge.json")) else {"rows": []}
    L = [f"# Eval run `{run}` — generator `{runj['model']}`, judge `{judge.get('judge', '-')}`", ""]

    # ---- pipeline health ----
    L += ["## Pipeline", "", "| Doc | Genre | Result | Level 1 (s) | Done (s) | Levels | Units | Mechanics | Variants | Frames |", "|---|---|---|---|---|---|---|---|---|---|"]
    for r in runj["results"]:
        pkg_p = os.path.join(out, r["id"], "package.json")
        if not os.path.exists(pkg_p):
            err = (r.get("upload") or {}).get("error", {}) if isinstance(r.get("upload"), dict) else {}
            L.append(f"| {r['id']} | {r['genre']} | {r.get('result') or r['job']['state']} `{err.get('code', '')}` | | | | | | | |")
            continue
        pkg = json.load(open(pkg_p))
        units = [u for l in pkg["levels"] for u in l["units"]]
        mech = collections.Counter(u["mechanic"] for u in units)
        var = sum(len(u.get("variants", [])) for u in units)
        frames = " ".join(f"{k}:{'✓' if v == 200 else v}" for k, v in (r.get("frames") or {}).items())
        L.append(f"| {r['id']} | {r['genre']} | {r['job']['state']} | {r.get('seconds_to_level1')} | {r.get('seconds_to_done')} | {len(pkg['levels'])} | {len(units)} | "
                 + ", ".join(f"{k}×{v}" for k, v in mech.items()) + f" | {var} | {frames} |")
    rep = re.findall(r"level (L\d+): repaired=(\w+) droppedUnits=(\d+)", log)
    L += ["", f"Levels validated: {len(rep)}; needed one repair call: {sum(1 for _, x, _ in rep if x == 'true')}; units dropped after repair: {sum(int(d) for *_, d in rep)}.",
          "Level failures: %d; default-frame failures: %d." % (len(re.findall(r"level L\d+ failed", log)), len(re.findall(r"default frame \w+ failed", log))), ""]

    # ---- cost ----
    def cost(model, rows):
        pi, po = PRICE.get(model, (0, 0))
        tin = sum(x.get("in") or 0 for x in rows); tout = sum(x.get("out") or 0 for x in rows)
        return tin, tout, round(tin / 1e6 * pi + tout / 1e6 * po, 2)
    gi, go, gc = cost(runj["model"], usage)
    calls = len(usage); errs = sum(1 for x in usage if "error" in x)
    lat = [x["ms"] for x in usage if "ms" in x and "error" not in x]
    ju = judge.get("usage", {})
    jc = round(ju.get("in", 0) / 1e6 * PRICE.get(judge.get("judge"), (0, 0))[0] + ju.get("out", 0) / 1e6 * PRICE.get(judge.get("judge"), (0, 0))[1], 2)
    L += ["## Cost and latency", "", f"Generator: {calls} calls ({errs} errors), {gi:,} in / {go:,} out tokens ≈ USD {gc}; median call {statistics.median(lat) / 1000 if lat else '-'} s, max {max(lat) / 1000 if lat else '-'} s.",
          f"Judge: {ju.get('in', 0):,} in / {ju.get('out', 0):,} out ≈ USD {jc}.", ""]
    by_step = collections.defaultdict(list)
    for x in usage:
        by_step[x.get("step", "?")[:50]].append(x)
    L += ["| Step (prompt) | Calls | In tok (avg) | Out tok (avg) | Avg s |", "|---|---|---|---|---|"]
    for s, xs in sorted(by_step.items(), key=lambda kv: -len(kv[1])):
        L.append(f"| {s} | {len(xs)} | {mean([x.get('in') for x in xs])} | {mean([x.get('out') for x in xs])} | {mean([x.get('ms', 0) / 1000 for x in xs])} |")
    L.append("")

    # ---- judge ----
    units = [r for r in judge["rows"] if r["kind"] == "unit" and "error" not in r["verdict"]]
    if units:
        crit = ["grounded", "unambiguous", "important", "feedback", "mechanic_fit", "mobile_clarity"]
        L += ["## Unit quality (judge, 1–5)", "", "| Doc | Units | " + " | ".join(crit) + " | factual errors | leaks |", "|---" * (len(crit) + 4) + "|"]
        for d in sorted({r["doc"] for r in units}) + ["ALL"]:
            rs = [r for r in units if d == "ALL" or r["doc"] == d]
            L.append(f"| {d} | {len(rs)} | " + " | ".join(str(mean([r['verdict'].get(c) for r in rs])) for c in crit)
                     + f" | {sum(1 for r in rs if r['verdict'].get('factual_error'))} | {sum(1 for r in rs if r['verdict'].get('answer_leaked_in_prompt'))} |")
        L += ["", "| Mechanic | Units | grounded | unambiguous | important | feedback |", "|---|---|---|---|---|---|"]
        for m in sorted({r["mechanic"] for r in units}):
            rs = [r for r in units if r["mechanic"] == m]
            L.append(f"| {m} | {len(rs)} | " + " | ".join(str(mean([r['verdict'].get(c) for r in rs])) for c in crit[:4]) + " |")
        L += ["", "### Lowest-scoring units", ""]
        worst = sorted(units, key=lambda r: sum(r["verdict"].get(c, 0) or 0 for c in crit))[:8]
        for r in worst:
            L.append(f"- **{r['doc']} / {r['id']} ({r['mechanic']})** — " + "; ".join(r["verdict"].get("problems", [])[:3]))
        L.append("")
    frames = [r for r in judge["rows"] if r["kind"] == "frame" and "error" not in r["verdict"]]
    if frames:
        L += ["## Frame quality (judge, 1–5)", "", "| Doc | Frame | Checks | Problems |", "|---|---|---|---|"]
        for r in frames:
            ch = ", ".join(f"{k} {v}" for k, v in r["verdict"].get("checks", {}).items())
            L.append(f"| {r['doc']} | {r['id']} | {ch} | " + "; ".join(r["verdict"].get("problems", [])[:2]).replace("|", "/") + " |")
        L.append("")
    cov = [r for r in judge["rows"] if r["kind"] == "coverage" and "error" not in r["verdict"]]
    if cov:
        L += ["## Coverage of key ideas", ""]
        for r in cov:
            ideas = r["verdict"].get("ideas", [])
            L.append(f"- **{r['doc']}**: {sum(1 for i in ideas if i.get('covered'))}/{len(ideas)} key ideas covered, score {r['verdict'].get('coverage')}. Missed: "
                     + "; ".join(i["idea"] for i in ideas if not i.get("covered")) + (f" — {r['verdict'].get('notes')}" if r["verdict"].get("notes") else ""))
    open(os.path.join(out, "report.md"), "w").write("\n".join(L) + "\n")
    print("\n".join(L))


if __name__ == "__main__":
    main(sys.argv[1])
