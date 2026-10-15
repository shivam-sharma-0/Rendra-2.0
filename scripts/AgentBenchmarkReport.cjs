// Report generation only; all usage comes from the exported durable ledger, never run display totals.
const fs = require('node:fs');
const path = require('node:path');
const [evidence, destination, commit] = process.argv.slice(2);
if (!/^[a-f0-9]{40}$/.test(commit)) throw Error('Invalid benchmark commit');
const archive = path.resolve(__dirname, '../../codex-archive/benchmarks');
const output = path.resolve(destination);
if (path.dirname(output) !== archive || !/^\d{4}-\d{2}-\d{2}-[a-f0-9]{12}(-structured)?\.json$/.test(path.basename(output))) {
  throw Error('Benchmark result escaped its archive');
}
function read(name, fallback) {
  const file = path.join(evidence, name + '.json');
  return fs.existsSync(file) ? JSON.parse(fs.readFileSync(file, 'utf8').replace(/^\uFEFF/, '')) : fallback;
}
const fixture = read('result', { success: false, cleanupVerified: false });
const records = read('benchmark-journeys', { journeys: [] }).journeys;
const ledger = read('benchmark-ledger', null);
const contracts = read('benchmark-contracts', []);
const names = ['greeting', 'how-work', 'project-question', 'bug-report', 'small-edit', 'new-static',
  'new-vite-multi-page', 'new-todo-browser-substitute', 'plan-then-build-this'];
const journeys = names.map((name, index) => {
  const record = records.find(value => value.journey === index + 1) || {
    journey: index + 1, name, routingCorrect: null, wallMs: null, questionsAsked: null,
    build: { status: 'NOT_RUN' }, preview: { status: 'NOT_RUN', ready: false },
    acceptance: { passed: false, reason: 'Fixture stopped before this journey' },
  };
  const attempts = ledger?.filter(value => value.project_id === record.projectId) || [];
  const sent = attempts.filter(value => value.sent_at != null);
  const auditKnown = ledger !== null && record.projectId != null;
  const complete = auditKnown && sent.every(value => value.state === 'REPORTED'
    && value.prompt_tokens !== null && value.completion_tokens !== null);
  const reportedInput = attempts.reduce((sum, value) => sum + Number(value.prompt_tokens || 0), 0);
  const reportedOutput = attempts.reduce((sum, value) => sum + Number(value.completion_tokens || 0), 0);
  const byPurpose = {};
  for (const value of sent) byPurpose[value.purpose] = (byPurpose[value.purpose] || 0) + 1;
  const attached = contracts.filter(value => value.project_id === record.projectId);
  const reused = index === 8 && record.planContractId != null
    && attached.some(value => value.mode === 'BUILD' && value.contract_id === record.planContractId);
  return { ...record,
    modelCalls: auditKnown ? sent.length : null,
    logicalCalls: auditKnown ? new Set(sent.map(value => value.logical_call_id)).size : null,
    inputTokens: complete ? reportedInput : null, outputTokens: complete ? reportedOutput : null,
    reportedInputTokens: auditKnown ? reportedInput : null,
    reportedOutputTokens: auditKnown ? reportedOutput : null,
    usageComplete: complete, callsByPurpose: byPurpose,
    unsettledReservations: auditKnown ? attempts.filter(value => ['SENT', 'RESERVED'].includes(value.state)).length : null,
    unknownChargeTokens: auditKnown ? attempts.reduce((sum, value) => sum + Number(value.unknown_charge_tokens || 0), 0) : null,
    ...(index === 8 ? { approvedPlanReused: reused } : {}),
  };
});
const unexpectedProviders = ledger?.filter(value => value.sent_at != null && !['mistral', 'nvidia'].includes(value.provider)) || [];
const result = {
  schema: 1, date: path.basename(output).slice(0, 10), timezone: 'Asia/Calcutta', commit,
  sourceState: read('benchmark-source', {}), fixture, journeys,
  dataScope: 'Synthetic projects/history/files and platform prompts/tool schemas; no copied application records or real workspaces',
  destinations: ['https://api.mistral.ai/v1', 'https://integrate.api.nvidia.com/v1'],
  journey8: 'BROWSER storage with no login; Supabase/login journey is deferred until Phase 7',
  questionCounting: 'Unique requirement prompts + ASK_USER prompts + question-mark sentences in final replies (code blocks excluded); deterministic observable proxy, not semantic classification',
  acceptanceScope: 'Fixed browser recipes on the saved artifact, cached Chromium with network disabled; no real publish request',
  completed: records.length === 9 && fixture.success === true,
  cleanupVerified: fixture.cleanupVerified === true,
  passed: fixture.success === true && fixture.cleanupVerified === true && ledger !== null && unexpectedProviders.length === 0
    && journeys.every(value => value.routingCorrect === true && value.acceptance.passed && value.questionsAsked <= 2 && value.usageComplete
      && value.unsettledReservations === 0) && journeys[8].approvedPlanReused,
};
fs.mkdirSync(archive, { recursive: true });
// Exclusive creation preserves previous measurements for the same source/date.
fs.writeFileSync(output, JSON.stringify(result, null, 2), { flag: 'wx' });
console.log(JSON.stringify({ benchmark: output, completed: result.completed, passed: result.passed }));
