#!/usr/bin/env python3
"""Blind, stratified sample of units from several runs for two human raters. EVAL TOOLING ONLY.
Writes out/human-<tag>/sheet.md (no arm shown, shuffled) and out/human-<tag>/key.json (arm of each item; keep away from raters).
Raters mark each item: answer correct per the passages? (yes / no / source unclear), and note the wrong part.
Usage: sample_for_humans.py --tag ab1 --per-arm 20 <run-A> <run-B> [...]"""
import argparse, json, os, random

HERE = os.path.dirname(os.path.abspath(__file__))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--tag", required=True)
    ap.add_argument("--per-arm", type=int, default=20)
    ap.add_argument("--seed", type=int, default=7)
    ap.add_argument("runs", nargs="+")
    a = ap.parse_args()
    rnd = random.Random(a.seed)
    items = []
    for run in a.runs:
        out = os.path.join(HERE, "out", run)
        pool = []
        for doc in sorted(os.listdir(out)):
            p = os.path.join(out, doc, "package.json")
            if not os.path.isfile(p):
                continue
            pkg = json.load(open(p))
            chunks = {c["chunkId"]: c for c in pkg["sources"]}
            for lvl in pkg["levels"]:
                for u in lvl["units"]:
                    ids = sorted({r["chunkId"] for r in json.loads(json.dumps(u["item"])).get("sourceRefs", [])} |
                                 {r["chunkId"] for c in u["item"].get("cards", []) for r in c.get("sourceRefs", [])})
                    pool.append({"run": run, "doc": doc, "unitId": u["unitId"], "mechanic": u["mechanic"], "item": u["item"],
                                 "passages": [f"(p.{chunks[i].get('pageLabel')}) {chunks[i]['text']}" for i in ids if i in chunks]})
        by_mech = {}
        for x in pool:
            by_mech.setdefault(x["mechanic"], []).append(x)
        picked = []
        while len(picked) < min(a.per_arm, len(pool)):   # round-robin over mechanics = stratified
            for mech in sorted(by_mech):
                if by_mech[mech] and len(picked) < a.per_arm:
                    picked.append(by_mech[mech].pop(rnd.randrange(len(by_mech[mech]))))
        items += picked
    rnd.shuffle(items)
    d = os.path.join(HERE, "out", "human-" + a.tag)
    os.makedirs(d, exist_ok=True)
    key, md = [], [f"# Blind check `{a.tag}` — {len(items)} items", "", "For each item: is every MARKED answer right according to the passages only? Write **yes**, **no (which part)** or **source unclear**.", ""]
    for i, x in enumerate(items, 1):
        key.append({"n": i, "run": x["run"], "doc": x["doc"], "unitId": x["unitId"]})
        md += [f"## {i}. {x['mechanic']}", "", "```json", json.dumps(x["item"], ensure_ascii=False, indent=1), "```", "", "Passages:", ""]
        md += [f"> {p}" for p in x["passages"]] + ["", "Rater A: ____   Rater B: ____", ""]
    open(os.path.join(d, "sheet.md"), "w").write("\n".join(md))
    json.dump(key, open(os.path.join(d, "key.json"), "w"), indent=1)
    print("wrote", os.path.join(d, "sheet.md"), len(items), "items")


if __name__ == "__main__":
    main()
