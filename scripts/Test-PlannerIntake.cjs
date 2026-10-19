const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const {createBenchmarkApi} = require('./AgentBenchmarkAuth.cjs');
const fixture = require('./fixtures/planner-intake.json');

const [base, workspace, evidence] = process.argv.slice(2);
const owner = path.basename(evidence);
if (!/^codex_e2e_[a-f0-9]{12}$/.test(owner)) throw Error('Owned synthetic intake evidence required');
const save = (name, value) => fs.writeFileSync(path.join(evidence, name + '.json'), JSON.stringify(value, null, 2));

(async () => {
  const identity = crypto.randomUUID().replaceAll('-', '');
  const client = createBenchmarkApi(base, {email:`benchmark-${identity}@example.invalid`,
    password:crypto.randomBytes(16).toString('hex'), name:'Synthetic product intake'});
  await client.signup();
  const workspaces = await client.api('/api/workspaces');
  const project = await client.api(`/api/workspaces/${workspaces[0].id}/projects`, 'POST',
    {name:fixture.name, type:'BUILD', pendingPrompt:fixture.prompt});
  if (!/^[a-f0-9-]{36}$/.test(project.id)) throw Error('Unexpected synthetic project identity');
  save('project', {id:project.id});
  save('projects', {owner, projects:[{id:project.id}]});
  const session = await client.api(`/api/projects/${project.id}/requirements/start`, 'POST', undefined, 180000);
  save('requirements', session);
  const unanswered = session.questions.filter(question => question.required && !question.selectedOption && !question.otherText);
  const productQuestions = session.questions.filter(question => !question.key.startsWith('platform_'));
  const storage = session.questions.find(question => question.key === 'platform_storage');
  const pages = session.review?.pages || [];
  const passed = session.complete && session.status === 'APPROVAL_REQUIRED' && unanswered.length === 0
    && productQuestions.length === 0 && storage?.selectedOption === 'MERN' && pages.length >= 2
    && session.review?.features?.length > 0;
  const result = {schema:1, owner, projectId:project.id, passed, unanswered:unanswered.length,
    productQuestions:productQuestions.length, storage:storage?.selectedOption, pages,
    featureCount:session.review?.features?.length || 0, approved:false, generated:false, published:false};
  save('planner-intake', result);
  console.log(JSON.stringify(result));
  if (!passed) throw Error('Product planning did not produce a complete default backend plan without redundant questions');
})().catch(error => {console.error(error.message); process.exitCode = 1;});
