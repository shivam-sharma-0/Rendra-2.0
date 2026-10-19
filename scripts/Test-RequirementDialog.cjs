// Component callback checks with deterministic hook state; this is not a browser/E2E test.
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const assert = require('node:assert/strict');
const root = path.resolve(__dirname, '..');
const ts = require(path.join(root, 'frontend/node_modules/typescript'));
const source = fs.readFileSync(path.join(root, 'frontend/src/pages/project/dialogs/RequirementDialog.tsx'), 'utf8');
const compiled = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.CommonJS, jsx: ts.JsxEmit.ReactJSX, target: ts.ScriptTarget.ES2022 } }).outputText;
const helperModule = { exports: {} };
vm.runInNewContext(ts.transpileModule(fs.readFileSync(path.join(root, 'frontend/src/lib/requirements.ts'), 'utf8'),
  { compilerOptions: { module: ts.ModuleKind.CommonJS } }).outputText, {exports:helperModule.exports, module:helperModule});
const question = { id: 'auth', key: 'access_rule', prompt: 'Which access rule?', required: true, selectedOption: 'NONE', otherText: null,
  options: [{ id: 'NONE', label: 'No sign-in' }, { id: 'ACCOUNTS', label: 'Real accounts' }] };
const session = { questions: [question], status: 'APPROVAL_REQUIRED', complete: true, processing: false, revision: 2, reviewStatus: 'MODEL' };

function render(value, state = {}, component = 'RequirementDialog') {
  let index = 0;
  const module = { exports: {} };
  const element = (type, props) => ({ type, props });
  const context = { exports: module.exports, module, require: (id) => {
    if (id === 'react') return { useEffect() {}, useState(initial) { const current = index++; return [Object.hasOwn(state, current) ? state[current] : initial, () => {}]; } };
    if (id === 'react/jsx-runtime') return { jsx: element, jsxs: element };
    if (id.endsWith('/neu')) return Object.fromEntries(['NeuAlert', 'NeuButton', 'NeuDialog', 'NeuIconButton'].map(name => [name, name]));
    if (id.endsWith('/icons')) return { AppIcon: 'AppIcon' };
    if (id.endsWith('/cx')) return { cx: (...values) => values.filter(Boolean).join(' ') };
    if (id.endsWith('/requirements')) return helperModule.exports;
    if (id.endsWith('.css')) return {};
    throw Error('Unexpected component dependency: ' + id);
  } };
  vm.runInNewContext(compiled, context, { filename: 'RequirementDialog.js' });
  const events = [];
  const tree = module.exports[component]({ session: value, busy: false, error: '', onPreview:() => {},
    onAnswer: async (...args) => events.push(['answer', ...args]), onApprove: async () => events.push(['approve']), onSkipAll: async () => {} });
  return { tree, events };
}
function all(value, found = []) {
  if (!value || typeof value !== 'object') return found;
  if (Array.isArray(value)) value.forEach(item => all(item, found));
  else { if (value.type) found.push(value); Object.values(value.props || {}).forEach(item => all(item, found)); }
  return found;
}
const button = result => all(result.tree).find(node => node.type === 'NeuButton' && node.props.variant === 'accent');
const settle = () => new Promise(resolve => setImmediate(resolve));

(async () => {
  let result = render({ ...session, status: 'IN_PROGRESS', complete: false });
  button(result).props.onClick(); await settle();
  assert.deepEqual(result.events.map(event => event[0]), ['answer'], 'last answer must not auto-approve a new revision');

  result = render(session); button(result).props.onClick(); await settle();
  assert.deepEqual(result.events.map(event => event[0]), ['approve']);

  result = render(session, { 1: 'ACCOUNTS' });
  assert.equal(button(result).props.children, 'Review answers');
  button(result).props.onClick(); await settle(); assert.equal(result.events[0][2], 'ACCOUNTS');
  assert.equal(result.events.length, 1, 'unsaved choice must be saved before approval');

  result = render({ ...session, processing: true }); assert.equal(button(result).props.disabled, true);

  const custom = { ...question, key: 'features', selectedOption: 'custom_text', otherText: 'Keyboard shortcuts', options: [{ id: 'a', label: 'Basic' }, { id: 'custom_text', label: 'Custom answer' }] };
  result = render({ ...session, status: 'IN_PROGRESS', questions: [custom] });
  button(result).props.onClick(); await settle();
  assert.equal(result.events[0][2], 'custom_text'); assert.equal(result.events[0][3], 'Keyboard shortcuts');

  result = render({ ...session, reviewStatus: 'FALLBACK' });
  assert.ok(all(result.tree).some(node => node.props.children === 'Automatic review was unavailable. Check these answers before approving.'));

  result = render({ ...session, review: { goal: 'Track tasks', pages: ['Tasks'], answersChanged: true,
    features: [{ id: 'loading', description: 'Show progress', checkPlanned: false }] } });
  assert.ok(all(result.tree).some(node => node.props.children === 'Show progress'));
  assert.ok(all(result.tree).some(node => node.props.children === 'Your saved answers take priority over this earlier draft.'));
  assert.ok(all(result.tree).some(node => node.props.children === 'Some features still need verification during the build. They remain part of your request.'));

  result = render({ ...session, questions: [], complete: false, processing: true });
  assert.equal(result.tree.props.role, 'status');

  const automatic = { ...session, questions: [{...question, key:'platform_auth', prompt:'Technical auth choice?'}],
    review:{goal:'A complete task application', pages:['Tasks', 'Task details', 'Settings'], features:[], answersChanged:false} };
  result = render(automatic);
  assert.equal(result.tree.props.title, 'Your application plan');
  assert.equal(all(result.tree).some(node => node.props.role === 'radiogroup'), false);
  button(result).props.onClick(); await settle();
  assert.deepEqual(result.events.map(event => event[0]), ['approve']);
  result = render(automatic, {}, 'RequirementAnswerCard');
  assert.equal(JSON.stringify(result.tree).includes('Technical auth choice?'), false);
  assert.equal(JSON.stringify(result.tree).includes('Task details'), true);
  result = render({...automatic, questions:[{...automatic.questions[0], selectedOption:null}], complete:false, status:'IN_PROGRESS'});
  assert.equal(result.tree.props.title, 'Technical auth choice?', 'unresolved infrastructure choices cannot be hidden as success');
  assert.equal(all(result.tree).find(node => node.type === 'NeuButton' && node.props.children === 'Skip all').props.disabled, true);
  assert.equal(button(result).props.disabled, true, 'an unresolved setup recommendation must require an explicit choice');
  result = render({...automatic, review:null});
  assert.equal(result.tree.props.title, 'Review your request');
  assert.equal(JSON.stringify(result.tree).includes('A detailed plan was unavailable'), true);
  result = render({...automatic, review:null}, {}, 'RequirementAnswerCard');
  assert.equal(JSON.stringify(result.tree).includes('App plan ready'), false);
  process.stdout.write('13 requirement dialog callback/state checks passed (no browser or network).\n');
})().catch(error => { process.stderr.write(error.stack + '\n'); process.exitCode = 1; });
