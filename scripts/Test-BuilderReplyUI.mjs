import { mkdir, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { execFileSync } from 'node:child_process';
import { randomUUID } from 'node:crypto';
import assert from 'node:assert/strict';

// Compile actual chat components; exercise outcomes and actions in offline Chromium. No app DB/provider.
const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'..');
const frontend=path.join(root,'frontend');
const owner='codepal-codex-reply-ui-'+randomUUID();
const fixture=path.join(frontend,'target','builder-reply-ui',owner);
assert.ok(fixture.startsWith(frontend+path.sep));
await mkdir(fixture,{recursive:true});
await writeFile(path.join(fixture,'index.html'),'<html><head><title>Builder reply verification</title></head><body><div id="root"></div><script type="module" src="/main.tsx"></script></body></html>');
await writeFile(path.join(fixture,'main.tsx'),`
import React,{useState} from 'react';import{createRoot}from'react-dom/client';
import{ChatMessage}from'../../../src/pages/project/chat/ChatMessage';import'../../../src/pages/project/chat/chat.css';
import { BuildSuggestion } from '../../../src/pages/project/chat/BuildSuggestion';
import { Composer } from '../../../src/pages/project/chat/Composer';
import{FailedDraftPanel}from'../../../src/pages/project/panel/FailedDraftPanel';import{agentApi}from'../../../src/api/projects';
let snapshots=0;const retryIds=new Set();agentApi.failedDraft=async()=>({state:++snapshots<2?'UNAVAILABLE':'AVAILABLE',available:snapshots>=2,verified:false,files:[],retryRunId:null});
agentApi.retryFailedDraft=async(_project,run,body)=>{if(retryIds.has(body.id))throw Error('Retry identity was reused for another draft');retryIds.add(body.id);return {id:'retry-'+run} as any;};
const base={projectId:'fixture',mode:'BUILD',promptTokens:20,completionTokens:40,rollbackAvailable:false,conversational:false,createdAt:'2026-10-15T00:00:00Z',updatedAt:'2026-10-15T00:00:02Z',respondingAt:null};
const cases=[['ready',{status:'DONE',codeSavedAt:base.updatedAt,previewStatus:'READY'}],['failed',{status:'FAILED'}],['stopped',{status:'CANCELLED'}],['preview-failed',{status:'FAILED',codeSavedAt:base.updatedAt,previewStatus:'FAILED'}],['preparing',{status:'RUNNING',codeSavedAt:base.updatedAt,previewStatus:'BUILDING'}],['recovery',{status:'FAILED',codeSavedAt:base.updatedAt,previewStatus:'READY',saveRecoveryRequired:true}],['old',{status:'DONE',codeSavedAt:base.updatedAt,previewStatus:'READY'}],['building',{status:'RUNNING'}],['budget',{status:'FAILED',failureReason:'RUN_TOKEN_BUDGET_EXCEEDED'}],['daily',{status:'FAILED',failureReason:'DAILY_QUOTA_EXCEEDED'}]];
function App(){const[panel,setPanel]=useState('None');const[draftRun,setDraftRun]=useState('first');return <main><h1>Build replies</h1><output id="panel">{panel}</output>{cases.map(([id,state])=>{const run={...base,...state,id,messageId:id};const message={id,projectId:'fixture',role:'ASSISTANT',status:run.status==='RUNNING'?'PENDING':'COMPLETE',content:id==='ready'?'Added task editing and completion.\\n\\nChanged files:\\n- src/App.tsx':'PRIVATE_RAW_FAILURE src/App.tsx',mode:'BUILD',replyToId:null,feedback:null,seq:1,createdAt:base.createdAt,updatedAt:base.updatedAt};return <section id={id} key={id}><ChatMessage message={message} run={run} steps={[{seq:1,type:'TOOL_CALL',content:'createFile',data:'{"path":"PRIVATE_FILE.tsx"}',createdAt:base.createdAt}]} currentPreview={id!=='old'} onOpenDetails={()=>setPanel('Details: '+id)} onOpenPreview={()=>setPanel('Preview: '+id)} onOpenThinking={()=>{}} onFeedback={()=>{}} onEdit={()=>{}}/></section>})}<section id="source-recovery"><FailedDraftPanel projectId="fixture" runId={draftRun} onRetried={run=>setPanel("Retry: "+run.id)}/><button id="switch-draft" onClick={()=>setDraftRun("second")}>Another stopped draft</button></section></main>}
function OfferHarness() {
  const [sent, setSent] = useState('');
  const action = { label: 'Build this', mode: 'BUILD' as const, message: 'add dark mode', attachments: ['uploads/mockup.png'], proposalId: 'offer-1' };
  return <section id="build-offer">
    <output id="chosen-mode">PLAN</output>
    <BuildSuggestion action={action} onBuild={async value => { setSent(JSON.stringify(value)); }} />
    <output id="offer-sent">{sent}</output>
    <div id="offer-disabled"><BuildSuggestion action={action} /></div>
    <div id="offer-error"><BuildSuggestion action={action} onBuild={async () => { throw Error('Fixture refusal'); }} /></div>
  </section>;
}
function ComposerHarness() {
  const [project, setProject] = useState(null);
  const [sent, setSent] = useState('');
  const load = (id, type) => setProject({ id, type });
  return <section id="composer-check">
    <button id="load-a" onClick={() => load('composer-a', 'CHAT')}>Load A</button>
    <button id="load-b" onClick={() => load('composer-b', 'BUILD')}>Load B</button>
    <button id="unload-project" onClick={() => setProject(null)}>Unload</button>
    <button id="invalid-mode" onClick={() => { localStorage.setItem('rendra:project-mode:composer-invalid', 'INVALID'); load('composer-invalid', 'CHAT'); }}>Invalid storage</button>
    <button id="blocked-storage" onClick={() => {
      Object.defineProperty(Storage.prototype, 'getItem', { configurable: true, value() { throw Error('Storage blocked'); } });
      Object.defineProperty(Storage.prototype, 'setItem', { configurable: true, value() { throw Error('Storage blocked'); } });
      load('composer-blocked', 'PLAN');
    }}>Blocked storage</button>
    <Composer project={project} onSend={(prompt, mode) => setSent(JSON.stringify({prompt, mode}))}
      quote={null} onClearQuote={() => {}} editRequest={null} />
    <output id="composer-sent">{sent}</output>
  </section>;
}
createRoot(document.getElementById('root')!).render(<><App/><OfferHarness/><ComposerHarness/></>);
`);
const {build}=await import(pathToFileURL(path.join(frontend,'node_modules/vite/dist/node/index.js')));
const {default:react}=await import(pathToFileURL(path.join(frontend,'node_modules/@vitejs/plugin-react/dist/index.js')));
await build({configFile:false,root:fixture,plugins:[react()],logLevel:'warn',build:{outDir:path.join(fixture,'site'),emptyOutDir:false}});
const command=(args)=>execFileSync('wsl.exe',['-e',...args],{encoding:'utf8',timeout:60_000,maxBuffer:1_048_576}).trim();
const actions=[];const action=(kind,selector,value)=>actions.push({id:'check'+actions.length,kind,selector,value});
for(const [id,text] of [['ready','preview is ready'],['failed','No new changes were saved'],['stopped','Build stopped'],['preview-failed','preview could not be prepared'],['preparing','Preparing your preview'],['recovery','recovery verification'],['old','earlier saved build'],['building','Working on your request']])action('ASSERT_TEXT','#'+id,text);
for(const id of ['failed','stopped','preview-failed','preparing','recovery','old','building'])action('ASSERT_COUNT','#'+id+' .msg-build-card button:disabled','1');
action('ASSERT_COUNT','code','0');action('ASSERT_COUNT','.run-steps','0');
action('ASSERT_TEXT','#ready','Added task editing and completion.');
action('CLICK','#ready .msg-build-card button:first-child',null);action('ASSERT_TEXT','#panel','Details: ready');
action('CLICK','#ready .msg-build-card button:last-child',null);action('ASSERT_TEXT','#panel','Preview: ready');
action('CLICK','#failed .msg-build-card button:first-child',null);action('ASSERT_TEXT','#panel','Details: failed');
assert.equal(actions.length,24);
const reasonActions=[{id:'budget',kind:'ASSERT_TEXT',selector:'#budget .msg-build-card > b',value:'Build token budget reached'},
  {id:'daily',kind:'ASSERT_TEXT',selector:'#daily .msg-build-card > b',value:'Daily AI limit reached'},
  {id:'budget-disabled',kind:'ASSERT_COUNT',selector:'#budget .msg-build-card button:disabled',value:'1'},
  {id:'daily-disabled',kind:'ASSERT_COUNT',selector:'#daily .msg-build-card button:disabled',value:'1'}];
reasonActions.push({id:'snapshot-ready',kind:'ASSERT_TEXT',selector:'#source-recovery',value:'Download source'},
 {id:'retry-first',kind:'CLICK',selector:'#source-recovery .pv-failed-draft__actions button:last-child',value:null},
 {id:'retry-first-result',kind:'ASSERT_TEXT',selector:'#panel',value:'Retry: retry-first'},
 {id:'switch-source',kind:'CLICK',selector:'#switch-draft',value:null},
 {id:'retry-second',kind:'CLICK',selector:'#source-recovery .pv-failed-draft__actions button:last-child',value:null},
 {id:'retry-second-result',kind:'ASSERT_TEXT',selector:'#panel',value:'Retry: retry-second'});
let report,cleanupVerified=false;
const offerActions = [
  { id: 'offer-visible', kind: 'ASSERT_TEXT', selector: '#build-offer > div:first-of-type button', value: 'Build this' },
  { id: 'offer-click', kind: 'CLICK', selector: '#build-offer > div:first-of-type button', value: null },
  { id: 'offer-payload', kind: 'ASSERT_TEXT', selector: '#offer-sent', value: '"mode":"BUILD","message":"add dark mode","attachments":["uploads/mockup.png"]' },
  { id: 'mode-preserved', kind: 'ASSERT_TEXT', selector: '#chosen-mode', value: 'PLAN' },
  { id: 'disabled-without-handler', kind: 'ASSERT_COUNT', selector: '#offer-disabled button:disabled', value: '1' },
  { id: 'failed-offer-click', kind: 'CLICK', selector: '#offer-error button', value: null },
  { id: 'failed-offer-message', kind: 'ASSERT_TEXT', selector: '#offer-error [role=alert]', value: 'Could not start the build' },
];
const composerActions = [];
const composerAction = (kind, selector, value = null) => composerActions.push({id: 'composer-' + composerActions.length, kind, selector, value});
const selectedMode = mode => composerAction('ASSERT_COUNT', '#composer-check [role=radio][aria-label="' + mode + '"][aria-checked=true]', '1');
composerAction('ASSERT_COUNT', '#composer-check textarea', '0');
composerAction('CLICK', '#load-a');
selectedMode('Chat');
composerAction('CLICK', '#composer-check [role=radio][aria-label=Plan]');
composerAction('CLICK', '#load-b');
selectedMode('Build');
composerAction('CLICK', '#load-a');
selectedMode('Plan');
composerAction('CLICK', '#unload-project');
composerAction('ASSERT_COUNT', '#composer-check textarea', '0');
composerAction('CLICK', '#load-a');
selectedMode('Plan');
composerAction('FILL', '#composer-check textarea', 'Explain this project');
composerAction('CLICK', '#composer-check button[aria-label=Send]');
composerAction('ASSERT_TEXT', '#composer-sent', '"prompt":"Explain this project","mode":"PLAN"');
composerAction('CLICK', '#invalid-mode');
selectedMode('Chat');
composerAction('CLICK', '#blocked-storage');
selectedMode('Plan');
composerAction('CLICK', '#composer-check [role=radio][aria-label=Chat]');
selectedMode('Chat');
composerAction('FILL', '#composer-check textarea', 'Hi');
composerAction('CLICK', '#composer-check button[aria-label=Send]');
composerAction('ASSERT_TEXT', '#composer-sent', '"prompt":"Hi","mode":"CHAT"');
try {
  const site=command(['wslpath','-u',path.join(fixture,'site')]);
  const probe=command(['wslpath','-u',path.join(root,'src/main/resources/sandbox/browser/probe.mjs')]);
  const image=command(['docker','image','inspect','codepal-browser:efeb16a78e4c','--format','{{.Id}}']);assert.match(image,/^sha256:[a-f0-9]{64}$/);
  const reports=[];
  for(const [index,recipe] of [actions,reasonActions,offerActions,composerActions].entries()) {
    const output=command(['docker','run','--rm','--name',owner+'-'+index,'--label','com.codepal.codex.reply-ui='+owner,'--pull','never','--network','none','--user','1000:1000','--cpus','1','--memory','512m','--cap-drop','ALL','--security-opt','no-new-privileges','--mount','type=bind,source='+site+',target=/site,readonly','--mount','type=bind,source='+probe+',target=/opt/codepal/probe.mjs,readonly','--entrypoint','node',image,'/opt/codepal/probe.mjs','/site','200',Buffer.from(JSON.stringify({schema:1,actions:recipe})).toString('base64')]);
    const result=JSON.parse(output.split('\n').filter(line=>line.startsWith('{')).at(-1));
    assert.equal(result.loaded,true);assert.equal(result.acceptance.length,recipe.length);assert.ok(result.acceptance.every(item=>item.passed),JSON.stringify(result));reports.push(result);
  }
  report={loaded:true,acceptance:reports.flatMap(result=>result.acceptance),reports};
} finally {
  const remaining=command(['docker','ps','-aq','--filter','label=com.codepal.codex.reply-ui='+owner]);
  for(const id of remaining.split('\n').filter(Boolean)) {
    const labels=JSON.parse(command(['docker','inspect','--format','{{json .Config.Labels}}',id]));assert.equal(labels['com.codepal.codex.reply-ui'],owner);
    command(['docker','rm','-f',id]);
  }
  cleanupVerified=command(['docker','ps','-aq','--filter','label=com.codepal.codex.reply-ui='+owner])==='';
  await writeFile(path.join(fixture,'result.json'),JSON.stringify({owner,actions,report,cleanupVerified,completedAt:new Date().toISOString()},null,2));
  assert.equal(cleanupVerified,true);
}
console.log(JSON.stringify({owner,passed:report.acceptance.length,cleanupVerified,result:path.join(fixture,'result.json')}));
