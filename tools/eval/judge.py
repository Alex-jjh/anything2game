#!/usr/bin/env python3
"""Scores a run's artefacts with an independent judge model (different family from the generator). EVAL TOOLING ONLY.

Per unit: grounded, unambiguous, important, feedback, mechanic_fit, mobile_clarity (1-5) + flags.
Per frame overlay: frame-specific checks (boss correctness, no invented facts, no answer giveaways, character consistency).
Per document: coverage of the material's key ideas.
Writes out/<run>/judge.json and out/<run>/report.md.
Usage: judge.py --run sonnet5-r1 [--judge us.openai.gpt-5.6-terra]
"""
import argparse, json, os, re, statistics, time
from concurrent.futures import ThreadPoolExecutor

import boto3
from botocore.config import Config

HERE = os.path.dirname(os.path.abspath(__file__))
REGION = {"us.anthropic.": "us-west-2", "us.openai.": "us-east-2", "global.openai.": "us-east-2"}
USAGE = []


def client(model):
    region = next((r for p, r in REGION.items() if model.startswith(p)), "us-west-2")
    return boto3.Session(region_name=region).client("bedrock-runtime", config=Config(retries={"max_attempts": 6, "mode": "adaptive"}, read_timeout=300))


def ask(cl, model, system, user, max_tokens=1500):
    for attempt in range(3):
        r = cl.converse(modelId=model, system=[{"text": system}], messages=[{"role": "user", "content": [{"text": user}]}],
                        inferenceConfig={"maxTokens": max_tokens})
        USAGE.append(r.get("usage", {}))
        text = "".join(b.get("text", "") for b in r["output"]["message"]["content"])
        m = re.search(r"\{.*\}", text, re.S)
        if m:
            try:
                return json.loads(m.group(0))
            except json.JSONDecodeError:
                pass
    return {"error": "judge returned no JSON"}


SYS = ("You are a strict reviewer of learning games generated from study material. You check generated items against the "
       "cited source passages ONLY (not your own knowledge). Be critical; a 5 means you would ship it unchanged to students. "
       "Answer with one JSON object, no prose outside it.")

UNIT_RUBRIC = """Score this generated unit from 1 (bad) to 5 (excellent) on each criterion and list concrete problems.
- grounded: every answer/verdict/feedback is supported by the CITED passages (not just plausible). 1 = a stated answer is unsupported or wrong.
- unambiguous: exactly one defensible answer per decision (for swipe: each verdict; for sort: each card; for deduce: one option; for order: one order).
- important: tests a key idea of the material, not trivia or wording.
- feedback: explanations say WHY, and address the specific misconception of wrong choices.
- mechanic_fit: the mechanic (swipe/sort/deduce/order) suits this knowledge.
- mobile_clarity: short, clear, readable on a phone; written in the material's language ({language}).
Also set booleans: factual_error, answer_leaked_in_prompt (the ALWAYS-VISIBLE text — prompt, scenario, card/option/step text, bin labels — gives the answer away), answer_leaked_in_hints (only the optional hints give it away; this is acceptable by design), implausible_distractors, source_conflict (the passages THEMSELVES disagree on a fact this unit uses — not the generator's fault).
List wrong_parts: ids of cards/options/steps whose MARKED answer is wrong according to the passages (e.g. a swipe card marked supported that the passage contradicts; the deduce option marked correct when another is right; a sort card in the wrong bin; for order use "order"). Empty list if all marked answers are right.
Return: {{"grounded":n,"unambiguous":n,"important":n,"feedback":n,"mechanic_fit":n,"mobile_clarity":n,"factual_error":b,"answer_leaked_in_prompt":b,"answer_leaked_in_hints":b,"implausible_distractors":b,"source_conflict":b,"wrong_parts":["..."],"parts_total":n,"problems":["..."]}}

LEVEL GOAL: {goal}
UNIT (JSON): {unit}
CITED PASSAGES:
{passages}"""

FRAME_RUBRIC = {
    "case": "Case frame: a partner briefs; 'the Summary' is a SUSPECT character who is SUPPOSED to overclaim — its lines and its one flawed boss statement are intentional overclaims and must NOT count against lines_faithful (judge only the partner/narration lines and the non-flawed statements for faithfulness). The cross-examination boss has statements, exactly ONE overclaims beyond the evidence, and its cited passages must DISPROVE it while the other statements must be supported. Check: boss_correct (flawed one truly overclaims, cited passage shows it, others truly supported), lines_faithful (dialogue adds no facts beyond the material), no_giveaway (intro/lines don't reveal unit answers), board_sensible (node labels and edges reflect real relations).",
    "escape": "Escape frame: a narrator, rooms grouping units, a final lock (order or deduce). Check: boss_correct (the lock's correct answer matches the material and is unambiguous), lines_faithful, no_giveaway, rooms_sensible (room themes match their units).",
    "rookie": "Rookie frame: a junior asks a lead-in question before each unit; boss = the rookie's notes as cross-examination with exactly ONE wrong line, disproved by its cited passage. Check: boss_correct, lines_faithful, no_giveaway (lead-ins ask, never answer), character_consistent.",
    "companion": "Companion frame: a partner gives 1-2 cited lead-in lines before each unit; no boss. Check: lines_faithful (cited lines are supported by their passages), no_giveaway (lead-ins must not reveal the unit's answer), helpful (lead-ins orient the player), character_consistent.",
}
FRAME_PROMPT = """{rubric}
Score each check 1-5 and list concrete problems (quote the offending text).
Return: {{"checks":{{"<check>":n,...}},"problems":["..."]}}
Material language: {language}
UNITS (for giveaway checks): {units}
OVERLAY (JSON): {overlay}
PASSAGES (all chunks, id: text): {passages}"""

COVERAGE_PROMPT = """Below is a study document split into passages, and the list of units a game generator made from it.
1) List the 6-8 most important ideas a student must take away from THIS document.
2) For each idea say whether some unit tests it (covered: true/false).
3) Score coverage 1-5 and note anything the units over-focus on.
Return: {{"ideas":[{{"idea":"...","covered":b}}],"coverage":n,"notes":"..."}}
DOCUMENT PASSAGES:
{passages}
UNITS (prompt/statements only):
{units}"""


def unit_brief(u):
    it = u["item"]
    t = it["type"]
    if t == "swipe": return [c["statement"] for c in it["cards"]]
    if t == "sort": return [it["prompt"]] + [c["text"] for c in it["cards"]]
    if t == "deduce": return [it["scenario"], it["prompt"]]
    if t == "order": return [it["prompt"]] + [s["text"] for s in it["steps"]]
    return [json.dumps(it, ensure_ascii=False)[:300]]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--run", required=True)
    ap.add_argument("--judge", default="us.openai.gpt-5.6-terra")
    a = ap.parse_args()
    out = os.path.join(HERE, "out", a.run)
    cl = client(a.judge)
    docs = sorted(d for d in os.listdir(out) if os.path.isfile(os.path.join(out, d, "package.json")))
    jobs = []
    for d in docs:
        pkg = json.load(open(os.path.join(out, d, "package.json")))
        chunks = {c["chunkId"]: c for c in pkg["sources"]}
        lang = pkg["language"]
        for lvl in pkg["levels"]:
            for u in lvl["units"]:
                ids = sorted(set(re.findall(r'"chunkId":\s*"([^"]+)"', json.dumps(u))))
                passages = "\n".join(f"[{i}] (page {chunks[i].get('pageLabel')}) {chunks[i]['text']}" for i in ids if i in chunks)
                jobs.append(("unit", d, u["unitId"], u["mechanic"], UNIT_RUBRIC.format(language=lang, goal=lvl["goal"],
                             unit=json.dumps(u, ensure_ascii=False), passages=passages)))
        allp = "\n".join(f"{c['chunkId']}: {c['text']}" for c in pkg["sources"])
        units = json.dumps({u["unitId"]: unit_brief(u) for l in pkg["levels"] for u in l["units"]}, ensure_ascii=False)
        jobs.append(("coverage", d, "-", "-", COVERAGE_PROMPT.format(passages=allp[:120000], units=units)))
        for f in ("case", "escape", "rookie", "companion"):
            p = os.path.join(out, d, f"frame.{f}.json")
            if os.path.exists(p):
                jobs.append(("frame", d, f, f, FRAME_PROMPT.format(rubric=FRAME_RUBRIC[f], language=lang, units=units,
                             overlay=open(p).read(), passages=allp[:120000])))
    t0 = time.time()
    with ThreadPoolExecutor(6) as pool:
        res = list(pool.map(lambda j: (j, ask(cl, a.judge, SYS, j[4], 2500 if j[0] != "unit" else 1500)), jobs))
    rows = [{"kind": j[0], "doc": j[1], "id": j[2], "mechanic": j[3], "verdict": v} for j, v in res]
    # GPT on Bedrock reports most input as cacheWriteInputTokens; count total - output as input.
    usage = {"in": sum(u.get("totalTokens", 0) - u.get("outputTokens", 0) for u in USAGE), "out": sum(u.get("outputTokens", 0) for u in USAGE)}
    json.dump({"judge": a.judge, "rubric": "v2", "seconds": round(time.time() - t0), "usage": usage, "rows": rows},
              open(os.path.join(out, "judge.json"), "w"), ensure_ascii=False, indent=2)
    print("judged", len(rows), "items in", round(time.time() - t0), "s; judge tokens", usage)


if __name__ == "__main__":
    main()
