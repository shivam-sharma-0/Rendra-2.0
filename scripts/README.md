# Smart-agent verification

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
`https://api.mistral.ai/v1` and `https://integrate.api.nvidia.com/v1`. API keys authenticate provider requests;
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
