// Report generation only; all usage comes from the exported durable ledger, never run display totals.
const fs = require('node:fs');
const path = require('node:path');
const definitions = require('./fixtures/agent-benchmark-journeys.json');
const policy = require('./fixtures/agent-benchmark-policy.json');
const validReportName = name => new RegExp(policy.reportFilePattern).test(name);
const allowedProviders = new Set(['mistral', 'nvidia']);

function proposalReused(record, attempts, proposals) {
  const plan = record.turns?.[0], build = record.turns?.at(-1);
  if (!record.planSourceUnchanged || plan?.mode !== 'PLAN' || build?.mode !== 'BUILD'
      || !record.planProposalId || plan.id === build.id) return false;
  const quote = text => {
    const lines = text.split(/\r\n|\r|\n/);
    if (/\r$|\n$/.test(text)) lines.pop();
    return lines.map(line => '> ' + line).join('\n');
  };
  return proposals.some(value => {
    if (value.project_id !== record.projectId || value.build_run_id !== build.id
        || value.proposal_id !== record.planProposalId || value.source_proposal_id !== value.proposal_id
        || value.proposal_plan_id !== plan.id || value.proposal_mode !== 'PLAN' || value.proposal_status !== 'COMPLETE'
        || value.proposal_consumed_by !== value.request_id || value.proposal_user_id !== value.request_user_id
        || value.build_user_id !== value.request_user_id || value.plan_user_id !== value.request_user_id
        || typeof value.content !== 'string' || !value.content.trim() || typeof value.suggested_build_text !== 'string') return false;
    const expected = 'Build the proposed changes.\n\nOriginal request:\n' + quote(value.suggested_build_text)
      + '\n\nImplementation proposal ' + plan.id + ':\n' + quote(value.content);
    return value.resolved_request_text === expected
      && !attempts.some(attempt => attempt.run_id === build.id && ['PLANNER','SCHEMA_CORRECTION'].includes(attempt.purpose));
  });
}

function summarize(definition, record, ledger, contracts, proposals) {
  record ??= {
    routingCorrect: null, wallMs: null, questionsAsked: null,
    build: { status: 'NOT_RUN' }, preview: { status: 'NOT_RUN', ready: false },
    acceptance: { passed: false, reason: 'Fixture stopped before this journey' }
  };
  const attempts = ledger?.filter(value => record.projectId != null && value.project_id === record.projectId) || [];
  const sent = attempts.filter(value => value.sent_at != null);
  const auditKnown = ledger !== null && record.projectId != null;
  const complete = auditKnown && sent.length > 0 && sent.every(value => value.state === 'REPORTED'
    && Number.isSafeInteger(value.prompt_tokens) && value.prompt_tokens >= 0
    && Number.isSafeInteger(value.completion_tokens) && value.completion_tokens >= 0);
  const reportedInput = attempts.reduce((sum, value) => sum + Number(value.prompt_tokens || 0), 0);
  const reportedOutput = attempts.reduce((sum, value) => sum + Number(value.completion_tokens || 0), 0);
  const byPurpose = {};
  for (const value of sent) byPurpose[value.purpose] = (byPurpose[value.purpose] || 0) + 1;
  const attached = contracts.filter(value => value.project_id === record.projectId);
  const contractReused = record.planContractId != null
    && attached.some(value => value.mode === 'BUILD' && value.contract_id === record.planContractId);
  const plainPlanReused = record.planContractId == null && proposalReused(record, attempts, proposals);
  const reused = definition.requirePlanReuse && (contractReused || plainPlanReused);
  return { ...record, journey: definition.id, name: definition.name,
    modelCalls: auditKnown ? sent.length : null,
    logicalCalls: auditKnown ? new Set(sent.map(value => value.logical_call_id)).size : null,
    inputTokens: complete ? reportedInput : null, outputTokens: complete ? reportedOutput : null,
    reportedInputTokens: auditKnown ? reportedInput : null,
    reportedOutputTokens: auditKnown ? reportedOutput : null,
    usageComplete: complete, callsByPurpose: byPurpose,
    unsettledReservations: auditKnown ? attempts.filter(value => ['SENT', 'RESERVED'].includes(value.state)).length : null,
    unknownChargeTokens: auditKnown ? attempts.reduce((sum, value) => sum + Number(value.unknown_charge_tokens || 0), 0) : null,
    ...(definition.requirePlanReuse ? { approvedPlanReused: Boolean(reused),
      planReuseKind: reused ? contractReused ? 'STRUCTURED_CONTRACT' : 'PROPOSAL_CONTEXT' : null } : {}),
  };
}

function createReport({ fixture, records, ledger, contracts = [], proposals = [], sourceState = {}, commit, date }) {
  const problems = [];
  if (!Array.isArray(records)) {
    problems.push('Journey records are unavailable');
    records = [];
  }
  if (!Array.isArray(ledger)) {
    problems.push('Usage ledger is unavailable');
    ledger = null;
  }
  const knownIds = new Set(definitions.map(value => value.id));
  const byId = new Map();
  const projects = new Set();
  for (const record of records) {
    if (!record || !knownIds.has(record.journey)) {
      problems.push('Unexpected journey identity');
      continue;
    }
    if (byId.has(record.journey)) problems.push(`Duplicate journey ${record.journey}`);
    if (record.projectId != null && projects.has(record.projectId)) problems.push('Journeys share a project identity');
    if (record.projectId != null) projects.add(record.projectId);
    byId.set(record.journey, record);
  }
  for (const definition of definitions) {
    if (!byId.has(definition.id)) problems.push(`Missing journey ${definition.id}`);
  }
  const journeys = definitions.map(definition => summarize(definition, byId.get(definition.id), ledger, contracts, proposals));
  const unexpectedProviders = ledger?.filter(value => value.sent_at != null && !allowedProviders.has(value.provider)) || [];
  const completed = problems.length === 0 && fixture.success === true;
  const passed = completed && fixture.cleanupVerified === true && unexpectedProviders.length === 0
    && journeys.every(value => value.routingCorrect === true && value.acceptance?.passed === true
      && Number.isInteger(value.questionsAsked) && value.questionsAsked >= 0 && value.questionsAsked <= 2
      && value.usageComplete && value.unsettledReservations === 0 && value.unknownChargeTokens === 0
      && (value.approvedPlanReused === undefined || value.approvedPlanReused));
  return {
  schema: 1, date, timezone: 'Asia/Calcutta', commit,
  sourceState, fixture, journeys, evidenceProblems: problems,
  dataScope: 'Synthetic projects/history/files and platform prompts/tool schemas; no copied application records or real workspaces',
  destinations: ['https://api.mistral.ai/v1', 'https://integrate.api.nvidia.com/v1'],
  journey8: 'BROWSER storage with no login; Supabase/login journey is deferred until Phase 7',
  questionCounting: 'Unique requirement prompts + ASK_USER prompts + question-mark sentences in final replies (code blocks excluded); deterministic observable proxy, not semantic classification',
  acceptanceScope: 'Fixed browser recipes on the saved artifact, cached Chromium with network disabled; no real publish request',
  completed,
  cleanupVerified: fixture.cleanupVerified === true,
  passed,
  };
}

function main() {
  const [evidence, destination, commit] = process.argv.slice(2);
  if (!/^[a-f0-9]{40}$/.test(commit)) throw Error('Invalid benchmark commit');
  const archive = path.resolve(__dirname, '../../codex-archive/benchmarks');
  const output = path.resolve(destination);
  if (path.dirname(output) !== archive || !validReportName(path.basename(output))) {
    throw Error('Benchmark result escaped its archive');
  }
  const read = (name, fallback) => {
    const file = path.join(evidence, name + '.json');
    return fs.existsSync(file) ? JSON.parse(fs.readFileSync(file, 'utf8').replace(/^\uFEFF/, '')) : fallback;
  };
  const result = createReport({
    fixture: read('result', { success: false, cleanupVerified: false }),
    records: read('benchmark-journeys', {}).journeys, ledger: read('benchmark-ledger', null),
    contracts: read('benchmark-contracts', []), proposals: read('benchmark-proposals', []), sourceState: read('benchmark-source', {}),
    commit, date: path.basename(output).slice(0, 10)
  });
  fs.mkdirSync(archive, { recursive: true });
  fs.writeFileSync(output, JSON.stringify(result, null, 2), { flag: 'wx' });
  console.log(JSON.stringify({ benchmark: output, completed: result.completed, passed: result.passed }));
}

module.exports = { createReport, validReportName };
if (require.main === module) main();
