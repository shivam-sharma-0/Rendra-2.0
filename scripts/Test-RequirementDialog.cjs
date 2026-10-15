// Component callback checks with deterministic hook state; this is not a browser/E2E test.
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const assert = require('node:assert/strict');
const root = path.resolve(__dirname, '..');
const ts = require(path.join(root, 'frontend/node_modules/typescript'));
const source = fs.readFileSync(path.join(root, 'frontend/src/pages/project/dialogs/RequirementDialog.tsx'), 'utf8');
const compiled = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.CommonJS, jsx: ts.JsxEmit.ReactJSX, target: ts.ScriptTarget.ES2022 } }).outputText;
const question = { id: 'auth', key: 'platform_auth', prompt: 'Sign in?', required: true, selectedOption: 'NONE', otherText: null,
  options: [{ id: 'NONE', label: 'No sign-in' }, { id: 'ACCOUNTS', label: 'Real accounts' }] };
const session = { questions: [question], status: 'APPROVAL_REQUIRED', complete: true, processing: false, revision: 2, reviewStatus: 'MODEL' };

function render(value, state = {}) {
  let index = 0;
  const module = { exports: {} };
  const element = (type, props) => ({ type, props });
  const context = { exports: module.exports, module, require: (id) => {
    if (id === 'react') return { useEffect() {}, useState(initial) { const current = index++; return [Object.hasOwn(state, current) ? state[current] : initial, () => {}]; } };
    if (id === 'react/jsx-runtime') return { jsx: element, jsxs: element };
    if (id.endsWith('/neu')) return Object.fromEntries(['NeuAlert', 'NeuButton', 'NeuDialog', 'NeuIconButton'].map(name => [name, name]));
    if (id.endsWith('/icons')) return { AppIcon: 'AppIcon' };
    if (id.endsWith('/cx')) return { cx: (...values) => values.filter(Boolean).join(' ') };
    if (id.endsWith('.css')) return {};
    throw Error('Unexpected component dependency: ' + id);
  } };
  vm.runInNewContext(compiled, context, { filename: 'RequirementDialog.js' });
  const events = [];
  const tree = module.exports.RequirementDialog({ session: value, busy: false, error: '',
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

  result = render({ ...session, questions: [], complete: false, processing: true });
  assert.equal(result.tree.props.role, 'status');
  process.stdout.write('7 requirement dialog callback/state checks passed (no browser or network).\n');
})().catch(error => { process.stderr.write(error.stack + '\n'); process.exitCode = 1; });
