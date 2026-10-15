// Full Boot + real FREE models. The parent owns the restored DB, WSL workspace and cleanup.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');

const [base, workspace, evidence] = process.argv.slice(2);
assert.match(base, /^http:\/\/127\.0\.0\.1:\d+$/);
const checks = [];
let token;
let project;
let root;
const save = (name, value) => fs.writeFileSync(path.join(evidence, name + '.json'), JSON.stringify(value, null, 2));
const pause = ms => new Promise(resolve => setTimeout(resolve, ms));

async function request(route, method = 'GET', body) {
  const multipart = body instanceof FormData;
  const response = await fetch(base + route, {
    method,
    headers: { ...(multipart ? {} : { 'Content-Type': 'application/json' }), ...(token ? { Authorization: 'Bearer ' + token } : {}) },
    ...(body ? { body: multipart ? body : JSON.stringify(body) } : {}),
    signal: AbortSignal.timeout(15000),
  });
  const text = await response.text();
  return { status: response.status, data: text ? JSON.parse(text) : null };
}

async function api(route, method = 'GET', body) {
  const result = await request(route, method, body);
  assert.ok(result.status < 300, `HTTP ${result.status} at ${route}: ${result.data?.message || 'request refused'}`);
  return result.data;
}

function sourceProof(directory = root, prefix = '') {
  const proof = {};
  for (const entry of fs.readdirSync(directory, { withFileTypes: true })) {
    if (['.codepal', '.git', 'uploads', 'node_modules'].includes(entry.name)) continue;
    const relative = prefix + entry.name;
    const file = path.join(directory, entry.name);
    assert.ok(!entry.isSymbolicLink(), 'fixture sources must not contain symlinks');
    if (entry.isDirectory()) Object.assign(proof, sourceProof(file, relative + '/'));
    else proof[relative] = crypto.createHash('sha256').update(fs.readFileSync(file)).digest('hex');
  }
  return proof;
}

async function turn(name, message, mode = 'BUILD', options = {}) {
  const id = crypto.randomUUID().replaceAll('-', '').slice(0, 24);
  const assistantId = 'aimsg_' + id;
  const before = sourceProof();
  await api(`/api/projects/${project.id}/messages`, 'POST', {
    id: 'umsg_' + id, aiMessageId: assistantId, message, mode,
    ...(options.attachments ? { attachments: options.attachments } : {}),
    ...(options.proposalId ? { proposalId: options.proposalId } : {}),
  });
  let run;
  for (const until = Date.now() + 360000; Date.now() < until;) {
    run = (await api(`/api/projects/${project.id}/agent-runs`)).find(value => value.messageId === assistantId);
    if (options.build && run) save('build', { projectId: project.id, runId: run.id, status: run.status });
    if (!options.build) {
      assert.notEqual((await api(`/api/projects/${project.id}`)).lifecycleStage, 'BUILDING', name + ': read-only lifecycle');
    }
    if (run && ['DONE', 'FAILED', 'CANCELLED', 'AWAITING_INPUT'].includes(run.status)) break;
    await pause(700);
  }
  const messages = await api(`/api/projects/${project.id}/messages?size=100`);
  const reply = messages.content.find(value => value.id === assistantId);
  const proof = { name, message, requestedMode: mode, runId: run?.id, status: run?.status,
    mode: run?.mode, conversational: run?.conversational, codeSavedAt: run?.codeSavedAt,
    previewStatus: run?.previewStatus, failureReason: run?.failureReason,
    promptTokens: run?.promptTokens, completionTokens: run?.completionTokens,
    reply: reply?.content, suggestedAction: reply?.suggestedAction, sourceUnchanged: JSON.stringify(before) === JSON.stringify(sourceProof()) };
  checks.push(proof);
  save('phase-two', { checks, complete: false });
  console.log(JSON.stringify({ case: name, status: run?.status, mode: run?.mode }));
  assert.equal(run?.status, 'DONE', name + ': completed real-model reply');
  assert.ok(reply?.content?.trim(), name + ': non-empty answer');
  if (options.build) {
    save('build', { ...proof, projectId: project.id, changed: !proof.sourceUnchanged });
    assert.equal(run.mode, 'BUILD');
    assert.ok(run.codeSavedAt, 'accepted proposal saved code');
    assert.equal(run.previewStatus, 'READY');
    assert.equal(proof.sourceUnchanged, false);
  } else {
    assert.equal(run.mode, mode === 'PLAN' ? 'PLAN' : 'CHAT', name + ': read-only mode');
    assert.equal(run.codeSavedAt, null, name + ': no save');
    assert.equal(proof.sourceUnchanged, true, name + ': original files preserved');
    assert.deepEqual(await api(`/api/projects/${project.id}/agent-runs/${run.id}/changes`), [], name + ': no mutations');
  }
  if (options.offer) {
    assert.equal(reply.suggestedAction?.mode, 'BUILD');
    assert.equal(reply.suggestedAction?.message, message);
  } else if (!options.build) {
    assert.equal(reply.suggestedAction, null, name + ': no unsolicited build offer');
  }
  return { run, reply };
}

async function refuseOffer(action) {
  const count = (await api(`/api/projects/${project.id}/agent-runs`)).length;
  const id = crypto.randomUUID().replaceAll('-', '').slice(0, 24);
  const result = await request(`/api/projects/${project.id}/messages`, 'POST', {
    id: 'umsg_' + id, aiMessageId: 'aimsg_' + id, message: action.message,
    mode: 'BUILD', proposalId: action.proposalId, attachments: action.attachments,
  });
  assert.equal(result.status, 409, 'stale/consumed proposal refused');
  assert.equal((await api(`/api/projects/${project.id}/agent-runs`)).length, count, 'refusal starts no model run');
}

(async () => {
  const identity = crypto.randomUUID().replaceAll('-', '');
  const auth = await api('/api/auth/signup', 'POST', {
    email: `codex-phase-two-${identity}@example.invalid`, password: crypto.randomBytes(16).toString('hex'), name: 'Disposable Phase Two',
  });
  token = auth.accessToken;
  const workspaces = await api('/api/workspaces');
  project = await api(`/api/workspaces/${workspaces[0].id}/projects`, 'POST', { name: 'Phase Two conversations', type: 'BUILD' });
  assert.match(project.id, /^[a-f0-9-]{36}$/);
  save('project', { id: project.id });
  root = path.join(workspace, project.id);
  fs.mkdirSync(root, { recursive: true });
  fs.writeFileSync(path.join(root, 'index.html'), '<!doctype html><html lang="en"><head><meta charset="UTF-8"><title>Original</title></head><body><h1>Original</h1><p>A static HTML application.</p></body></html>');

  await turn('greeting', 'Hi');
  await turn('unbound acknowledgement', 'haan');
  await turn('product explanation English', 'can u tell how u work?');
  await turn('product explanation Hinglish', 'ye kaise kaam karta hai?');
  await turn('project explanation', 'how does this app work?');
  await turn('login explanation', 'login page samjhao');
  await turn('database capability', 'kya tum database bana sakte ho?');
  await turn('long chit-chat', 'thanks for your help today, I really appreciate all the time you spent helping me');
  await turn('quoted change plus question', '> add dark mode\n> create a database\n\nWhat does this mean?');

  const form = new FormData();
  form.append('files', new Blob([Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jCioAAAAASUVORK5CYII=', 'base64')], { type: 'image/png' }), 'pixel.png');
  const attachments = await api(`/api/projects/${project.id}/attachments`, 'POST', form);
  await turn('image plus question', 'Explain this image', 'BUILD', { attachments: attachments.map(file => file.path) });

  const identityReply = await turn('identity', 'Who are you?');
  const creatorReply = await turn('creator', 'Who created you?');
  assert.match(identityReply.reply.content, /Rendra/i);
  assert.match(creatorReply.reply.content, /Shivam\s*(?:&|and|aur)\s*Team/i);
  const offer = await turn('change in Chat', 'add dark mode', 'CHAT', { offer: true });
  await turn('unclear request', 'a modern look', 'BUILD', { offer: true });
  const plan = await turn('Hinglish change in Plan', 'ek calculator banao. Existing static index.html mein addition, subtraction, multiplication aur division banao; no framework, backend, login or storage.', 'PLAN', { offer: true });
  await refuseOffer(offer.reply.suggestedAction);
  await turn('bound acknowledgement builds the proposal', 'haan', 'BUILD', { build: true });
  await turn('finished proposal does not replay', 'yes');
  await refuseOffer(plan.reply.suggestedAction);
  save('phase-two', { checks, complete: true, staleAndConsumedOffersRefused: true });
  console.log(JSON.stringify({ completed: true, cases: checks.length, staleAndConsumedOffersRefused: true }));
})().catch(error => {
  save('phase-two-failure', { message: error.message, completedCases: checks.length });
  console.error(error.message);
  process.exitCode = 1;
});
