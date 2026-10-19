const assert = require('node:assert/strict');
const { test } = require('node:test');
const { createReport, validReportName } = require('./AgentBenchmarkReport.cjs');
const definitions = require('./fixtures/agent-benchmark-journeys.json');

test('legacy and exclusive candidate filenames share one constrained policy', () => {
  for (const name of ['2026-10-17-abcdef123456.json', '2026-10-17-abcdef123456-structured.json',
    '2026-10-17-abcdef123456-candidate-123456abcdef.json']) assert.equal(validReportName(name), true);
  for (const name of ['../2026-10-17-abcdef123456.json', '2026-10-17-abcdef123456-candidate-other.json',
    '2026-10-17-abcdef123456.json.bak']) assert.equal(validReportName(name), false);
});

function fixture() {
  return {
    fixture: { success: true, cleanupVerified: true },
    records: definitions.map(value => ({ journey: value.id, projectId: `project-${value.id}`,
      routingCorrect: true, acceptance: { passed: true }, questionsAsked: 0,
      ...(value.requirePlanReuse ? { planContractId: 'approved-plan' } : {}) })),
    ledger: definitions.map(value => ({ project_id: `project-${value.id}`, provider: 'mistral',
      sent_at: 'synthetic', state: 'REPORTED', logical_call_id: `call-${value.id}`,
      prompt_tokens: 10, completion_tokens: 2, unknown_charge_tokens: 0 })),
    contracts: [{ project_id: 'project-9', mode: 'BUILD', contract_id: 'approved-plan' }]
  };
}

test('all declared journeys appear and independently complete evidence passes', () => {
  const report = createReport(fixture());
  assert.equal(report.journeys.length, definitions.length);
  assert.equal(report.completed, true);
  assert.equal(report.passed, true);
});

test('a failed tenth journey cannot disappear or pass', () => {
  const input = fixture();
  input.records.at(-1).acceptance.passed = false;
  const report = createReport(input);
  assert.equal(report.journeys.at(-1).acceptance.passed, false);
  assert.equal(report.passed, false);
});

for (const kind of ['missing', 'duplicate', 'unexpected', 'shared-project']) {
  test(`${kind} journey evidence refuses a complete/pass result`, () => {
    const input = fixture();
    if (kind === 'missing') input.records.pop();
    if (kind === 'duplicate') input.records.push({ ...input.records[0] });
    if (kind === 'unexpected') input.records.push({ journey: 999 });
    if (kind === 'shared-project') input.records[1].projectId = input.records[0].projectId;
    const report = createReport(input);
    assert.equal(report.completed, false);
    assert.equal(report.passed, false);
    assert.ok(report.evidenceProblems.length > 0);
  });
}

for (const kind of ['no-ledger', 'no-sent-attempt', 'unknown-usage', 'missing-tokens', 'held', 'provider', 'cleanup', 'plan-reuse']) {
  test(`${kind} cannot be certified`, () => {
    const input = fixture();
    if (kind === 'no-ledger') input.ledger = null;
    if (kind === 'no-sent-attempt') input.ledger.pop();
    if (kind === 'unknown-usage') input.ledger.at(-1).state = 'UNKNOWN';
    if (kind === 'missing-tokens') delete input.ledger.at(-1).completion_tokens;
    if (kind === 'held') input.ledger.push({ project_id: 'project-10', state: 'RESERVED' });
    if (kind === 'provider') input.ledger.at(-1).provider = 'unapproved';
    if (kind === 'cleanup') input.fixture.cleanupVerified = false;
    if (kind === 'plan-reuse') input.contracts = [];
    assert.equal(createReport(input).passed, false);
  });
}

function plainPlanFixture() {
  const input = fixture();
  input.contracts = [];
  const record = input.records.find(value => value.journey === 9);
  delete record.planContractId;
  Object.assign(record, {planProposalId:'offer-9', planSourceUnchanged:true,
    turns:[{id:'plan-9',mode:'PLAN'},{id:'build-9',mode:'BUILD'}]});
  input.proposals = [{project_id:'project-9',build_run_id:'build-9',build_user_id:7,plan_user_id:7,request_id:'request-9',request_user_id:7,
    source_proposal_id:'offer-9',proposal_id:'offer-9',proposal_user_id:7,proposal_mode:'PLAN',proposal_status:'COMPLETE',
    proposal_plan_id:'plan-9',proposal_consumed_by:'request-9',suggested_build_text:'Add reset',content:'Preserve increment.\nAdd reset.',
    resolved_request_text:'Build the proposed changes.\n\nOriginal request:\n> Add reset\n\nImplementation proposal plan-9:\n> Preserve increment.\n> Add reset.'}];
  return input;
}

test('plain PLAN reuse requires the exact durably consumed proposal context and reports its separate kind', () => {
  const report = createReport(plainPlanFixture());
  assert.equal(report.passed, true);
  assert.equal(report.journeys.find(value => value.journey === 9).planReuseKind, 'PROPOSAL_CONTEXT');
});

for (const defect of ['missing-proof','changed-context','other-actor','other-project','unconsumed','other-plan','replanned','missing-contract']) {
  test(`plain PLAN reuse rejects ${defect}`, () => {
    const input = plainPlanFixture(), proof = input.proposals[0];
    if (defect === 'missing-proof') input.proposals = [];
    if (defect === 'changed-context') proof.resolved_request_text = 'Build something else';
    if (defect === 'other-actor') proof.proposal_user_id = 8;
    if (defect === 'other-project') proof.project_id = 'another-project';
    if (defect === 'unconsumed') proof.proposal_consumed_by = null;
    if (defect === 'other-plan') proof.proposal_plan_id = 'another-plan';
    if (defect === 'replanned') input.ledger.push({project_id:'project-9',run_id:'build-9',purpose:'PLANNER'});
    if (defect === 'missing-contract') input.records.find(value => value.journey === 9).planContractId = 'expected-contract';
    assert.equal(createReport(input).passed, false);
  });
}
