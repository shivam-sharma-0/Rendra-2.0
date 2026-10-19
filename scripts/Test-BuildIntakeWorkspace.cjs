// Workspace hook boundary checks; these do not execute a browser or a backend.
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const assert = require('node:assert/strict');
const {test} = require('node:test');
const root = path.resolve(__dirname, '..');
const ts = require(path.join(root, 'frontend/node_modules/typescript'));
const source = fs.readFileSync(path.join(root, 'frontend/src/pages/project/useProjectWorkspace.ts'), 'utf8');
const compiled = ts.transpileModule(source, {compilerOptions:{module:ts.ModuleKind.CommonJS, target:ts.ScriptTarget.ES2022}}).outputText;
const project = {id:'synthetic-project', type:'BUILD', lifecycleStage:'DESIGN'};
const request = {id:'user-build', projectId:project.id, role:'USER', mode:'BUILD', content:'Build a task app'};
const interview = {...project, lifecycleStage:'REQUIREMENTS'};
const settle = () => new Promise(resolve => setImmediate(resolve));

function fixture({created=[request], postError, stageError}={}) {
  const states = [project];
  let index=0, onRealtime, gets=0;
  const post = async () => { if(postError)throw Error(postError); return created; };
  const get = async () => { gets++; if(stageError)throw Error(stageError); return interview; };
  const module = {exports:{}};
  const react = {
    useState(initial) {
      const key=index++;
      if(!(key in states))states[key]=typeof initial==='function'?initial():initial;
      return [states[key], value => {states[key]=typeof value==='function'?value(states[key]):value;}];
    },
    useRef: value => ({current:value}), useMemo: work => work(), useCallback: work => work, useEffect() {},
  };
  vm.runInNewContext(compiled, {exports:module.exports, module, Error, window:{setTimeout() {}}, require: name => {
    if(name==='react')return react;
    if(name==='@tanstack/react-query')return {useQueryClient:()=>({invalidateQueries:async()=>{}})};
    if(name.endsWith('/api/projects'))return {messageApi:{post},projectApi:{get},agentApi:{list:async()=>[]}};
    if(name.endsWith('/api/queries'))return {qk:{}};
    if(name.endsWith('/realtime/useRealtime'))return {useRealtime: value => {onRealtime=value.onMessage;}};
    if(name==='./runUtils')return {isActive:()=>false};
    if(name==='./workspaceTypes')return {PAGE_SIZE:50,TIMELINE_EVENT_TYPES:new Set()};
    throw Error('Unexpected hook dependency: '+name);
  }}, {filename:'useProjectWorkspace.js'});
  const hook=module.exports.useProjectWorkspace(project.id);
  return {hook,states,notify:value=>onRealtime(value),gets:()=>gets};
}

test('accepted build intake refreshes the requirements stage when realtime is missed', async()=>{
  const f=fixture();
  await f.hook.send(request.content,'BUILD'); await settle();
  assert.equal(f.gets(),1);
  assert.equal(f.states[0].lifecycleStage,'REQUIREMENTS');
  assert.equal(f.states[1].length,1);
});

test('an ordinary agent response does not trigger another project fetch', async()=>{
  const f=fixture({created:[request,{id:'assistant',role:'ASSISTANT'}]});
  await f.hook.send(request.content,'BUILD'); await settle();
  assert.equal(f.gets(),0);
  assert.equal(f.states[0].lifecycleStage,'DESIGN');
});

test('failure to refresh accepted intake does not claim the message was rejected or restore its draft', async()=>{
  const f=fixture({stageError:'Temporary stage read failure'});
  await f.hook.send(request.content,'BUILD'); await settle();
  assert.equal(f.states[1].length,1);
  assert.equal(f.states.some(value=>value?.text===request.content),false);
  assert.equal(f.states.includes('Temporary stage read failure'),false);
});

test('a rejected message retains the send error and does not refresh requirements', async()=>{
  const f=fixture({postError:'Rejected synthetic request'});
  await f.hook.send(request.content,'BUILD'); await settle();
  assert.equal(f.gets(),0);
  assert.ok(f.states.includes('Rejected synthetic request'));
  assert.ok(f.states.some(value=>value?.text===request.content));
});

test('project lifecycle events update only the current project', ()=>{
  const f=fixture();
  f.notify({type:'PROJECT_UPDATED',payload:{...interview,id:'foreign-project'}});
  assert.equal(f.states[0].lifecycleStage,'DESIGN');
  f.notify({type:'PROJECT_UPDATED',payload:interview});
  assert.equal(f.states[0].lifecycleStage,'REQUIREMENTS');
});
