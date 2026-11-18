# Anything2Game server

Kotlin + Ktor backend of the `:server` module. It ingests a document (PDF or already-extracted text), compiles it into a
validated learning package with an LLM, publishes the package level by level, generates optional "frames" (story layers),
and answers questions grounded in the document. The wire format is the `contract/` module; this module reuses its DTOs and
validators and defines no wire types of its own.

```
./gradlew :server:run          # fake model, no keys, file store, listens on 127.0.0.1:8080
./gradlew :server:test         # fake model; the Postgres tests start a throwaway container (Testcontainers) and are skipped without Docker
A2G_SERVER_ONLY=1 ./gradlew :server:installDist   # build the server without the Android modules (no Android SDK needed)
```

The server is **cloud-portable**: one container, state in PostgreSQL, configuration only through environment variables, no cloud SDK
(see [Container contract](#container-contract)).

## Packages (`com.anything2game.server`)

| Package | What it does |
|---|---|
| `config` | `ServerConfig.fromEnv()`: all settings from environment variables, safe defaults, fails fast on a missing key. `Limits` holds the hard limits. |
| `api` | Ktor routes (`ApiRoutes.kt`), error mapping, request ids and access log (`ApiModule.kt`), the `Authenticator` port with install tokens and the default-deny plugin (`Auth.kt`), OIDC JWT verification (`OidcJwtAuthenticator.kt`), quotas (`Quotas.kt`). |
| `ingest` | `PdfTextExtractor` (PDFBox 3, text per page, encrypted and no-text detection, time limit), `Chunker` (paragraphs to 300-1200 char chunks, never across pages, `c1..cn`), `documentVersion`. |
| `llm` | `LlmClient` + `OpenAiCompatibleLlmClient`, `AnthropicLlmClient`, `FakeLlmClient`, `LlmRouter` (tiers `compile`, `frame`, `flash`), prompt loading and the untrusted-text format (`Prompts.kt`). |
| `compile` | The pipeline nodes, `CompileJobRunner` (job worker: claims jobs with a lease, runs the state machine), frame nodes, `FrameService`. |
| `adapt` | `AskService` (retrieval + flash model), `Retrieval` (keyword overlap, Chinese by character bigrams). |
| `store` | Four repositories (`InstallRepository`, `DocumentRepository`, `JobRepository`, `PackageRepository`; `Store` bundles them for wiring), `FileStore` (JSON files, local development) and `PostgresStore` (JDBC + Flyway). |
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
                Refuses (returns false) once the job is terminal or leased to another worker: a late result after cancel is
                discarded, and the store (not an in-process lock) guarantees it
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
`frame-rookie.v1.md`, `frame-companion.v1.md`, `ask.v1.md`; for the experiment also `claims.v1.md`, `claim-check.v1.md`,
`plan-claims.v1.md`, `unit.v1.md`, `support.v1.md` (`PromptTemplates.LAYERED`). Each file is the system prompt, a line `=== USER ===`, the user
prompt, with `{{placeholders}}`. Rendering is strict (a missing or unused variable throws). Every prompt states the role, JSON-only
output with the exact shape and an example adapted from `contract/examples`, "every quote is an exact substring of the cited
chunk", the language rule (`en` or `zh`) and that the text between `<document>` tags is untrusted data. Document text enters
prompts only inside `<document><chunk ...>` blocks; tag-like sequences in it are defused (`<` becomes `‹`) so it cannot close
a block, and the model's answer is mapped back before quotes are checked. The `promptVersion` recorded in a package is
`plan.v1+level.v1`; in a frame it is the frame's prompt file name.

## Generation pipelines (experiment)

Two flags let the team A/B the way units are generated on real documents. **Frame generation is identical in every arm**; only the
unit generation (and, with the gate, a check after it) changes. Design and pre-registered decision rule:
`docs/architecture/layered-generation.md`. With both flags unset the server runs the classic pipeline above, call for call.

| Variable | Values | Meaning |
|---|---|---|
| `A2G_PIPELINE` | `classic` (default), `layered` | `classic` = plan -> level -> validate. `layered` = claims -> plan over claims -> one call per unit. |
| `A2G_SUPPORT_GATE` | `off` (default), `on` | Support gate after generation, works with both pipelines (arm A+G = classic + gate). |
| `A2G_LAYERED_PARALLELISM` | `6` (1 to 32) | How many claim-batch and unit calls run at once (layered). |

The `flash` tier (`A2G_FLASH_*`) runs the claim entailment check and the gate; the `compile` tier runs everything else.

```
LAYERED  (compile/layered/)
  ClaimNode        L1  chunks -> batches (~6 chunks / ~7000 chars, a chunk is never split), one call per batch IN PARALLEL (claims.v1)
                       claim = claimId k1..kn (assigned by us) | chunkId | quote (verbatim) | text | type | scope | numbers | importance | relations
  ClaimChecker     L1  1 deterministic: quote in chunk (PackageValidator.normalise) -> every number of `text` in `quote` (ASCII or
                       full-width digits, decimals, percents; also in Chinese) -> `scope` present for number/comparison/causal
                       2 entailment, flash tier, batches of <= 15 (claim-check.v1): only `supported` survives
                       3 conflicts: two `number` claims whose text minus digits is near-identical (token Jaccard >= 0.6, character
                         bigrams for Chinese) but whose numbers differ -> both excluded from questions (ids logged, never text)
  LayeredPlanNode  L2  surviving claims only (plan-claims.v1), no document. Levels as in the classic plan, but a unit lists claimIds (1-4).
                       Enforced in code: ids exist; logistics claims are not planned unless >= 60 % of the claims are logistics;
                       `order` only with a `sequence` claim, otherwise the unit becomes `deduce`; 1-4 levels, 3-4 units, >= 2 mechanics
  UnitNode         L3  ONE call per unit (unit.v1), all units of all levels in parallel (semaphore, level 1 queued first). Input = the unit's
                       claims + their chunks' full text. Every wrong part is a declared mutation:
                       "mutations":[{"partId","claimId","kind"}], kind = overgeneralise_scope | swap_number | reverse_cause |
                       wrong_order | swap_entity | drop_limitation. The item is parsed into the contract DTO; `mutations` is read from the raw JSON
  UnitChecks       L3  PackageValidator item checks; a number on the correct side (supported swipe cards, sort cards, the correct deduce option,
                       every why/feedback, order steps) must appear in the quotes of the unit's claims; a declared mutation licenses only
                       the numbers of its own part; every wrong part (unsupported card, wrong option) has a declared mutation; `order` needs a
                       `sequence` claim. On failure: ONE repair call (LlmSteps.repair with the issue list), then the unit is dropped
  publish              a level is published (PublishNode, unchanged) as soon as ITS units are done; < 2 surviving units = the level fails.
                       The recap is the level's most important claims (no extra call). Package promptVersion = claims.v1+plan-claims.v1+unit.v1
SUPPORT GATE  (SupportGate.kt, support.v1, flash tier, both pipelines)
  per unit: the unit's item and variants + the full text of every chunk they cite (+ the claims in layered mode). The model answers per part
  {"parts":[{"partId","verdict":"correct|wrong|ambiguous","reason"}]}: swipe = each card's supported flag, sort = each card's bin, deduce =
  the correct option is right and every other wrong, order = the order. Anything but `correct` rejects the unit:
    layered:  rerun UnitNode with the reasons (label unit:<id>:gate), re-gate once (support:<id>:regate), else drop the unit
    classic:  ONE level repair call with the reasons as issues (label repair:level:<id>:gate); only the rejected units are replaced,
              re-gated once, else dropped
  promptVersion gets "+support.v1".
```

Model call labels (they are what the fake model and the logs key on): `claims:b<n>`, `claimcheck:b<n>`, `planclaims`, `unit:<unitId>`,
`unit:<unitId>:gate`, `support:<unitId>`, `support:<unitId>:regate`, `repair:<label>`, `repair:level:<id>:gate`.

**Stats line.** When the generation of a job ends (before its final state is set) ONE line is logged, `layered stats {json}` for the layered
pipeline, `classic stats {json}` for the classic pipeline with the gate on (nothing for plain classic). Counts and ids only, no document text:

```
layered stats {"jobId":"job_...","pipeline":"layered","gate":true,
 "claims":{"batches":1,"batchesFailed":0,"extracted":42,"droppedQuote":1,"droppedNumber":2,"droppedScope":1,"droppedEntailment":3,
           "conflictPairs":1,"conflictClaims":2,"conflictPairIds":["k7~k19"],"kept":33},
 "plan":{"units":9,"orderSwitched":1},
 "units":{"generated":9,"repaired":2,"dropped":1,"kept":8},
 "gate":{"unitsChecked":9,"partsCorrect":61,"partsWrong":2,"partsAmbiguous":1,"unitsRejected":2,"regenerations":2,"unitsDropped":1},
 "millis":{"claims":9100,"plan":4200,"levels":{"L1":21000,"L2":33000,"L3":35500},"total":35600}}
```

`units.generated` = units that passed the checks (after at most one repair); `units.kept` = units that went into a level (after the gate);
`units.dropped` = every unit that was not (checks, gate, model error); `millis.levels` = time from the start of the job until the level was
published (or failed), so the first value is the time to first playable level. In the classic arm only `pipeline`, `gate`, `gate.*` and
`millis.levels` / `millis.total` are filled.

## Configuration (environment variables only)

| Variable | Default | Meaning |
|---|---|---|
| `A2G_HOST` | `127.0.0.1` | Bind address. Never defaults to all interfaces. |
| `A2G_PORT` | `8080` | Port. If unset, `PORT` is used (Cloud Run / App Runner convention). |
| `A2G_STORE` | `file` | `file` (local development) or `postgres` (cloud). See [Storage](#storage-and-the-job-queue). |
| `A2G_DATA_DIR` | `server/data` | Data directory of the file store (git-ignored). |
| `A2G_DB_URL`, `A2G_DB_USER`, `A2G_DB_PASSWORD` | unset | PostgreSQL JDBC URL, user and password (required for `postgres`; the password is a secret). |
| `A2G_DB_POOL_SIZE` | `10` | Connection pool size (3 to 100). |
| `A2G_WORKER_CONCURRENCY` | `2` | Compile jobs one instance runs at the same time. |
| `A2G_WORKER_LEASE_SECONDS` | `30` | How long a worker owns a job without renewing (renewed every third of it). |
| `A2G_WORKER_POLL_MS` | `1000` | How often an idle worker looks for jobs (a job created on this instance wakes it at once). |
| `A2G_OIDC_ISSUER`, `A2G_OIDC_CLIENT_ID`, `A2G_OIDC_JWKS_URI` | unset | Optional sign-in. See [Sign-in](#sign-in-with-oidc--cognito). |
| `A2G_PDF_TIMEOUT_SECONDS` | `20` | Time limit for reading one PDF (1 to 300). |
| `A2G_PIPELINE`, `A2G_SUPPORT_GATE`, `A2G_LAYERED_PARALLELISM` | `classic`, `off`, `6` | Generation experiment, see [Generation pipelines (experiment)](#generation-pipelines-experiment). |
| `A2G_LOG_FORMAT` | `text` | `text` or `json` (one JSON object per line on stdout). |
| `A2G_<TIER>_PROVIDER` (`COMPILE`, `FRAME`, `FLASH`) | unset | Per tier: `fake`, `openai-compatible` (alias `openai`) or `anthropic`. Wins over `A2G_LLM_<TIER>`. |
| `A2G_<TIER>_BASE_URL`, `_MODEL`, `_API_KEY` | unset | Per tier endpoint, model and key. Missing ones fall back to the shared `A2G_OPENAI_*` / `ANTHROPIC_*` values below. |
| `A2G_LLM_COMPILE` | `fake` | Older, shared way to pick the provider of the tier for plan, level, validate-repair: `fake`, `openai` or `anthropic`. |
| `A2G_LLM_FRAME` | `fake` | Same for frame generation. |
| `A2G_LLM_FLASH` | `fake` | Same for ask. |
| `A2G_OPENAI_BASE_URL` | `https://api.deepseek.com` | OpenAI-compatible endpoint (`/chat/completions` is appended). Must be `https` (http only for localhost). |
| `A2G_OPENAI_API_KEY` | unset | Key for tiers set to `openai`. |
| `A2G_OPENAI_MODEL_STRONG` | `deepseek-chat` | Model of the `compile` and `frame` tiers when they are `openai`. |
| `A2G_OPENAI_MODEL_FLASH` | `deepseek-chat` | Model of the `flash` tier when it is `openai`. |
| `ANTHROPIC_API_KEY` | unset | Key for tiers set to `anthropic`. |
| `A2G_ANTHROPIC_MODEL` | `claude-sonnet-5-5` | Claude model id. |
| `A2G_DEFAULT_FRAMES` | `case` | Comma-separated frames generated after every compile (`classic` is accepted and does nothing). |
| `A2G_LLM_TIMEOUT_SECONDS` | `120` | Per model call. |
| `A2G_ASK_TIMEOUT_MS` | `5000` | Ask deadline; exceeded = `504 MODEL_TIMEOUT`. |
| `A2G_GLOBAL_COMPILES_PER_DAY` | `500` | Cost guard across all owners and all instances (counter in the store). |
| `A2G_INSTALLS_PER_HOUR` | `200` | Cost guard against install farming, across all instances (counter in the store, fixed window per clock hour). |

If a tier selects a real provider and its key is missing, or `A2G_STORE=postgres` lacks its database settings, or an OIDC issuer
has no client id, the process exits with code 2 and a clear message before listening. Keys are read from the environment only; they are never in code, config files or logs, and `ServerConfig.toString()`
masks them.

### Model endpoints per tier (DeepSeek, Qwen, Gemini, Claude, ...)

Each tier (`COMPILE`, `FRAME`, `FLASH`) has its own provider, endpoint, model and key, so any tier can use any provider and
providers can be swapped without a code change. `A2G_<TIER>_PROVIDER` is `fake`, `openai-compatible` (the OpenAI chat-completions
protocol; the old value `openai` is an alias) or `anthropic`. For every value that a tier does not set, the shared variables
(`A2G_LLM_<TIER>`, `A2G_OPENAI_BASE_URL`, `A2G_OPENAI_API_KEY`, `A2G_OPENAI_MODEL_STRONG` / `_FLASH`, `ANTHROPIC_API_KEY`,
`A2G_ANTHROPIC_MODEL`) are the fallback, so existing settings keep working. The base URL must be `https` (the key is sent to it).
Model names below are examples: **check the provider's current model list**.

```
# DeepSeek for everything (shared variables, as before)
A2G_LLM_COMPILE=openai A2G_LLM_FRAME=openai A2G_LLM_FLASH=openai \
A2G_OPENAI_BASE_URL=https://api.deepseek.com A2G_OPENAI_API_KEY=... \
A2G_OPENAI_MODEL_STRONG=deepseek-chat A2G_OPENAI_MODEL_FLASH=deepseek-chat

# Qwen (Alibaba Cloud DashScope, OpenAI-compatible mode) for compile and frames, DeepSeek for questions
A2G_COMPILE_PROVIDER=openai-compatible A2G_FRAME_PROVIDER=openai-compatible
A2G_COMPILE_BASE_URL=https://dashscope.aliyuncs.com/compatible-mode/v1 A2G_FRAME_BASE_URL=https://dashscope.aliyuncs.com/compatible-mode/v1
A2G_COMPILE_MODEL=qwen-plus A2G_FRAME_MODEL=qwen-plus A2G_COMPILE_API_KEY=... A2G_FRAME_API_KEY=...
A2G_FLASH_PROVIDER=openai-compatible A2G_FLASH_BASE_URL=https://api.deepseek.com A2G_FLASH_MODEL=deepseek-chat A2G_FLASH_API_KEY=...

# Google Gemini through its OpenAI-compatible endpoint (a Gemini API key from Google AI Studio)
A2G_FLASH_PROVIDER=openai-compatible
A2G_FLASH_BASE_URL=https://generativelanguage.googleapis.com/v1beta/openai/
A2G_FLASH_MODEL=<a Gemini model id> A2G_FLASH_API_KEY=...

# Claude for compile and frames (dev / eval), a cheap model for questions
A2G_COMPILE_PROVIDER=anthropic A2G_FRAME_PROVIDER=anthropic ANTHROPIC_API_KEY=... A2G_ANTHROPIC_MODEL=claude-sonnet-5-5
A2G_FLASH_PROVIDER=openai-compatible A2G_FLASH_BASE_URL=https://api.deepseek.com A2G_FLASH_MODEL=deepseek-chat A2G_FLASH_API_KEY=...
```

The OpenAI-compatible client (`/chat/completions` is appended to the base URL) sends
`response_format: {"type":"json_object"}`. The Claude client uses the official `com.anthropic:anthropic-java` SDK, treats a
`refusal` stop reason as a non-retryable failure and a `max_tokens` stop as a bad response. The validators, not the
provider, decide whether output is acceptable.

The fake model (default) derives deterministic output from the chunks in the prompt (quotes are exact first sentences, Chinese
text is split on 。！？ too), so the whole stack runs and demos with no keys and no network.

## Storage and the job queue

`A2G_STORE=file` (default) keeps JSON files under `A2G_DATA_DIR`: for local development and tests, **one instance only**.
`A2G_STORE=postgres` is the cloud store: `A2G_DB_URL=jdbc:postgresql://host:5432/dbname` (add `?sslmode=require` where the database
enforces TLS, as RDS does by default for new PostgreSQL 15+ parameter groups), `A2G_DB_USER`, `A2G_DB_PASSWORD`. Flyway applies
`src/main/resources/db/migration/V*.sql` at startup (it takes a database lock, so instances that start together are safe).
Packages, frames and chunks are `jsonb` written with the contract's JSON configuration.

The code that uses storage depends on one of four small interfaces, not on a database: `InstallRepository` (installs and the
quota counters), `DocumentRepository`, `JobRepository` (jobs and the queue), `PackageRepository` (packages and their frames).
Replacing one of them (say packages in object storage) does not touch the others.

**Why several instances can share one database.**

- **The jobs table is the queue.** A worker claims the oldest job that is not finished and has no lease, or an expired lease:
  `UPDATE ... WHERE job_id = (SELECT ... FOR UPDATE SKIP LOCKED LIMIT 1) RETURNING ...`. Two instances never get the same job.
  The claim records `claimed_by`, `lease_until` and `attempts`.
- **Leases.** While a worker runs a job it renews the lease every third of `A2G_WORKER_LEASE_SECONDS`. If the instance dies, the
  lease runs out and another instance takes the job; a job found half-done is rebuilt from scratch (its half-published package is
  deleted first). A worker whose renewal fails (the job was cancelled, deleted or taken over) stops working on it, and its writes
  are rejected anyway. A job claimed more than three times without finishing is failed instead of retried forever. On a clean
  shutdown the leases are given back so another instance continues at once.
- **No in-process lock decides anything.** Every job state change is `JobRepository.updateIfNotTerminal`: Postgres locks the row
  (`SELECT ... FOR UPDATE`) in a transaction; the file store uses its own lock. "Publish a level" runs inside that same update, so a
  cancel either comes first (the late level is refused) or second (the level is kept and the package is completed). A late result
  after cancel is discarded by the store, on any instance.
- **Global cost guards are in the store.** `A2G_GLOBAL_COMPILES_PER_DAY`, `A2G_INSTALLS_PER_HOUR` and the per-owner daily quotas are
  atomic conditional upserts (`INSERT ... ON CONFLICT DO UPDATE ... WHERE n < limit RETURNING n`), so the limits hold across instances
  and restarts. The install limit is a fixed window per clock hour (up to twice the limit can pass around a full hour boundary).
- The worker loop polls every `A2G_WORKER_POLL_MS` and is woken at once when its own instance creates a job. Run at least one instance
  with the CPU always allocated (ECS Fargate, Cloud Run with "CPU always allocated"): a platform that throttles CPU between requests
  would starve the worker.

The file store keeps the same behaviour for one instance (a single in-process lock; leases are recorded but nobody competes).

## Sign-in with OIDC / Cognito

Anonymous install tokens remain the default. Setting `A2G_OIDC_ISSUER` also enables sign-in with an OIDC access token (a JWT):

| Variable | Meaning |
|---|---|
| `A2G_OIDC_ISSUER` | The issuer, e.g. `https://cognito-idp.<region>.amazonaws.com/<userPoolId>`. Setting it turns the feature on. |
| `A2G_OIDC_CLIENT_ID` | Required with the issuer: the app client id the tokens must be issued for. |
| `A2G_OIDC_JWKS_URI` | Optional. Default `<issuer>/.well-known/jwks.json` (Cognito). For Keycloak, Google, Auth0 etc. set the provider's `jwks_uri`. |

A token is accepted only if its RS256 signature verifies against the issuer's key set (fetched over HTTPS, cached, refreshed when an
unknown key id appears), `iss` equals the issuer, `exp` and `nbf` hold, `token_use` is `access` when present (Cognito ID tokens are
refused), and `client_id` (Cognito access tokens) or, when absent, `aud` equals the client id. `alg: none`, HMAC and other
algorithms are refused. Anything else is `401 UNAUTHORIZED`; an unverified JWT is never accepted. The owner id of such a caller is
`user:<sub>`, of an install token `install:<installId>`; quotas and ownership use the owner id everywhere (a user's resources are
invisible to others and to installs). A bearer value that looks like a JWT goes to the OIDC verifier only, everything else is an
install token. The server uses no Cognito SDK: only HTTPS to the JWKS URL. The app (or a web client) obtains the access token from the
provider's hosted UI / SDK; `POST /v1/installs` still works for users who do not sign in.

## Container contract

```
docker build -f server/Dockerfile -t a2g-server:dev .                    # from the repository root; no Android SDK needed
docker run --rm -p 127.0.0.1:18090:8080 a2g-server:dev                    # publish on loopback only, never on all interfaces
curl http://127.0.0.1:18090/v1/health
```

The image (multi-stage: `gradle:9.4.1-jdk21` builds, `eclipse-temurin:21-jre` runs; about 410 MB) is the whole interface between the code
and the infrastructure:

| Contract | |
|---|---|
| Port | Listens on `$PORT`, else `$A2G_PORT`, else 8080 (`EXPOSE 8080`). `A2G_HOST=0.0.0.0` is set in the image because a container must accept traffic from its network; who can reach the port is decided by the platform (load balancer, security groups, `-p 127.0.0.1:...`). Terminate TLS in front of it. |
| Configuration | Only environment variables (`A2G_*`, `ANTHROPIC_API_KEY`, `PORT`), injected from the platform's secret store (AWS Secrets Manager, Google Secret Manager, Alibaba KMS). Secrets are never in the image or in logs. |
| State | None in the container. `A2G_STORE=postgres` with `A2G_DB_*`; the default file store writes to `/app/data` and is only for smoke tests. |
| Health | `GET /v1/health` (process is up, no dependencies) and `GET /v1/ready` (store reachable: `SELECT 1`; 503 otherwise). Use health as the liveness probe, ready as the readiness / load-balancer check. Both are unauthenticated. |
| Logs | stdout only. `A2G_LOG_FORMAT=json` (the image default) writes one JSON object per line (`level`, `loggerName`, `formattedMessage`, `mdc.requestId`, `timestamp`); `text` for people. Every call has a request id (`X-Request-Id` from the proxy if it is harmless, else generated, echoed in the response). The access log has method, path and status only: never query strings, headers, tokens, questions or document text. |
| User | Runs as the non-root user `a2g` (uid 10001). |
| Scaling | Any number of instances on one database (see above), at least one with CPU always allocated. |
| Database network | Put the database in private subnets and let only the service's security group reach port 5432. |

## API (ARCHITECTURE section 3.2, plus two frame routes)

All routes are under `/v1`. Everything except `GET /v1/health`, `GET /v1/ready` and `POST /v1/installs` needs `Authorization: Bearer <token>`
(an install token, or an OIDC access token when sign-in is configured).

| Route | Result |
|---|---|
| `GET /health` | `{"status":"ok","contract":"v1"}` (liveness) |
| `GET /ready` | `{"status":"ready","contract":"v1"}` when the store answers, `503` with an `ErrorBody` otherwise |
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
resource), `BAD_REQUEST` 400, `FILE_TOO_LARGE` 413, `UNSUPPORTED_TYPE` 415 (also a PDF that takes longer than `A2G_PDF_TIMEOUT_SECONDS`), `NO_TEXT_LAYER` and `ENCRYPTED_PDF` 422,
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

- **Loopback by default.** `A2G_HOST` defaults to `127.0.0.1`. The server logs a warning if it is set to anything else. The only
  all-interfaces setting in this module is `ENV A2G_HOST=0.0.0.0` in `server/Dockerfile`, which a container needs; exposure is then
  governed by the platform (load balancer, security groups) or by publishing the port on `127.0.0.1` only.
- **Public API stance.** The app has anonymous public users, so the API is a public API and the install token is **not identity
  authentication**. `POST /v1/installs` issues a random 256-bit token (SecureRandom, base64url, only its SHA-256 is stored).
  The token scopes **quota and document ownership**: another install's document, job, package or frame answers `404`, never
  `403`. Auth is default-deny: a route added later is protected unless it is added to the three-entry public list in `ApiRoutes.kt`
  (`GET /v1/health`, `GET /v1/ready`, `POST /v1/installs`). Signed-in users (OIDC) are a second, verified kind of caller; see above.
- **Limits.** PDF at most 20 MB and 80 pages; the `%PDF-` magic bytes decide, not the file name or content type; extracted
  or uploaded text at most 200 000 characters; JSON bodies capped while reading (16 KB for small routes, 2 MB for text
  documents); a global declared-size gate of 21 MB; 20 compiles per install per day; 200 questions per install per day;
  ask questions at most 1000 characters; one PDF is read for at most `A2G_PDF_TIMEOUT_SECONDS` (20) seconds. Two global cost
  guards in the store (`A2G_GLOBAL_COMPILES_PER_DAY`, `A2G_INSTALLS_PER_HOUR`) stop quota evasion by minting installs.
- **Untrusted document text.** It is data, never instructions: it is delimited in prompts, the model has no tools, and every
  output is parsed and validated (`PackageValidator`, `FrameValidator`, quote-in-chunk checks) before it is stored.
- **No document text in logs.** The server logs ids, states and error codes. Exception messages are not logged for unexpected
  errors (class name only), and provider error bodies are never copied into exceptions, so keys and prompts cannot leak that way.
- **Files.** Ids that reach file names must match `[A-Za-z0-9_-]{1,80}`; writes are temp file + atomic rename. (Postgres access uses
  bound parameters only.)

### Pre-deploy checklist

- [ ] TLS terminates at the platform's load balancer or a reverse proxy; the server's port is reachable only from it (security group /
      `127.0.0.1` binding), never published on all interfaces.
- [ ] The database is in private subnets; only the service's security group reaches it; `A2G_DB_PASSWORD` comes from the secret store.
- [ ] Firewall: only 443 open at the front door; the data directory is not served by anything.
- [ ] **Play Integrity / app attestation before public launch.** The install token is not proof that a real app made the request;
      without attestation anyone can mint installs and spend model budget (the global caps only limit the damage).
- [ ] Per-IP rate limiting at the proxy (the proxy sees the client address; this process does not trust forwarded headers).
- [ ] Rotate the LLM keys before launch and after any suspected leak; keys live only in the service's environment or a secret store.
- [ ] A spending cap at each LLM provider.
- [ ] Database backups (managed PostgreSQL snapshots); decide a retention period for uploaded documents (users can `DELETE`).
- [ ] The consent screen in the app says that text is sent to an external AI provider.

## Known limits

- The worker runs in the server process. With the file store that means one instance; with Postgres any number of instances, and a job
  of a dead instance is rebuilt from scratch by another one after its lease expires (default 30 s).
- The whole document goes into the plan prompt. A 200 000 character document can exceed a small model's context window; use a
  long-context model for the `compile` tier or lower the limit.
- PDF parsing runs in-process on a small pool of daemon threads with a time limit; on timeout the document is closed and the upload is
  refused, but PDFBox may keep a CPU core busy for a short while before it notices.
- Old data of the file store written before the `ownerId` change (`installId` fields) is not read; delete `A2G_DATA_DIR`.
- The "support" gate of ARCHITECTURE section 4 (second LLM pass judging whether a chunk supports an answer) is not built; the
  structural and quote-in-chunk gates are. All output is therefore `auto-validated`, never `flagged`.
- `POST /v1/variants` of ARCHITECTURE section 3.2 is not implemented (not in this task's route list).

## Contract notes (not changed; for the architecture lead)

- `POST /compile-jobs` and `/cancel` answer with `CompileJobStatus` (which contains `jobId` / `state`) because `Api.kt` has no
  `{jobId}` or `{state}` response type.
- `AskResponse.requestId` is a server-generated id; the contract does not say whether it should echo `clientRequestId`.
- There is no error code for "server bug" or "package not finished"; the server uses the literal `INTERNAL_ERROR` (500) and
  `BAD_REQUEST` with HTTP 409 + `retryable: true`. (`PACKAGE_NOT_READY` and `INTERNAL_ERROR` have since been added to `ErrorCodes`.)
- A PDF that takes too long to read answers `415 UNSUPPORTED_TYPE` with a message; there is no dedicated code (for example `PDF_TIMEOUT`).
- Signed-in users need no contract change: the same routes, a different bearer token. The app cannot yet obtain or send such a token.
- `contentVersion` is the number of published levels, so it changes as levels are published; frames bind to it and a frame
  generated for an earlier version is not served for a later one.
