#!/usr/bin/env python3
"""Pre-generate a tutor script (diagnostic + explanations) for a preset course, with a real model on Bedrock.

Tooling, not product code: run locally, review the output by a person, then commit it next to the package as
samples/demo/tutor.demo.<name>.json (same shape as contract/examples/tutor.v1.example.json). PackTool puts it in the
course pack (ADR-017). Credentials come only from the SDK chain (AWS_CONFIG_FILE / AWS_PROFILE); none is read or written.

usage: make_tutor_script.py <package.json> <out.json> [--model us.anthropic.claude-sonnet-5] [--concepts 4]
"""
import argparse, json, re, sys, boto3
from botocore.config import Config

FACETS = ["background", "method", "result", "limitation"]

PROMPT = """You write the short diagnostic + explanation script for one course in a phone study app.
The app first asks {n} quick multiple-choice questions (the player may answer "I don't know"), then a character explains
each concept, starting from what the player missed. Ground EVERYTHING in the source passages below.

Return ONLY JSON: {{"concepts": [ ... {n} items ... ]}}, each item:
{{
  "facet": one of {facets} (what kind of knowledge this concept is: background/motivation, method, result, limitation),
  "question": {{
    "id": "q1".., "conceptId": short-kebab-id, "concept": short concept name ({lang_name}),
    "prompt": one clear question ({lang_name}, <= 25 words),
    "options": [{{"id": "a", "text": ...}}, {{"id": "b", ...}}, {{"id": "c", ...}}]  (3 options, <= 16 words each, one correct, two plausible misreadings),
    "sourceRefs": [{{"chunkId": <id from the passages>, "quote": <EXACT verbatim substring of that passage, 8-30 words>}}]
  }},
  "correctOptionId": "a"|"b"|"c",
  "explanation": 2-3 sentences ({lang_name}) a friendly explainer would say, what the reading says and why it matters; no new facts,
  "simpler": 1-2 sentences, the same idea said more plainly,
  "example": 1-2 sentences: a concrete illustration; if it is invented, say so ("A made-up example: ...")
}}
Rules: cover the course's levels in order ({levels}); spread facets (at least one method and one result if the reading has them);
quotes must be copied character for character from the passages; never invent numbers; plain, adult tone.

Passages (chunkId | page | text):
{passages}
"""

def norm(s): return re.sub(r"\s+", " ", s).strip()

def validate(pkg, concepts):
    chunks = {c["chunkId"]: norm(c["text"]) for c in pkg["sources"]}
    problems = []
    seen = set()
    for i, c in enumerate(concepts):
        q = c["question"]
        if c.get("facet") not in FACETS: problems.append(f"{i}: facet {c.get('facet')}")
        ids = [o["id"] for o in q["options"]]
        if c["correctOptionId"] not in ids or len(set(ids)) != len(ids) or len(ids) < 2: problems.append(f"{i}: options")
        if q["conceptId"] in seen: problems.append(f"{i}: duplicate conceptId")
        seen.add(q["conceptId"])
        if not q["sourceRefs"]: problems.append(f"{i}: no sourceRefs")
        for r in q["sourceRefs"]:
            if r["chunkId"] not in chunks or norm(r["quote"]) not in chunks[r["chunkId"]]:
                problems.append(f"{i}: quote not verbatim in {r['chunkId']}: {r['quote'][:60]}")
        for k in ("explanation", "simpler", "example"):
            if not c.get(k, "").strip(): problems.append(f"{i}: empty {k}")
    return problems

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("package"); ap.add_argument("out")
    ap.add_argument("--model", default="us.anthropic.claude-sonnet-5")
    ap.add_argument("--concepts", type=int, default=4)
    ap.add_argument("--region", default="us-west-2")
    a = ap.parse_args()
    pkg = json.load(open(a.package))
    zh = pkg["language"].startswith("zh")
    passages = "\n".join(f"{c['chunkId']} | p.{c.get('pageLabel') or c['pageIndex'] + 1} | {norm(c['text'])}" for c in pkg["sources"])
    prompt = PROMPT.format(n=a.concepts, facets=FACETS, lang_name="Chinese" if zh else "English",
                           levels="; ".join(l["title"] for l in pkg["levels"]), passages=passages)
    client = boto3.Session(region_name=a.region).client("bedrock-runtime", config=Config(read_timeout=300, retries={"max_attempts": 4, "mode": "adaptive"}))
    messages = [{"role": "user", "content": [{"text": prompt}]}]
    for attempt in range(3):
        r = client.converse(modelId=a.model, messages=messages, inferenceConfig={"maxTokens": 6000})
        text = next(b["text"] for b in r["output"]["message"]["content"] if "text" in b)
        m = re.search(r"\{.*\}", text, re.S)
        try:
            concepts = json.loads(m.group(0))["concepts"]
        except Exception as e:
            problems = [f"not JSON: {e}"]; concepts = []
        else:
            problems = validate(pkg, concepts)
        if not problems: break
        print(f"attempt {attempt + 1}: {len(problems)} problem(s): {problems[:4]}", file=sys.stderr)
        messages += [{"role": "assistant", "content": [{"text": text}]},
                     {"role": "user", "content": [{"text": "Fix these problems and return the full JSON again:\n" + "\n".join(problems)}]}]
    else:
        sys.exit("could not produce a valid script")
    for c in concepts:
        c["question"]["facet"] = c.pop("facet")
    script = {"packageId": pkg["packageId"], "contentVersion": pkg["contentVersion"], "documentVersion": pkg["documentVersion"],
              "blueprint": {"schema": "a2g.tutor/draft-v1", "concepts": concepts},
              "generation": {"mode": "pre-generated", "model": a.model, "promptVersion": "tutor-script.v1", "review": "needs human review"}}
    json.dump(script, open(a.out, "w"), ensure_ascii=False, indent=1)
    print(f"wrote {a.out}: {len(concepts)} concepts; usage {r.get('usage')}")

if __name__ == "__main__":
    main()
