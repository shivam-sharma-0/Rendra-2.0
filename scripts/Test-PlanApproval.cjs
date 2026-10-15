// Callback/state verification; no browser, backend or provider is contacted.
const fs=require('node:fs'), path=require('node:path'), vm=require('node:vm'), assert=require('node:assert/strict');
const root=path.resolve(__dirname,'..'), ts=require(path.join(root,'frontend/node_modules/typescript'));
const compiled=ts.transpileModule(fs.readFileSync(path.join(root,'frontend/src/pages/project/chat/PlanApproval.tsx'),'utf8'),
  {compilerOptions:{module:ts.ModuleKind.CommonJS,jsx:ts.JsxEmit.ReactJSX,target:ts.ScriptTarget.ES2022}}).outputText;
const reviewed={id:'immutable-id',hash:'reviewed-hash',state:'DRAFT',storage:'BROWSER',auth:'NONE',units:[]};
const run={id:'run-a',projectId:'project-a'};
const settle=()=>new Promise(resolve=>setImmediate(resolve));
function harness(state,api){
  let cursor=0;const effects=[],module={exports:{}};
  const element=(type,props)=>({type,props});
  const context={module,exports:module.exports,AbortController,require(id){
    if(id==='react')return {useState(initial){const i=cursor++;if(!(i in state))state[i]=initial;return [state[i],v=>state[i]=v]},useEffect(fn){effects.push(fn)}};
    if(id==='react/jsx-runtime')return {jsx:element,jsxs:element};
    if(id.endsWith('/projects'))return {agentApi:api};
    if(id.endsWith('/neu'))return {NeuAlert:'NeuAlert',NeuButton:'NeuButton',NeuDialog:'NeuDialog'};
    throw Error(id);
  }};
  vm.runInNewContext(compiled,context);
  return {tree:module.exports.PlanApproval({run}),effects};
}
function all(v,out=[]){if(Array.isArray(v))v.forEach(x=>all(x,out));else if(v&&typeof v==='object'){if(v.type)out.push(v);Object.values(v.props||{}).forEach(x=>all(x,out))}return out}
const approve=h=>all(h.tree).find(n=>n.type==='NeuButton'&&n.props.variant==='primary');
(async()=>{
  let calls=[],state=[false,null,false,null];
  const api={plan:async()=>{calls.push('read');return reviewed},approvePlan:async(p,r,body)=>{calls.push([p,r,body.id,body.hash]);return {...reviewed,state:'APPROVED'}}};
  let h=harness(state,api);h.effects.forEach(fn=>fn());await settle();assert.equal(calls.length,0,'closed review must not fetch or approve');
  all(h.tree).find(n=>n.type==='button').props.onClick();h=harness(state,api);h.effects.forEach(fn=>fn());await settle();
  assert.equal(calls.length,1);assert.equal(calls[0],'read','opening only reads');
  h=harness(state,api);approve(h).props.onClick();await settle();assert.deepEqual(calls[1],['project-a','run-a','immutable-id','reviewed-hash']);assert.equal(state[1].state,'APPROVED');
  for(const status of ['APPROVED','INVALIDATED']){h=harness([true,{...reviewed,state:status},false,null],api);assert.equal(approve(h).props.disabled,true);approve(h).props.onClick();await settle()}
  assert.equal(calls.length,2,'stale/already-approved plans cannot submit');
  h=harness([true,reviewed,true,null],api);assert.equal(approve(h).props.disabled,true);
  state=[true,reviewed,false,null];h=harness(state,{...api,approvePlan:async()=>{throw Error('stale')}});approve(h).props.onClick();await settle();assert.equal(state[1].state,'DRAFT');assert.ok(state[3].includes('could not be saved'));
  state=[true,null,false,null];h=harness(state,api);const cleanup=h.effects[0]();cleanup();await settle();assert.equal(state[1],null,'closed/aborted read must not install its response');
  process.stdout.write('7 plan review/approval state checks passed (no browser or network).\n');
})().catch(e=>{process.stderr.write(e.stack+'\n');process.exitCode=1});
