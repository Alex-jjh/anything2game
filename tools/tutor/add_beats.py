#!/usr/bin/env python3
"""Turn each concept of an existing tutor script into a scene: many short lines instead of one paragraph (paper2gal style).

Tooling, not product code. Keeps the diagnostic (questions, answers, explanation/simpler/example) exactly as reviewed and
adds `beats` to every concept, written with a real model on Bedrock; review the output by a person before filming.
Credentials come only from the SDK chain (AWS_CONFIG_FILE / AWS_PROFILE); none is read or written.

usage: add_beats.py <package.json> <tutor.json> [--out tutor.json] [--model us.anthropic.claude-sonnet-5]
"""
import argparse, json, re, sys, boto3
from botocore.config import Config

PROMPT = """You write one scene of a phone app where a character explains a reading to the player, the way a visual novel
would (many short lines, one per tap), not a paragraph.

The explainer is {explainer}: {voice}
The player has their own small companion character on stage; it may speak 1-2 lines per scene as the reader's own voice
(a natural question or a moment of confusion, never an answer). It has no fixed name: never name it, never name the explainer.

This scene teaches ONE concept: "{concept}" (question the player answered before: "{prompt}"; the answer was "{answer}").
What the scene must get across, already reviewed: {explanation}

Write {lo}-{hi} lines in {lang}. Each line:
{{"speaker": "explainer"|"player", "text": ..., "section": <where in the document this is, as the document itself names it
  (a heading or "Table 3", "Figure 2" as printed in the passages), or null>, "sourceRefs": [{{"chunkId": .., "quote": ..}}] or []}}
Optionally ONE line may also carry a quick check, only if it really helps the idea stick:
  "check": {{"prompt": .., "options": [{{"id":"a","text":..}},{{"id":"b",..}},{{"id":"c",..}}], "correctOptionId": .., "right": <one line when right>, "wrong": <one line when wrong, explaining the misreading>}}
  The line's own text then introduces the check (e.g. "Quick one before we go on.").

Rules:
- Explainer lines {line_len}; player lines shorter. One idea per line. Plain, warm, adult; no emoji; no stage directions.
- Point to where things are in the reading: name the section, table or figure when the passages show one ("Look at Table 2").
  "section" is ONLY a heading, table or figure label that literally appears in the passages (e.g. "3.2 Attention",
  "研究和应用", "Table 2"); otherwise null. Never make up labels like "the opening paragraph".
- Vary the wording; a slow-down line at most once, and only on a genuinely hard step.
- A check must test a different point from the question the player already answered.
- One everyday analogy at most, marked as an aside.
- The scene stands alone (scenes are shown in a personalised order): no "as we saw", no "next concept", no greeting.
- Ground everything in the passages. Never invent numbers, names or results. Every number you use must appear in a quote.
- sourceRefs: attach to the lines that state something from the reading, 1 ref per such line, quote = EXACT verbatim
  substring of that passage, 6-30 words. At least 3 lines must carry a ref.
- End on a line that says what to take away from this concept.
Return ONLY JSON: {{"beats": [ ... ]}}

Passages (chunkId | page | text):
{passages}
"""

VOICE = {
    "zh": ("阿雅", "a calm, warm reading guide with a light touch of playfulness. Talks to the player as 你. Short sentences. "
                  "May slow down on a hard step, in her own words. Never servile, never calls the player 主人."),
    "en": ("Aya", "a calm, warm reading guide with a light touch of playfulness. Talks to the player as you. Short sentences. "
                  "May slow down on a hard step, in her own words."),
}

def norm(s): return re.sub(r"\s+", " ", s).strip()

def validate(pkg, beats, lo, hi):
    chunks = {c["chunkId"]: norm(c["text"]) for c in pkg["sources"]}
    p = []
    if not (lo <= len(beats) <= hi + 2): p.append(f"{len(beats)} lines, expected {lo}-{hi}")
    refs = 0
    checks = 0
    for i, b in enumerate(beats):
        if b.get("speaker") not in ("explainer", "player"): p.append(f"{i}: speaker")
        t = b.get("text", "")
        if not t.strip() or len(t) > 400: p.append(f"{i}: text length {len(t)}")
        if b.get("section") is not None and (not str(b["section"]).strip() or len(b["section"]) > 60): p.append(f"{i}: section")
        for r in b.get("sourceRefs") or []:
            refs += 1
            if r.get("chunkId") not in chunks or norm(r.get("quote", "")) not in chunks[r["chunkId"]]:
                p.append(f"{i}: quote not verbatim in {r.get('chunkId')}: {r.get('quote', '')[:60]}")
        k = b.get("check")
        if k:
            checks += 1
            ids = [o["id"] for o in k.get("options", [])]
            if not (2 <= len(ids) <= 4) or k.get("correctOptionId") not in ids or not k.get("right") or not k.get("wrong") or not k.get("prompt"):
                p.append(f"{i}: check incomplete")
        for n in re.findall(r"\d+(?:\.\d+)?", t):
            if len(n) > 1 and not any(n in q for q in chunks.values()): p.append(f"{i}: number {n} not in the reading")
    if refs < 3: p.append(f"only {refs} lines carry a source")
    if checks > 1: p.append("more than one check")
    return p

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("package"); ap.add_argument("tutor"); ap.add_argument("--out")
    ap.add_argument("--model", default="us.anthropic.claude-sonnet-5")
    ap.add_argument("--region", default="us-west-2")
    a = ap.parse_args()
    pkg = json.load(open(a.package)); script = json.load(open(a.tutor))
    zh = pkg["language"].startswith("zh")
    explainer, voice = VOICE["zh" if zh else "en"]
    passages = "\n".join(f"{c['chunkId']} | p.{c.get('pageLabel') or c['pageIndex'] + 1} | {norm(c['text'])}" for c in pkg["sources"])
    lo, hi = 6, 10
    client = boto3.Session(region_name=a.region).client("bedrock-runtime", config=Config(read_timeout=300, retries={"max_attempts": 4, "mode": "adaptive"}))
    for c in script["blueprint"]["concepts"]:
        q = c["question"]
        answer = next(o["text"] for o in q["options"] if o["id"] == c["correctOptionId"])
        prompt = PROMPT.format(explainer=explainer, voice=voice, concept=q["concept"], prompt=q["prompt"], answer=answer,
                               explanation=c["explanation"], lo=lo, hi=hi, lang="Chinese" if zh else "English",
                               line_len="20-70 Chinese characters" if zh else "10-35 words", passages=passages)
        messages = [{"role": "user", "content": [{"text": prompt}]}]
        for attempt in range(4):
            r = client.converse(modelId=a.model, messages=messages, inferenceConfig={"maxTokens": 5000})
            text = "".join(b.get("text", "") for b in r["output"]["message"]["content"]) or "(empty reply)"
            m = re.search(r"\{.*\}", text, re.S)
            try: beats = json.loads(m.group(0))["beats"]
            except Exception as e: problems, beats = [f"not JSON: {e}"], []
            else: problems = validate(pkg, beats, lo, hi)
            if not problems: break
            print(f"{q['id']} attempt {attempt + 1}: {problems[:5]}", file=sys.stderr)
            messages += [{"role": "assistant", "content": [{"text": text}]},
                         {"role": "user", "content": [{"text": "Fix these problems and return the full JSON again:\n" + "\n".join(problems)}]}]
        else:
            sys.exit(f"could not write a valid scene for {q['id']}")
        for b in beats:
            if not b.get("section"): b.pop("section", None)
            if not b.get("sourceRefs"): b.pop("sourceRefs", None)
            if not b.get("check"): b.pop("check", None)
        c["beats"] = beats
        print(f"{q['id']} {q['concept']}: {len(beats)} lines; usage {r.get('usage')}")
    script.setdefault("generation", {})["beats"] = {"model": a.model, "promptVersion": "tutor-beats.v1", "review": "needs human review"}
    json.dump(script, open(a.out or a.tutor, "w"), ensure_ascii=False, indent=1)

if __name__ == "__main__":
    main()
