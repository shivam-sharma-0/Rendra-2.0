# Smart-agent verification

## Planner-only batch (no app or sandbox)

After the full offline `*Test` gate compiles current sources, run:

```powershell
.\scripts\Run-PlannerBatch.ps1 -VerificationDirectory target/agent-generation-gate-20261019
```

The synthetic fixture samples personal Todo 10 times, detailed notes/expenses/blog-admin prompts 3 times each,
and three short natural prompts (as real users type them) twice each: 25 samples.
Both current primary routes must be the owner-approved FREE `mistral:codestral-latest`.
The script uses the production streaming client with one transmission per call and no route fallback,
correction, generation, database, application boot, preview or sandbox. Credentials load in memory from
the existing configuration/environment and ignored secrets file; only Codestral's key is loaded.
Requirements use current resource prompts and RequirementDraft; approved capabilities are MERN/accounts
for every sample. Planner requests are captured directly from ContractPlanner and each returned proposal
passes its real offline validation, both raw and with all deterministic normalizers enabled.
`target/planner-batch-*/report.json` records per-call provider tokens, latency, characters, first rejection
rule/location and normalizer events; synthetic response artifacts stay ignored. 429/provider failures
remain separate from contract rejections and do not count as returned proposals. No retries hide them.
The rejection summary counts first blockers, not every latent defect. Valid contracts prove planning
only; generated code/READY/browser/CRUD/CHAT still require the separate E2E gate.

To measure a validator/normalizer change without new model calls, replay an earlier batch:

```powershell
.\scripts\Run-PlannerBatch.ps1 -Replay target/planner-batch-r1 -OutputDirectory target/planner-replay-r1-fix1
```

Replay loads no configuration or credentials and makes zero model calls. For each sample with a saved
planner result it rebuilds the original planner input (same fixture prompt, saved contract id, approved
requirements from the saved requirements result, MERN/accounts, limits and root path), re-runs the raw and
normalized validation with the current code and writes a new `report.json` with `replayOf` and each
sample's `original` outcome/signature. `REPLAY_SAMPLE ... CHANGED` lines mark samples whose outcome or
first-blocker signature moved. Samples without a saved proposal (429/provider failure) are carried over.
Use the same `-Fixture` as the original batch.

## S0: nine-journey benchmark (Claude executes)

From the single inner `Code-Pal` repository, after independently compiling the intended source:

```powershell
.\scripts\Run-AgentBenchmark.ps1 -VerificationDirectory 'C:\path\to\clean-scratch\codex-build'
```

Prerequisites:

- Stop the development backend and Vite first. The wrapper refuses a detected running instance.
- Java 21 and Node must be available. The clean offline Maven gate must already have produced
  `surefire-reports/TEST-com.code.pal.agent.usage.MeteredLlmClientTest.xml` under the supplied directory.
  Its classpath supplies dependency jars and Java home. **Runtime classes/resources come from this
  repository's `target/classes`, which Claude must compile from the intended revision beforehand.**
  This command never invokes Maven/npm or checks out a branch. Metadata records HEAD, dirty tracked paths
  and the AgentRunner class digest; that digest does not itself prove source/class freshness.
- WSL with Docker, Python 3, `psql`, `pg_dump` and `pg_restore`; the existing pinned PostgreSQL 17,
  Redis 7, sandbox and `codepal-browser:efeb16a78e4c` images must be cached. Browser runs use
  `--pull never --network none`. Generated frontend installs can require the existing sandbox registry access.
- The local development `codepal` PostgreSQL DB at WSL loopback must be readable as `admin`, using
  the existing `.env` password. Existing platform secrets/JWT configuration and working FREE Mistral/NVIDIA
  keys are needed; no key values are arguments or report fields. No paid model route is selected by this harness.

`Run-RealDevE2E.ps1 -AgentBenchmark` reuses the restored-dev-copy fixture. It checks source DB identity,
reads a dump, restores it into a uniquely named disposable `codex_e2e_<12 hex>` PostgreSQL container/DB,
and retains a restore-verified backup under outer `codex-archive/dev-db-backups`. **Before Boot starts**,
guarded SQL truncates every copied public application table, keeping Flyway history/schema, and verifies
zero application rows. The original DB is never mutated. A new synthetic user and nine separate projects
are created in that empty copy, using an owned WSL workspace. JVM, Redis, DB and project/browser containers
are stopped/removed with ownership checks. Workspace/evidence are retained for audit. A failed cleanup
cannot produce a passing benchmark.

Journeys: greeting; how Redra works; question about a seeded counter; broken blank counter;
small heading edit; new static bakery; new routed Vite fitness app; browser-persistent todo;
PLAN then the returned **Build this** action. Journey 8 deliberately has **no login or Supabase** until
Phase 7. Journey 9 checks immutable contract reuse from DB bindings and never substitutes a magic phrase.
The blank-page fixture is checked broken before requesting repair. Read-only journeys hash public sources
before/after; the PLAN turn must also leave them unchanged.

Output: outer `codex-archive/benchmarks/<Asia-Calcutta-date>-<12-character-HEAD>.json`, exclusive creation
(existing output is never overwritten). Per-journey usage is exported from `ai_token_attempts` by unique
project, including requirements calls without a run ID: actual sent attempts, distinct logical calls,
purpose counts, reported input/output tokens, unknown charges and held reservations. Missing/unreported
usage is `null`, with separate known subtotals; skipped journeys do not show fake zero usage. Wall time
includes project creation, requirements, execution and acceptance, excluding shared fixture startup.
Question count is an explicit observable proxy: unique requirement/ASK_USER prompts and question-mark
sentences in final replies, excluding code fences. It is not a semantic judge of every possible question.

Fixed offline Chromium recipes check the saved artifact (counter behavior, routes/form, todo reload
persistence, reset), runtime issues and blank pages. New builds also require saved code, preview READY
and publish-capability available. No actual publish request or production certification is performed.
Individual failed journeys are recorded and the driver continues after quiescing their runs; fatal fixture
errors retain partial/NOT_RUN results. `completed` means measurement finished, **not** all checks passed:
inspect `passed`, each journey's acceptance/routing and usage completeness. Call targets are reported
for review, not silently enforced by removing stages. The owner's D17/D18 clarification permits a
deterministic plan from a single-batch spec; a planner call is needed only for multiple batches (S5).

For the `c2b2afc` baseline, Claude must arrange/compile that production revision in the single folder while
preserving the harness changes, then return to the intended branch. The harness does not automate checkout
or alter commits. Compare identical prompts, fixture recipes and FREE routes at S1/S5/S6/S7 checkpoints.

### Provider payloads

Only synthetic fixture prompts, synthetic conversation history, project summaries, seeded/generated source
and validation/repair context, and platform system/mode prompts plus tool schemas reach the providers:
`https://api.mistral.ai/v1` and `https://integrate.api.nvidia.com/v1`, plus `https://openrouter.ai/api/v1` for planning
and, since 2026-10-27, `https://api.groq.com/openai/v1` as the last free-build repair alternate. API keys authenticate provider requests;
they must not be included in prompt text or reports. Copied dev records are cleared before any worker starts.
Real workspaces, the DB dump/backup, `.env`, secret files and private JWT keys are not model input.
JWT keys are used locally by the fixture backend for authentication. The benchmark uploads no images;
the separate older Phase Two gate has its own synthetic pixel fixture. Package registries/Docker/DB access
are infrastructure traffic, not model payloads. Retained DB backups contain dev data and remain local.

## S1: frontend regressions (Claude executes)

```powershell
node scripts/Test-SmartRepliesUI.mjs
```

Requires installed frontend dependencies, WSL/Docker and the cached browser image. Builds a verification-only
fixture under `frontend/target/smart-replies/<owner>` from the real workspace hook/reply components, with
stubbed API/realtime. No backend, DB or provider is used. Three recipes cover rejected-proposal error
propagation, stale/viewer-safe responses, and one approved-plan next step without a magic phrase.
The script saves recipes, browser results and cleanup receipt, then fails if an assertion fails.
This is separate from the normal frontend typecheck/build and existing UI suite.

Neither benchmark nor frontend fixture was executed in the S0/S1 tests-only writing turn.

## S1 fix verification status

Claude subsequently verified the red tests and ran the baseline at `9d5cb7c` (production `c2b2afc`).
The first S1 verification ran 818 unit tests with three outdated-expectation failures and one skip;
35+5 disposable ITs, tsc and 12 UI checks passed. The subsequent A-D review fixes await a new run.
[Current changes, selectors and limits](../docs/backend-reliability/SMART_AGENT_S1_REVIEW_FIX_HANDOFF.md).
The UI fixture includes an out-of-order scoped-API response so an older offer cannot restore a retired
button. Rejected Build this clicks have one local alert and leave the composer alone.
`MISTRAL_INCLUDE_STREAM_USAGE` defaults false pending a live compatibility check;
`NVIDIA_INCLUDE_STREAM_USAGE` remains true. These provider-specific switches do not
guarantee that every provider attempt will report usage; benchmark unknown totals remain explicit.

## Independent native outcome gate (fixture verified 2026-10-17)

`node --test scripts/Test-FeatureOutcomes.mjs` exercises the bounded outcome protocol with fake page state;
it does not run native DOM. The separate `./scripts/Run-CodexFeatureOutcomes.ps1` uses cached isolated
Chromium with synthetic native contact-form/browser-CRUD fixtures, including routed navigation and broken controls.
It mounts the current probe/relay/outcome modules read-only, uses no backend/provider/customer data,
and writes owned evidence under `target/codex-outcome-it` with label-verified container cleanup.
Do not enable `AGENT_FEATURE_OUTCOMES_ENABLED` based on the Node protocol pass alone. Focused Java
and actual native-DOM gates remain required; ten real positive/negative Chromium cases now pass with
cleanup verified. This is authored-fixture evidence; generated-app rollout still needs its gate. See
the agent-generation implementation progress file for exact receipts and remaining scope.

## Supabase SDK/browser boundary

`./scripts/Run-CodexSupabaseSdk.ps1` obtains only an integrity-pinned SDK browser artifact with npm lifecycle
scripts disabled, then runs real native signup/login/reload/server-data actions in offline Chromium against
fresh labelled Supabase services. It also requires a broken-delete negative control. Fixture policy is
`scripts/fixtures/supabase-sdk.json`; no customer credentials/target are accepted. Prepared assets and reports
remain under a unique `target/codex-supabase-sdk-it` directory, with owned container cleanup. The focused
Java gate can also include `SupabaseCrashRecoveryIT` (`codepal.supabase.it=true`) to kill its own JVM and
prove orphan cleanup; its lease ownership is controlled, not a distributed lease-expiry proof.

## Isolated current-candidate benchmark

After current all-`*Test` verification, use `Run-AgentBenchmark.ps1 -VerificationDirectory <verified-output>
-IsolatedClasses -GenerationImprovements -CandidateId <12-hex-id>` to select that output's `classes` instead
of development `target/classes`. The harness freezes that class tree in its owned evidence directory and
verifies SHA-256 manifests before/after copying, so a later compilation cannot alter the running candidate.
It records bytecode/source identity, excludes test classes from Boot and retains the existing fresh sanitized
DB/workspace/port/cleanup boundaries and FREE provider routes.
This mode can coexist with development processes; default legacy launch keeps its stop-process precondition.
Candidate reports have an exclusive suffix and never overwrite historical output. The optional improvement
flag is scoped to the synthetic backend and enables native outcomes, compact tools and bounded run artifacts;
it does not enable structured/parallel execution or normal deployment rollout.

`Run-RealDevE2E.ps1 -Scenario mern -VerificationDirectory <verified-output> -IsolatedClasses` uses the same
frozen production-only class selection, FREE routes and pre-Boot synthetic sanitization for a fresh
generated backend/frontend scenario. A benchmark also retains run steps and bounded failed-draft bodies
before its disposable database is removed, so validation failures remain inspectable after cleanup.

`Run-CodexNativeCompiler.ps1 -Source <owned-synthetic-stage>` checks native dependency recovery without a
provider call. It accepts only a retained `codepal-codex-e2e/codex_e2e_<id>/<project>/.codepal/changesets/...`
stage, copies into a fresh owned Linux volume, installs with scripts disabled, then compiles in a separate
network-disabled container. Logs, image identity and cleanup receipt stay under `target/codex-native-it`.

`Run-RealDevE2E.ps1 -ViteGeneration -IsolatedClasses -GenerationImprovements -VerificationDirectory <verified-output>`
selects only the shared multi-page Vite and browser Todo journeys (7/8) for focused generation verification.
It requires both saved builds, READY previews, real browser actions and publish capability; it retains the
physical usage ledger and exact owned project/run evidence. It does not issue a complete ten-journey report.
The real provider call remains subject to explicit approval for platform prompts/tool schemas and synthetic
generated source sent to Mistral/NVIDIA; local protocol/compiler/Chromium gates do not substitute for it.

The owner-selected PLAN route is `openrouter:nvidia/nemotron-3-ultra-550b-a55b:free`, configured by
`AGENT_MODEL_PLAN` / `AGENT_MODEL_FREE_PLAN`; coding stays on BUILD routes. Synthetic harness overrides
pin this planner to `https://openrouter.ai/api/v1`, alongside its existing fixed coding destinations.
`Run-RealDevE2E.ps1 -PlanningIntake -IsolatedClasses -VerificationDirectory <verified-output>` tests only
product intake: a reported successful plan on that primary or its explicit configured Mistral fallback,
within two ordered attempts, no redundant questions, automatic MERN and multiple planned pages.
Both route entries live in `fixtures/planner-intake.json`; coding routes are never inferred as planners.
Any failed attempt remains in the physical ledger, including UNKNOWN usage. It never approves, generates or publishes an app.
The same sanitized DB/frozen classes/owned cleanup and physical ledger evidence apply. A passing intake
does not certify app generation, authentication or backend publishing; API/MERN publishing is still gated.
The physical ledger export includes safe call outcome/error code and duration. A missing plan or failed
final attempt keeps the gate red. A successful fallback does not turn an earlier UNKNOWN into reported
usage or a complete monetary-cost certificate.

The fresh `-Scenario mern -IsolatedClasses` fixture now uses `fullStackPrompt` from that same resource:
private accounts, dashboard/task details/settings routes, persistent backend data and complete UI states.
It refuses redundant questions, absent automatic MERN/accounts or missing multipage scope. It requires
saved code/READY preview, retains the actual project usage summary, and checks existing API/browser gates.
It does not certify the still-unsupported full-stack publishing capability.

`-Scenario mern -PersonalTodo -IsolatedClasses` uses the original natural personal-Todo prompt as a
separate baseline: automatic server storage, either valid inferred auth choice, actual saved API/browser/CRUD/restart
and READY proof, plus at least three routes in the saved build contract. Detailed intake review may be
unavailable; final app proof remains required. The default account-backed `-Scenario mern` gate retains
its separate stricter intake/account checks and results. Intake usage is exported even before generation;
an intake stop is never masked by a missing build.json read.

`-CodexRoute` (developer evaluation only, added 2026-10-28) makes `codex:<-CodexModel>` (`gpt-5.6-sol` by default, or `gpt-5.6-luna`) the first free-build route and sets
`app.agent.dev.codex-enabled=true` for that fixture, so unit generation runs through the operator's own logged-in Codex
CLI (`codex exec`, read-only sandbox, empty temp directory, reasoning effort `low`); Codestral, NVIDIA and Groq stay as
alternates. It needs `codex login` on this machine, reports model usage as unknown and is never a production route.

`-TracePlanning` is an optional diagnostic for `-IsolatedClasses` only. It preserves generated synthetic
planning replies in the owned ignored evidence directory through a loopback relay to the same fixed
OpenRouter and Mistral endpoints. It never records request bodies, credentials/headers or reasoning, and the owned
relay process stops during fixture cleanup. This must not be used with customer data. Contract planning
defaults to plain JSON; `AGENT_PLANNING_CONTRACT_TOOL_ENABLED=false` leaves native tools opt-in, and
`AGENT_PLANNING_CORRECTION_FALLBACK=true` can use another explicit PLAN route for the existing correction slot.
Current defaults use Ultra first and existing Mistral Codestral second; `AGENT_PLANNING_SCHEMA_PROVIDERS=mistral`
permits schema output there. An unsupported alternate never inherits the previous provider's response format.
Planning and unit-output corrections have distinct ledger purposes; coding repair keeps its existing transport policy.

`node scripts/Test-RequirementDialog.cjs` and `node --test scripts/Test-RequirementsHook.cjs` exercise the
actual UI callbacks with controlled hook state: inferred setup stays hidden, unresolved setup cannot be
accepted by Skip all, approval uses the saved revision, and missing detailed planning is not shown as ready.
These are offline component/hook checks, not browser, backend or generated-app certificates.
