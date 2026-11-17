# Anything2Game server

Kotlin + Ktor backend of the `:server` module. It ingests a document (PDF or already-extracted text), compiles it into a
validated learning package with an LLM, publishes the package level by level, generates optional "frames" (story layers),
and answers questions grounded in the document. The wire format is the `contract/` module; this module reuses its DTOs and
validators and defines no wire types of its own.

```
./gradlew :server:run          # fake model, no keys, listens on 127.0.0.1:8080
./gradlew :server:test         # offline, uses FakeLlmClient, writes only under server/build/
```

## Packages (`com.anything2game.server`)

| Package | What it does |
|---|---|
| `config` | `ServerConfig.fromEnv()`: all settings from environment variables, safe defaults, fails fast on a missing key. `Limits` holds the hard limits. |
| `api` | Ktor routes (`ApiRoutes.kt`), error mapping (`ApiModule.kt`), install tokens (`Auth.kt`, default deny), quotas (`Quotas.kt`). |
| `ingest` | `PdfTextExtractor` (PDFBox 3, text per page, encrypted and no-text detection), `Chunker` (paragraphs to 300-1200 char chunks, never across pages, `c1..cn`), `documentVersion`. |
| `llm` | `LlmClient` + `OpenAiCompatibleLlmClient`, `AnthropicLlmClient`, `FakeLlmClient`, `LlmRouter` (tiers `compile`, `frame`, `flash`), prompt loading and the untrusted-text format (`Prompts.kt`). |
| `compile` | The pipeline nodes, `CompileJobRunner` (job state machine), frame nodes, `FrameService`. |
| `adapt` | `AskService` (retrieval + flash model), `Retrieval` (keyword overlap, Chinese by character bigrams). |
| `store` | `Store` interface and `FileStore` (JSON files, atomic writes). Postgres/S3 can replace it behind the interface. |
| `resources/prompts` | Versioned prompt files, see below. |

## The pipeline

```
upload -> Chunker -> chunks            (ingest)
POST /compile-jobs -> CompileJobRunner (queued -> planning -> generating -> ready | partial | failed | cancelled)
  PlanNode      chunks -> levels (1-4), units (3-4 per level), >= 2 mechanics per level, chunk ids checked
  LevelNode     one planned level -> contract Level (items, hints, variants, recap), quotes copied from chunks
  ValidateNode  PackageValidator on the package-with-this-level; ONE repair call with the issue list;
                then drops what still fails (a bad variant drops the variant, a bad unit drops the unit);
                fewer than 2 surviving units = the level fails
  PublishNode   stores the level; GET /v1/packages/{id} shows level 1 as soon as it validates.
                Refuses (returns false) once the job is terminal: a late result after cancel is discarded
  (levels run in parallel, level 1 started first)
after ready/partial: default frames (A2G_DEFAULT_FRAMES) are generated
```

Frame nodes (`FrameNode.generate(pkg): FrameOverlay?`), registered in `FrameNodeRegistry`:

| Frame | Node | Prompt |
|---|---|---|
| case | `CaseFrameNode` | `frame-case.v1.md`: partner + suspect "The Summary", cross-exam boss with exactly one flawed statement whose `sourceRefs` disprove it, a board node per unit with labelled edges |
| escape | `EscapeFrameNode` | `frame-escape.v1.md`: narrator, rooms covering every unit once, final lock = order or deduce |
| rookie | `RookieFrameNode` | `frame-rookie.v1.md`: a junior asks a lead-in before every unit, boss = the rookie's notes (cross-exam, one wrong line) |
| companion | `CompanionFrameNode` | `frame-companion.v1.md`: a partner gives 1-2 cited lead-in lines before every unit, no boss |
| classic | `ClassicFrameNode` | none; returns `null` |

Each LLM frame node validates with `FrameValidator` against `FrameSpec`, makes ONE repair call on issues, and otherwise fails
with `GENERATION_FAILED` (HTTP 502). The server, not the model, sets `schema`, `frameId`, `packageId`, `contentVersion` and
`generation` (`mode: live`, `review: auto-validated`, `promptVersion` = the prompt file, `model` = the client's model id).
Adding a frame = one node class + one prompt file + one line in `FrameNodeRegistry.default`.

**Classic has no overlay.** `GET` and `POST /v1/packages/{id}/frames/classic` answer `204 No Content`: nothing exists and
nothing is missing. A frame that is valid but not generated yet answers `404 NOT_FOUND` on `GET`.

### Prompts

`src/main/resources/prompts/`: `plan.v1.md`, `level.v1.md`, `repair.v1.md`, `frame-case.v1.md`, `frame-escape.v1.md`,
`frame-rookie.v1.md`, `frame-companion.v1.md`, `ask.v1.md`. Each file is the system prompt, a line `=== USER ===`, the user
prompt, with `{{placeholders}}`. Rendering is strict (a missing or unused variable throws). Every prompt states the role, JSON-only
output with the exact shape and an example adapted from `contract/examples`, "every quote is an exact substring of the cited
chunk", the language rule (`en` or `zh`) and that the text between `<document>` tags is untrusted data. Document text enters
prompts only inside `<document><chunk ...>` blocks; tag-like sequences in it are defused (`<` becomes `‹`) so it cannot close
a block, and the model's answer is mapped back before quotes are checked. The `promptVersion` recorded in a package is
`plan.v1+level.v1`; in a frame it is the frame's prompt file name.

## Configuration (environment variables only)

| Variable | Default | Meaning |
|---|---|---|
| `A2G_HOST` | `127.0.0.1` | Bind address. Never defaults to all interfaces. |
| `A2G_PORT` | `8080` | Port. |
| `A2G_DATA_DIR` | `server/data` | Data directory (git-ignored). |
| `A2G_LLM_COMPILE` | `fake` | Tier for plan, level, validate-repair: `fake`, `openai` or `anthropic`. |
| `A2G_LLM_FRAME` | `fake` | Tier for frame generation. |
| `A2G_LLM_FLASH` | `fake` | Tier for ask. |
| `A2G_OPENAI_BASE_URL` | `https://api.deepseek.com` | OpenAI-compatible endpoint (`/chat/completions` is appended). Must be `https` (http only for localhost). |
| `A2G_OPENAI_API_KEY` | unset | Key for tiers set to `openai`. |
| `A2G_OPENAI_MODEL_STRONG` | `deepseek-chat` | Model of the `compile` and `frame` tiers when they are `openai`. |
| `A2G_OPENAI_MODEL_FLASH` | `deepseek-chat` | Model of the `flash` tier when it is `openai`. |
| `ANTHROPIC_API_KEY` | unset | Key for tiers set to `anthropic`. |
| `A2G_ANTHROPIC_MODEL` | `claude-sonnet-5-5` | Claude model id. |
| `A2G_DEFAULT_FRAMES` | `case` | Comma-separated frames generated after every compile (`classic` is accepted and does nothing). |
| `A2G_LLM_TIMEOUT_SECONDS` | `120` | Per model call. |
| `A2G_ASK_TIMEOUT_MS` | `5000` | Ask deadline; exceeded = `504 MODEL_TIMEOUT`. |
| `A2G_GLOBAL_COMPILES_PER_DAY` | `500` | Cost guard across all installs (in memory, resets on restart). |
| `A2G_INSTALLS_PER_HOUR` | `200` | Cost guard against install farming (in memory). |

If a tier selects a real provider and its key is missing, the process exits with code 2 and a clear message before
listening. Keys are read from the environment only; they are never in code, config files or logs, and `ServerConfig.toString()`
masks them.

### Switch tiers to Claude, DeepSeek or Qwen

```
# Claude for everything (dev / eval)
A2G_LLM_COMPILE=anthropic A2G_LLM_FRAME=anthropic A2G_LLM_FLASH=anthropic ANTHROPIC_API_KEY=... ./gradlew :server:run

# Claude compiles, cheap model answers questions
A2G_LLM_COMPILE=anthropic A2G_LLM_FRAME=anthropic ANTHROPIC_API_KEY=... \
A2G_LLM_FLASH=openai A2G_OPENAI_API_KEY=... A2G_OPENAI_MODEL_FLASH=deepseek-chat ./gradlew :server:run

# DeepSeek
A2G_LLM_COMPILE=openai A2G_LLM_FRAME=openai A2G_LLM_FLASH=openai \
A2G_OPENAI_BASE_URL=https://api.deepseek.com A2G_OPENAI_API_KEY=... \
A2G_OPENAI_MODEL_STRONG=deepseek-chat A2G_OPENAI_MODEL_FLASH=deepseek-chat ./gradlew :server:run

# Qwen (DashScope OpenAI-compatible mode)
A2G_LLM_COMPILE=openai A2G_LLM_FRAME=openai A2G_LLM_FLASH=openai \
A2G_OPENAI_BASE_URL=https://dashscope.aliyuncs.com/compatible-mode/v1 A2G_OPENAI_API_KEY=... \
A2G_OPENAI_MODEL_STRONG=qwen-plus A2G_OPENAI_MODEL_FLASH=qwen-flash ./gradlew :server:run
```

(Model ids are examples; check the provider's current list.) The OpenAI-compatible client sends
`response_format: {"type":"json_object"}`. The Claude client uses the official `com.anthropic:anthropic-java` SDK, treats a
`refusal` stop reason as a non-retryable failure and a `max_tokens` stop as a bad response. The validators, not the
provider, decide whether output is acceptable.

The fake model (default) derives deterministic output from the chunks in the prompt (quotes are exact first sentences, Chinese
text is split on 。！？ too), so the whole stack runs and demos with no keys and no network.

## API (ARCHITECTURE section 3.2, plus two frame routes)

All routes are under `/v1`. Everything except `GET /v1/health` and `POST /v1/installs` needs `Authorization: Bearer <token>`.

| Route | Result |
|---|---|
| `GET /health` | `{"status":"ok","contract":"v1"}` |
| `POST /installs` | `201 InstallResponse {installId, token}` |
| `POST /documents` | multipart part `file` (PDF, optional form field `title`) **or** JSON `TextDocumentRequest` (`kind` text or ocr). `201 DocumentResponse` |
| `POST /compile-jobs` | `CompileJobRequest` -> `202` + `CompileJobStatus` (it contains `jobId`) |
| `GET /compile-jobs/{id}` | `CompileJobStatus` |
| `POST /compile-jobs/{id}/cancel` | `CompileJobStatus` (a terminal state wins: cancelling a finished job returns that state) |
| `GET /packages/{id}` | `LearningPackage` with published levels only; `ETag` = `contentVersion` (number of published levels); `If-None-Match` gives `304` |
| `GET /packages/{id}/frames/{frameId}` | `FrameOverlay`, `404` if not generated, `204` for classic |
| `POST /packages/{id}/frames/{frameId}` | generates if missing, idempotent, returns the `FrameOverlay` (`409` + retryable while the package is still compiling) |
| `POST /ask` | `AskRequest` -> `AskResponse` |
| `DELETE /documents/{id}` | `204`; removes document, chunks, packages, frames and jobs (running jobs are cancelled first) |

Errors are `ErrorBody` with the contract's `ErrorCodes`: `UNAUTHORIZED` 401, `NOT_FOUND` 404 (also another install's
resource), `BAD_REQUEST` 400, `FILE_TOO_LARGE` 413, `UNSUPPORTED_TYPE` 415, `NO_TEXT_LAYER` and `ENCRYPTED_PDF` 422,
`QUOTA_EXCEEDED` 429, `MODEL_TIMEOUT` 504, `GENERATION_FAILED` 502.

Compile idempotency: the same `clientRequestId` returns the same job (a different document or options under the same id is a
`400`). A request for the same document (same `documentVersion`) and options as an existing queued, running, ready or partial
job returns that job and its package, and costs no quota. Failed and cancelled jobs are not reused.

### curl walk-through

```
B=http://127.0.0.1:8080/v1
TOKEN=$(curl -s -X POST $B/installs -d '{}' | python3 -c 'import sys,json;print(json.load(sys.stdin)["token"])')
AUTH="Authorization: Bearer $TOKEN"

# text (or OCR) document
DOC=$(curl -s -X POST $B/documents -H "$AUTH" -H 'Content-Type: application/json' \
  -d '{"kind":"text","title":"Demo","pages":[{"pageIndex":0,"text":"Retrieval practice means pulling information out of memory. One week later the recall group remembered clearly more. Re-reading puts the text in front of you again."}]}')
DID=$(echo "$DOC" | python3 -c 'import sys,json;print(json.load(sys.stdin)["documentId"])')

# or a PDF
curl -s -X POST $B/documents -H "$AUTH" -F 'file=@paper.pdf;type=application/pdf' -F 'title=My paper'

JOB=$(curl -s -X POST $B/compile-jobs -H "$AUTH" -H 'Content-Type: application/json' \
  -d "{\"documentId\":\"$DID\",\"options\":{\"minutesPerLevel\":5,\"language\":\"en\"},\"clientRequestId\":\"demo-1\"}")
JID=$(echo "$JOB" | python3 -c 'import sys,json;print(json.load(sys.stdin)["jobId"])')

curl -s $B/compile-jobs/$JID -H "$AUTH"            # poll until state is ready / partial / failed
PID=...                                           # packageId from the status once levelsReady >= 1
curl -s $B/packages/$PID -H "$AUTH"
curl -s -X POST $B/packages/$PID/frames/escape -H "$AUTH"
curl -s -X POST $B/ask -H "$AUTH" -H 'Content-Type: application/json' \
  -d "{\"packageId\":\"$PID\",\"unitId\":\"L1U1\",\"question\":\"What is retrieval practice?\",\"clientRequestId\":\"q1\"}"
curl -s -X DELETE $B/documents/$DID -H "$AUTH"      # 204
```

## Security notes

- **Loopback by default.** `A2G_HOST` defaults to `127.0.0.1`. The server logs a warning if it is set to anything else.
  Nothing in this module needs, sets or documents an all-interfaces bind.
- **Public API stance.** The app has anonymous public users, so the API is a public API and the install token is **not identity
  authentication**. `POST /v1/installs` issues a random 256-bit token (SecureRandom, base64url, only its SHA-256 is stored).
  The token scopes **quota and document ownership**: another install's document, job, package or frame answers `404`, never
  `403`. Auth is default-deny: a route added later is protected unless it is added to the two-entry public list in `ApiRoutes.kt`.
- **Limits.** PDF at most 20 MB and 80 pages; the `%PDF-` magic bytes decide, not the file name or content type; extracted
  or uploaded text at most 200 000 characters; JSON bodies capped while reading (16 KB for small routes, 2 MB for text
  documents); a global declared-size gate of 21 MB; 20 compiles per install per day; 200 questions per install per day;
  ask questions at most 1000 characters. Two global in-memory cost guards (`A2G_GLOBAL_COMPILES_PER_DAY`,
  `A2G_INSTALLS_PER_HOUR`) stop quota evasion by minting installs.
- **Untrusted document text.** It is data, never instructions: it is delimited in prompts, the model has no tools, and every
  output is parsed and validated (`PackageValidator`, `FrameValidator`, quote-in-chunk checks) before it is stored.
- **No document text in logs.** The server logs ids, states and error codes. Exception messages are not logged for unexpected
  errors (class name only), and provider error bodies are never copied into exceptions, so keys and prompts cannot leak that way.
- **Files.** Ids that reach file names must match `[A-Za-z0-9_-]{1,80}`; writes are temp file + atomic rename.

### Pre-deploy checklist

- [ ] TLS terminates at a reverse proxy (Caddy/nginx); the Ktor process stays on `127.0.0.1` behind it. Do not publish its port.
- [ ] Firewall: only 443 open; the data directory is not served by anything.
- [ ] **Play Integrity / app attestation before public launch.** The install token is not proof that a real app made the request;
      without attestation anyone can mint installs and spend model budget (the global caps only limit the damage).
- [ ] Per-IP rate limiting at the proxy (the proxy sees the client address; this process does not trust forwarded headers).
- [ ] Rotate the LLM keys before launch and after any suspected leak; keys live only in the service's environment or a secret store.
- [ ] A spending cap at each LLM provider.
- [ ] Back up or accept loss of `A2G_DATA_DIR`; decide a retention period for uploaded documents (users can `DELETE`).
- [ ] The consent screen in the app says that text is sent to an external AI provider.

## Known limits

- One process, in-process worker. Jobs persist in the data dir and non-terminal jobs are re-queued at startup (their
  half-published package is discarded and rebuilt).
- The whole document goes into the plan prompt. A 200 000 character document can exceed a small model's context window; use a
  long-context model for the `compile` tier or lower the limit.
- PDF parsing runs in-process on an IO thread and is not time-bounded; a hostile PDF can occupy a thread until PDFBox finishes.
- The "support" gate of ARCHITECTURE section 4 (second LLM pass judging whether a chunk supports an answer) is not built; the
  structural and quote-in-chunk gates are. All output is therefore `auto-validated`, never `flagged`.
- `POST /v1/variants` of ARCHITECTURE section 3.2 is not implemented (not in this task's route list).

## Contract notes (not changed; for the architecture lead)

- `POST /compile-jobs` and `/cancel` answer with `CompileJobStatus` (which contains `jobId` / `state`) because `Api.kt` has no
  `{jobId}` or `{state}` response type.
- `AskResponse.requestId` is a server-generated id; the contract does not say whether it should echo `clientRequestId`.
- There is no error code for "server bug" or "package not finished"; the server uses the literal `INTERNAL_ERROR` (500) and
  `BAD_REQUEST` with HTTP 409 + `retryable: true`.
- `contentVersion` is the number of published levels, so it changes as levels are published; frames bind to it and a frame
  generated for an earlier version is not served for a later one.
