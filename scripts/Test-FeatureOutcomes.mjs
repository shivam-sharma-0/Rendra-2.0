import test from 'node:test';
import assert from 'node:assert/strict';
import {runOutcomes} from '../src/main/resources/sandbox/browser/outcomes.mjs';

const crud = {id:'tasks', kind:'BROWSER_CRUD', field:'title'};
const contact = {id:'contact', kind:'FORM_FEEDBACK', field:null};

// Protocol/state-machine checks only. Real native DOM/Chromium fixtures are a separate required gate.
function page({persist=true, feedback=true, deletion=true}={}) {
  let rows=[], saved=[], editing=false, input='', clock=0;
  const phases=[], fixtures=[];
  return {
    phases, fixtures,
    options:{budgetMs:5000, stepTimeoutMs:150, now:() => clock, wait:async milliseconds => { clock += milliseconds; }},
    async evaluate(definition, phase, fixture) {
      phases.push(phase);
      if (phase === 'prepare') { input=fixture.key; fixtures.push(fixture.key); return {ok:true, baseline:'[]'}; }
      if (phase === 'submit') { if (editing) rows=[input]; else rows.push(input); if (persist) saved=[...rows]; return {ok:true}; }
      if (phase === 'created') return {ok:rows.length===1 && rows[0]===fixture.key};
      if (phase === 'edit') { editing=true; return {ok:true}; }
      if (phase === 'replace') { input=fixture.replacement; return {ok:true}; }
      if (phase === 'updated') return {ok:rows.length===1 && rows[0]===fixture.replacement && !rows.includes(fixture.key)};
      if (phase === 'delete') { if (deletion) rows=[]; if (persist) saved=[...rows]; return {ok:true}; }
      if (phase === 'deleted') return {ok:rows.length===0};
      if (phase === 'feedback') return {ok:feedback};
      return {ok:true};
    },
    async reload() { phases.push('reload'); rows=[...saved]; return true; },
  };
}

test('browser CRUD requires changed data and three independently observed reloads', async () => {
  const fixture=page();
  assert.deepEqual(await runOutcomes([crud], fixture, fixture.options), [{id:'tasks', outcome:'PASS'}]);
  assert.deepEqual(fixture.phases, ['ready','prepare','submit','created','reload','created','edit','replace','submit',
    'updated','reload','updated','delete','deleted','reload','deleted']);
});

test('a create that exists only in memory fails after reload', async () => {
  const fixture=page({persist:false});
  assert.deepEqual(await runOutcomes([crud], fixture, fixture.options), [{id:'tasks', outcome:'FAIL', phase:'reload_created'}]);
  assert(!fixture.phases.includes('edit'));
});

test('a nonworking delete cannot pass just because creation and update worked', async () => {
  const fixture=page({deletion:false});
  assert.equal((await runOutcomes([crud], fixture, fixture.options))[0].outcome, 'FAIL');
});

test('contact feedback is independently observed after native submission', async () => {
  const fixture=page();
  assert.deepEqual(await runOutcomes([contact], fixture, fixture.options), [{id:'contact', outcome:'PASS'}]);
  const broken=page({feedback:false});
  assert.deepEqual(await runOutcomes([contact], broken, broken.options), [{id:'contact', outcome:'FAIL', phase:'feedback'}]);
});

test('each validation gets fresh input that was not part of a model recipe', async () => {
  const first=page(), second=page();
  await runOutcomes([crud], first, first.options);
  await runOutcomes([crud], second, second.options);
  assert.notEqual(first.fixtures[0], second.fixtures[0]);
  assert.match(first.fixtures[0], /^check-[a-f0-9]{16}$/);
});

test('arbitrary selectors, scripts, duplicate ids and unsupported kinds are never executed', async () => {
  for (const definitions of [[{...crud, id:'x"] script'}], [crud, crud], [{...crud, kind:'RUN_JAVASCRIPT'}],
    [{...crud, field:'title;fetch()'}]]) {
    let called=false;
    await assert.rejects(runOutcomes(definitions, {evaluate(){called=true;}}));
    assert.equal(called, false);
  }
});

test('unavailable transport preserves one required outcome per declared feature', async () => {
  const result=await runOutcomes([crud, contact], {evaluate(){throw Error('CDP unavailable');}});
  assert.deepEqual(result, [{id:'tasks', outcome:'SKIPPED', phase:'ready'}, {id:'contact', outcome:'SKIPPED', phase:'ready'}]);
});

test('failed native actions retain actionable reason codes without echoing arbitrary DOM text', async () => {
  for (const reason of ['FIELD_MISSING', 'EDIT_BUTTON_AMBIGUOUS_OR_MISSING', '<untrusted project text>']) {
    const fixture=page();
    const evaluate=fixture.evaluate;
    fixture.evaluate=async (definition, phase, input) => phase === 'prepare' ? {ok:false, reason} : evaluate(definition, phase, input);
    assert.deepEqual(await runOutcomes([crud], fixture, fixture.options),
      [{id:'tasks', outcome:'FAIL', phase:'prepare', ...(reason.startsWith('<') ? {} : {reason})}]);
  }
});

test('a spent browser budget leaves remaining coverage explicitly skipped', async () => {
  const fixture=page();
  const result=await runOutcomes([crud, contact], fixture, {...fixture.options, budgetMs:1});
  assert.deepEqual(result, [{id:'tasks', outcome:'SKIPPED', phase:'submit'}, {id:'contact', outcome:'SKIPPED'}]);
  assert(!fixture.phases.includes('submit'), 'No submission starts after the budget is spent');
});
