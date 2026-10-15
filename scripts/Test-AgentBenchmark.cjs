const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const browserCheck = require('./AgentBenchmarkBrowser.cjs');
const { createBenchmarkApi } = require('./AgentBenchmarkAuth.cjs');

const [base, workspace, evidence] = process.argv.slice(2);
if (!/^http:\/\/127\.0\.0\.1:\d+$/.test(base)) throw Error('Owned loopback backend required');
const owner = path.basename(evidence);
if (!/^codex_e2e_[a-f0-9]{12}$/.test(owner)) throw Error('Owned evidence directory required');
const projects = [];
const journeys = [];
const identity = crypto.randomUUID().replaceAll('-', '');
const client = createBenchmarkApi(base, { email: `benchmark-${identity}@example.invalid`,
  password: crypto.randomBytes(16).toString('hex'), name: 'Synthetic benchmark' });
const api = client.api;
let workspaceId;
let browserIndex = 0;
const pause = ms => new Promise(resolve => setTimeout(resolve, ms));
const save = (name, data) => fs.writeFileSync(path.join(evidence, name + '.json'), JSON.stringify(data, null, 2));
const action = (id, kind, selector, value) => ({ id, kind, selector, value });
const counterActions = [action('counter', 'ASSERT_TEXT', 'h1', 'Counter'), action('increment', 'CLICK', '#increment'),
  action('count', 'ASSERT_TEXT', '#count', '1')];
const counter = '<!doctype html><html lang="en"><head><meta charset="utf-8"><title>Counter</title></head><body><main id="app"><h1>Counter</h1><button id="increment">Increment</button><output id="count">0</output></main><script>document.querySelector("#increment").onclick=()=>document.querySelector("#count").textContent=Number(document.querySelector("#count").textContent)+1</script></body></html>';
const staticPrompt = 'Build a polished static website for Solar Bakery, a synthetic bakery. Include a responsive hero headed Solar Bakery, a menu section id=menu, and a Contact link to #contact with a contact section id=contact. Use static HTML/CSS, no framework, accounts, backend or database. Add a working mobile menu if navigation collapses. It should be ready to preview and publish.';
const vitePrompt = 'Build a React/Vite multi-page fitness app for fictional Solar Fitness. Routes /, /classes and /contact; visible navigation links to Classes and Contact. Classes lists Yoga. Contact has labelled fields id=name, id=email, id=message and a Send button id=send; submitting valid values displays Message received in id=contact-status without a backend. Use browser-only state, no accounts or external services. Responsive and ready to preview and publish.';
const todoPrompt = 'Build a React/Vite Todo app using browser localStorage only, with no login, backend or cloud dependency. This is the browser-storage benchmark substitute until Phase 7. Provide a labelled input id=task-title, button id=add-task, task rows data-task, completion checkboxes and per-task buttons data-delete. Add, complete and delete must persist across page reloads. Show loading/empty/error states and make it responsive and publishable.';
const definitions = [
  { id: 1, name: 'greeting', prompt: 'Hi', expected: 'CHAT' },
  { id: 2, name: 'how-work', prompt: 'how do you work?', expected: 'CHAT' },
  { id: 3, name: 'project-question', prompt: 'How does the counter on this page work?', expected: 'CHAT' },
  { id: 4, name: 'bug-report', prompt: 'page is blank', expected: 'BUILD' },
  { id: 5, name: 'small-edit', prompt: 'Change the heading to Benchmark Counter. Preserve the Increment button, #increment and #count behavior.', expected: 'BUILD' },
  { id: 6, name: 'new-static', prompt: staticPrompt, expected: 'BUILD', storage: 'NONE', fresh: true },
  { id: 7, name: 'new-vite-multi-page', prompt: vitePrompt, expected: 'BUILD', storage: 'BROWSER', fresh: true },
  { id: 8, name: 'new-todo-browser-substitute', prompt: todoPrompt, expected: 'BUILD', storage: 'BROWSER', fresh: true },
  { id: 9, name: 'plan-then-build-this', prompt: 'Add a Reset button id=reset to the existing counter that resets #count to 0. Preserve increment behavior. Plan the change first.', expected: 'PLAN' },
];

function sourceProof(root) {
  const files = {};
  function walk(directory, prefix = '') {
    if (!fs.existsSync(directory)) return;
    for (const entry of fs.readdirSync(directory, { withFileTypes: true })) {
      if (['.codepal', '.git', 'uploads', 'node_modules'].includes(entry.name)) continue;
      if (entry.isSymbolicLink()) throw Error('Synthetic public sources cannot contain symlinks');
      const name = prefix + entry.name;
      if (entry.isDirectory()) walk(path.join(directory, entry.name), name + '/');
      else files[name] = crypto.createHash('sha256').update(fs.readFileSync(path.join(directory, entry.name))).digest('hex');
    }
  }
  walk(root);
  return JSON.stringify(files);
}

function question(record, text) {
  if (text?.trim()) record.questionPrompts.add(text.trim().replace(/\s+/g, ' '));
}

async function waitRun(record, messageId) {
  for (const until = record.deadline; Date.now() < until;) {
    const runs = await api(`/api/projects/${record.projectId}/agent-runs`);
    const run = messageId ? runs.find(value => value.messageId === messageId) : runs[0];
    if (run && ['DONE', 'FAILED', 'CANCELLED', 'AWAITING_INPUT'].includes(run.status)) {
      if (run.status === 'DONE' && run.codeSavedAt && ['BUILDING', 'NOT_REQUESTED'].includes(run.previewStatus)) {
        await pause(500);
        continue;
      }
      const steps = await api(`/api/projects/${record.projectId}/agent-runs/${run.id}/steps`);
      for (const step of steps.filter(value => value.type === 'ASK_USER' || value.activity?.kind === 'ASK_USER')) {
        let payload;
        try { payload = typeof step.data === 'string' ? JSON.parse(step.data) : step.data; } catch { payload = null; }
        const questions = payload?.questions || [];
        if (questions.length) questions.forEach(value => question(record, value.prompt || value.question));
        else question(record, step.content || step.activity?.title);
      }
      const page = await api(`/api/projects/${record.projectId}/messages?size=100`);
      const reply = page.content.find(value => value.id === run.messageId);
      const text = (reply?.content || '').replace(/```[\s\S]*?```/g, '');
      for (const match of text.matchAll(/[^?\n]+\?/g)) question(record, match[0]);
      record.turns.push({ id: run.id, messageId: run.messageId, mode: run.mode, status: run.status,
        codeSavedAt: run.codeSavedAt, previewStatus: run.previewStatus, failureReason: run.failureReason,
        reply: reply?.content || '', suggestedAction: reply?.suggestedAction || null, planAvailable: run.planAvailable });
      record.build = { mode: run.mode, status: run.status, saved: !!run.codeSavedAt };
      record.preview = { status: run.previewStatus || 'NOT_REQUESTED', ready: run.previewStatus === 'READY' };
      return { run, reply };
    }
    await pause(700);
  }
  throw Error('Journey time budget exhausted');
}

async function post(record, prompt, mode, extra = {}) {
  const key = crypto.randomUUID().replaceAll('-', '').slice(0, 24);
  await api(`/api/projects/${record.projectId}/messages`, 'POST',
    { id: 'u_' + key, aiMessageId: 'a_' + key, message: prompt, mode, ...extra });
  return waitRun(record, 'a_' + key);
}

async function requirements(record, definition) {
  const prefix = `/api/projects/${record.projectId}/requirements`;
  // The current interview endpoints wait synchronously for model/projection work.
  const interview = (suffix, method = 'POST', body) => api(prefix + suffix, method, body,
    Math.max(1, Math.min(record.deadline - Date.now(), 240000)));
  let current = await interview('/start');
  for (let answered = 0; answered < 16 && current.status !== 'APPROVED';) {
    (current.questions || []).forEach(value => question(record, value.prompt));
    if (current.processing || current.artifactStatus === 'PENDING') {
      if (Date.now() >= record.deadline) throw Error('Requirements time budget exhausted');
      await pause(700); current = await interview('/start'); continue;
    }
    const next = current.questions?.find(value => !value.selectedOption && !value.otherText);
    if (!next) break;
    const options = next.options || [];
    const selected = next.key === 'platform_storage' ? definition.storage
      : next.key === 'platform_auth' ? 'NONE' : next.recommendedOption || options.find(value => value.recommended)?.id || options[0]?.id;
    let answer;
    if (next.type === 'TEXT') {
      answer = { otherText: definition.prompt };
    } else if (!selected || !options.some(value => value.id === selected)) {
      throw Error('Benchmark cannot choose an unsupported requirement option: ' + next.key);
    } else {
      answer = { selectedOption: selected,
        ...(selected.toLowerCase() === 'other' ? { otherText: definition.prompt } : {}) };
    }
    current = await interview('/questions/' + next.id, 'PUT', { ...answer, revision: current.revision });
    answered++;
  }
  (current.questions || []).forEach(value => question(record, value.prompt));
  if (current.status !== 'APPROVED') {
    if (!current.complete) throw Error('Requirements did not complete within the benchmark answer bound');
    current = await interview('/approve', 'POST', { revision: current.revision });
  }
  record.requirements = { status: current.status, revision: current.revision };
  const runs = await api(`/api/projects/${record.projectId}/agent-runs`);
  return runs.length ? waitRun(record) : post(record, definition.prompt, 'BUILD');
}

async function accept(record, definition, result) {
  const root = path.join(workspace, record.projectId);
  const last = result.run;
  record.build = { status: last.status, saved: !!last.codeSavedAt };
  record.preview = { status: last.previewStatus || 'NOT_REQUESTED', ready: last.previewStatus === 'READY' };
  if (definition.expected === 'CHAT') {
    const unchanged = sourceProof(root) === record.originalProof;
    record.routingCorrect = last.mode === 'CHAT' && !last.codeSavedAt && unchanged && !result.reply?.suggestedAction;
    const reply = result.reply?.content || '';
    const content = definition.id === 3 ? /counter/i.test(reply) && /increment|button/i.test(reply)
      : reply.trim().length > 0 && reply.trim().split(/\s+/).length <= 120;
    record.acceptance = { passed: record.routingCorrect && last.status === 'DONE' && content, sourceUnchanged: unchanged, usefulBriefReply: content };
    return;
  }
  record.routingCorrect = last.mode === 'BUILD'
    && (definition.id !== 9 || (record.turns[0]?.mode === 'PLAN' && record.planSourceUnchanged));
  if (!last.codeSavedAt || last.previewStatus !== 'READY' || last.status !== 'DONE') {
    record.acceptance = { passed: false, reason: 'No completed saved build with preview READY' }; return;
  }
  let actions = counterActions;
  if (definition.id === 5) actions = [action('heading', 'ASSERT_TEXT', 'h1', 'Benchmark Counter'), ...counterActions.slice(1)];
  if (definition.id === 6) actions = [action('bakery', 'ASSERT_TEXT', 'h1', 'Solar Bakery'),
    action('menu', 'ASSERT_COUNT', '#menu', '1'), action('contact', 'CLICK', 'a[href="#contact"]'),
    action('contact-section', 'ASSERT_COUNT', '#contact', '1')];
  if (definition.id === 7) actions = [action('classes', 'CLICK', 'a[href="/classes"]'), action('classes-route', 'ASSERT_ROUTE', null, '/classes'),
    action('yoga', 'ASSERT_TEXT', 'body', 'Yoga'), action('contact', 'CLICK', 'a[href="/contact"]'),
    action('contact-route', 'ASSERT_ROUTE', null, '/contact'), action('name', 'FILL', '#name', 'Synthetic visitor'),
    action('email', 'FILL', '#email', 'visitor@example.invalid'), action('message', 'FILL', '#message', 'Synthetic enquiry'),
    action('send', 'CLICK', '#send'), action('sent', 'ASSERT_TEXT', '#contact-status', 'Message received')];
  if (definition.id === 8) actions = [action('title', 'FILL', '#task-title', 'Bake bread'), action('add', 'CLICK', '#add-task'),
    action('task', 'ASSERT_TEXT', '[data-task]', 'Bake bread'), action('complete', 'CLICK', '[data-task] input[type=checkbox]'),
    action('checked', 'ASSERT_COUNT', '[data-task] input:checked', '1'), action('reload', 'RELOAD'),
    action('persisted-task', 'ASSERT_TEXT', '[data-task]', 'Bake bread'), action('persisted-completion', 'ASSERT_COUNT', '[data-task] input:checked', '1'),
    action('delete', 'CLICK', '[data-task] [data-delete]'), action('empty', 'ASSERT_COUNT', '[data-task]', '0'),
    action('reload-deletion', 'RELOAD'), action('deletion-persisted', 'ASSERT_COUNT', '[data-task]', '0')];
  if (definition.id === 9) actions = [...counterActions, action('reset', 'CLICK', '#reset'), action('reset-value', 'ASSERT_TEXT', '#count', '0')];
  record.acceptance = browserCheck(root, actions, owner, browserIndex++);
  record.publishCapability = await api(`/api/projects/${record.projectId}/publish-capability`);
  record.acceptance.passed = record.acceptance.passed && record.publishCapability.available === true;
}

async function stopActive(record) {
  const runs = await api(`/api/projects/${record.projectId}/agent-runs`);
  const active = runs.filter(run => ['QUEUED', 'RUNNING', 'COMMITTING'].includes(run.status));
  for (const run of active.filter(value => value.status !== 'COMMITTING')) {
    await api(`/api/projects/${record.projectId}/agent-runs/${run.id}/cancel`, 'POST');
  }
  if (!active.length) return;
  for (const until = Date.now() + 20000; Date.now() < until;) {
    if (!(await api(`/api/projects/${record.projectId}/agent-runs`)).some(run => ['QUEUED', 'RUNNING', 'COMMITTING'].includes(run.status))) return;
    await pause(500);
  }
  throw Error('Unable to quiesce the owned journey; refusing to run the next one');
}

(async () => {
  await client.signup();
  workspaceId = (await api('/api/workspaces'))[0].id;
  for (const definition of definitions) {
    const start = Date.now();
    const record = { journey: definition.id, name: definition.name, startedAt: new Date(start).toISOString(),
      projectId: null, routingCorrect: false, turns: [], questionPrompts: new Set(),
      build: { status: 'NOT_RUN', saved: false }, preview: { status: 'NOT_RUN', ready: false },
      deadline: start + 900000, acceptance: { passed: false, reason: 'Not completed' } };
    journeys.push(record);
    try {
      await client.renew();
      const project = await api(`/api/workspaces/${workspaceId}/projects`, 'POST', { name: 'Benchmark ' + definition.id,
        type: definition.id === 9 ? 'PLAN' : 'BUILD', ...(definition.fresh ? { pendingPrompt: definition.prompt } : {}) });
      record.projectId = project.id;
      if (!/^[a-f0-9-]{36}$/.test(project.id)) throw Error('Unexpected synthetic project identity');
      projects.push({ id: project.id }); save('projects', { owner, projects });
      if (projects.length === 1) save('project', { id: project.id });
      const root = path.join(workspace, project.id);
      fs.mkdirSync(root, { recursive: true });
      if (!definition.fresh) {
        const source = definition.id === 4 ? counter.replace('<main id="app">', '<main id="app" hidden>')
          .replace('<script>', '<script>document.querySelector("#missing").hidden=false;') : counter;
        fs.writeFileSync(path.join(root, 'index.html'), source);
      }
      record.originalProof = sourceProof(root);
      if (definition.id === 4 && browserCheck(root, counterActions, owner, browserIndex++).passed) throw Error('Blank-page fixture was not broken');
      let result = definition.fresh && project.lifecycleStage === 'REQUIREMENTS'
        ? await requirements(record, definition) : await post(record, definition.prompt, definition.expected === 'PLAN' ? 'PLAN' : 'BUILD');
      if (definition.id === 9) {
        record.planSourceUnchanged = sourceProof(root) === record.originalProof && !result.run.codeSavedAt;
        const offer = result.reply?.suggestedAction;
        if (!offer) throw Error('PLAN reply has no Build this action');
        if (result.run.planAvailable) {
          const plan = await api(`/api/projects/${project.id}/agent-runs/${result.run.id}/plan`);
          record.planContractId = plan?.id;
          if (plan?.state === 'DRAFT') await api(`/api/projects/${project.id}/agent-runs/${result.run.id}/plan/approve`, 'POST',
            { contractId: plan.id, reviewedHash: plan.hash });
        }
        result = await post(record, offer.message, 'BUILD', { proposalId: offer.proposalId, attachments: offer.attachments });
      }
      await accept(record, definition, result);
    } catch (error) {
      record.error = error.message;
      if (record.projectId) await stopActive(record);
    } finally {
      record.wallMs = Date.now() - start;
      record.finishedAt = new Date().toISOString();
      record.questionsAsked = record.questionPrompts.size;
      record.questionPrompts = [...record.questionPrompts];
      delete record.deadline;
      delete record.originalProof;
      save('benchmark-journeys', { schema: 1, owner, journeys });
      console.log(JSON.stringify({ journey: record.journey, routingCorrect: record.routingCorrect,
        acceptance: record.acceptance.passed, wallMs: record.wallMs }));
    }
  }
})().catch(error => {
  save('benchmark-failure', { error: error.message }); console.error(error.message); process.exitCode = 1;
});
