// Actual hook callbacks with controlled React state; no browser or network.
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const assert = require('node:assert/strict');
const { test } = require('node:test');
const root = path.resolve(__dirname, '..');
const ts = require(path.join(root, 'frontend/node_modules/typescript'));
function compile(relative) {
  return ts.transpileModule(fs.readFileSync(path.join(root, relative), 'utf8'), {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 },
  }).outputText;
}
const helper = { exports: {} };
vm.runInNewContext(compile('frontend/src/lib/requirements.ts'), { module: helper, exports: helper.exports });
const compiled = compile('frontend/src/pages/project/useRequirements.ts');
const platform = { id: 'storage', key: 'platform_storage', required: true, selectedOption: null,
  otherText: null, recommendedOption: 'BROWSER', options: [{ id: 'BROWSER' }] };
const product = { id: 'view', key: 'task_view', required: true, selectedOption: null,
  otherText: null, recommendedOption: 'LIST', options: [{ id: 'LIST' }] };

function fixture(questions) {
  const session = { questions, revision: 1, complete: false, status: 'IN_PROGRESS', processing: false };
  const states = [session, false, ''];
  const calls = [];
  let index = 0;
  const module = { exports: {} };
  vm.runInNewContext(compiled, { module, exports: module.exports, require: name => {
    if (name === 'react') return { useEffect() {}, useRef: value => ({ current: value }),
      useCallback: callback => callback, useState: () => {
        const key = index++;
        return [states[key], value => { states[key] = value; }];
      } };
    if (name.endsWith('/lib/requirements')) return helper.exports;
    if (name === './runUtils') return { needsRequirementInterview: () => false };
    if (name.endsWith('/api/projects')) return { requirementsApi: {
      answer: async (...args) => {
        calls.push(['answer', ...args]);
        return { ...session, revision: session.revision + 1, complete: true, status: 'APPROVAL_REQUIRED' };
      },
      approve: async (...args) => { calls.push(['approve', ...args]); return { ...session, status: 'APPROVED' }; },
    } };
    throw Error('Unexpected hook dependency: ' + name);
  } }, { filename: 'useRequirements.js' });
  const hook = module.exports.useRequirements('synthetic-project', null, () => {}, () => calls.push(['completed']));
  return { hook, states, calls };
}

test('Skip all cannot replace an unresolved backend choice with browser storage', async () => {
  const f = fixture([product, platform]);
  await f.hook.skipRequirements();
  assert.deepEqual(f.calls, []);
  assert.match(f.states[2], /Choose the required application setup/);
});

test('Skip all retains an inferred backend and accepts ordinary recommendations', async () => {
  const f = fixture([{ ...platform, selectedOption: 'MERN' }, product]);
  await f.hook.skipRequirements();
  assert.deepEqual(f.calls.map(call => call[0]), ['answer', 'approve', 'completed']);
  assert.equal(f.calls[0][2], 'view');
  assert.equal(f.calls[0][3].selectedOption, 'LIST');
  assert.equal(f.calls[1][2], 2, 'approval must use the saved answer revision');
});
