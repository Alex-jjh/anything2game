#!/usr/bin/env python3
"""Wrong-answer rate by agreement of two judges from different model families. EVAL TOOLING ONLY.
A unit counts as WRONG only if both judges list at least one wrong part for it; 'either' gives the upper bound.
Usage: consensus.py <name>=<run judged by judge 1>,<same artefacts judged by judge 2> ..."""
import json, sys


def load(run):
    j = json.load(open(f"out/{run}/judge.json"))
    return j["judge"], {(r["doc"], r["id"]): r["verdict"] for r in j["rows"] if r["kind"] == "unit" and "error" not in r["verdict"]}


print("| arm | judges | units | wrong by both | wrong by either | judge 1 only | judge 2 only | grounded (j1 / j2) |")
print("|---|---|---|---|---|---|---|---|")
for arg in sys.argv[1:]:
    name, runs = arg.split("=")
    r1, r2 = runs.split(",")
    j1, a = load(r1); j2, b = load(r2)
    keys = sorted(set(a) & set(b))
    wa = {k for k in keys if a[k].get("wrong_parts")}; wb = {k for k in keys if b[k].get("wrong_parts")}
    g = lambda d: round(sum((d[k].get("grounded") or 0) for k in keys) / max(len(keys), 1), 2)
    print(f"| {name} | {j1.split('.')[-1]} + {j2.split('.')[-1]} | {len(keys)} | {len(wa & wb)} ({100 * len(wa & wb) / max(len(keys), 1):.1f} %) | {len(wa | wb)} | {len(wa - wb)} | {len(wb - wa)} | {g(a)} / {g(b)} |")
