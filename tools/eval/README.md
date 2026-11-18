# tools/eval — generation-quality harness (eval tooling, not product code)

Runs real documents through the **server as shipped** and scores the output with an independent judge model. Results of the first run: [docs/testing/eval-2026-11-18.md](../../docs/testing/eval-2026-11-18.md).

| File | Role |
|---|---|
| `bedrock_proxy.py` | loopback-only OpenAI-compatible shim → Amazon Bedrock Converse; logs tokens/latency per call (no content) |
| `run_eval.py` | starts proxy + server on 127.0.0.1, runs each document: install → upload → compile → poll → package → 4 frames; saves artefacts to `out/<run>/` |
| `judge.py` | independent judge (default GPT-5.6 Terra) scores units, frames and coverage → `out/<run>/judge.json` |
| `report.py` | `out/<run>/report.md` |
| `corpus.json` | documents and their sources; the files themselves are downloaded into `corpus/` (gitignored, not redistributed) |

```sh
cd tools/eval && python3 -m venv .venv && .venv/bin/pip install boto3
A2G_SERVER_ONLY=1 ../../gradlew -p ../.. :server:installDist
# credentials: an AWS profile with Bedrock access, via the SDK chain only (never exported or written to files)
AWS_PROFILE=<profile> .venv/bin/python run_eval.py --model us.anthropic.claude-sonnet-5 --run <name>
AWS_PROFILE=<profile> .venv/bin/python judge.py --run <name>
.venv/bin/python report.py <name>
```

Known harness gap: the default `case` frame is generated after the job reports `ready`; `run_eval.py` fetches it too early (copy it from `out/<run>/data/frames/` or POST it like the other frames).
