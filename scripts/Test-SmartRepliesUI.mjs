// Verification-only: actual reply components + workspace send hook, offline browser, stubbed APIs/realtime.
import { mkdir, writeFile } from 'node:fs/promises';
import { execFileSync } from 'node:child_process';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { randomUUID } from 'node:crypto';
import assert from 'node:assert/strict';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const frontend = path.join(root, 'frontend');
const owner = 'codepal-smart-replies-' + randomUUID();
const fixture = path.join(frontend, 'target', 'smart-replies', owner);
await mkdir(fixture, { recursive: true });
await writeFile(path.join(fixture, 'index.html'), '<html><body><div id="root"></div><script type="module" src="/main.tsx"></script></body></html>');
await writeFile(path.join(fixture, 'realtime.ts'), `
let receiver; export function useRealtime(options) { receiver = options.onMessage; return { status:'closed',send(){} }; }
export function emit(frame) { receiver?.(frame); }
`);
await writeFile(path.join(fixture, 'main.tsx'), `
import React,{useEffect,useState} from 'react'; import {createRoot} from 'react-dom/client';
import {QueryClient,QueryClientProvider} from '@tanstack/react-query';
import {useProjectWorkspace} from '../../../src/pages/project/useProjectWorkspace';
import {ChatMessage} from '../../../src/pages/project/chat/ChatMessage';
import {BuildSuggestion} from '../../../src/pages/project/chat/BuildSuggestion';
import {agentApi,messageApi,projectApi} from '../../../src/api/projects';
import {emit} from './realtime';
const now='2026-10-15T00:00:00Z';
const action={label:'Build this',mode:'BUILD',message:'add dark mode',attachments:[],proposalId:'offer'};
const offer={id:'offer',projectId:'fixture',role:'ASSISTANT',status:'COMPLETE',mode:'CHAT',content:'I can add dark mode.',
  replyToId:'user',seq:2,createdAt:now,updatedAt:now,feedback:null,suggestedAction:action};
projectApi.get=async()=>({id:'fixture',name:'Synthetic UI',type:'CHAT',lifecycleStage:'REVIEW'});
let currentOffer=offer;
let holdNextSnapshot=false,releaseOldSnapshot;
messageApi.list=async()=>{
  const page={content:[currentOffer],last:true,totalPages:1,number:0};
  if(holdNextSnapshot){
    holdNextSnapshot=false;
    await new Promise(resolve=>{releaseOldSnapshot=resolve;});
  }
  return page;
};
messageApi.post=async()=>{throw Error('Fixture stale proposal refused');};
agentApi.list=async()=>[]; agentApi.steps=async()=>[];
agentApi.plan=async()=>({id:'plan-contract',hash:'reviewed-hash',state:'APPROVED',storage:'NONE',auth:'NONE',units:[]});
const planRun={id:'plan-run',projectId:'fixture',messageId:'plan-reply',mode:'PLAN',status:'DONE',planAvailable:true,
  createdAt:now,updatedAt:now,codeSavedAt:null,conversational:false};
const planMessage={...offer,id:'plan-reply',mode:'PLAN',suggestedAction:{...action,proposalId:'plan-reply'}};
const callbacks={onOpenDetails(){},onOpenPreview(){},onOpenThinking(){},onFeedback(){},onEdit(){}};
function App(){
  const workspace=useProjectWorkspace('fixture'); const [sendResult,setSendResult]=useState('idle');
  const [planState,setPlanState]=useState('');
  useEffect(()=>{
    const update=()=>setPlanState(JSON.stringify({
      magicPhrase:[...document.querySelectorAll('[role=dialog] p')].some(el=>/build the approved plan/i.test(el.textContent||'')),
      buildActions:[...document.querySelectorAll('#plan-case button,[role=dialog] button')].filter(el=>el.textContent?.trim()==='Build this'&&!el.disabled).length
    }));
    const observer=new MutationObserver(update); observer.observe(document.body,{subtree:true,childList:true,characterData:true});
    update(); return ()=>observer.disconnect();
  },[]);
  return <main>
    <section id="send-case"><BuildSuggestion action={action} onBuild={async value=>{
      try {await workspace.send(value.message,value.mode,[],value.attachments,value.proposalId);setSendResult('fulfilled');}
      catch(error){setSendResult('rejected');throw error;}
    }}/><output id="send-result">{sendResult}</output>
      <output id="composer-state">{workspace.editRequest?.text ?? 'unchanged'}</output>
      <output id="global-send-error">{workspace.sendError ?? 'none'}</output>
    </section>
    <section id="stale-case">{workspace.messages.map(message=><ChatMessage key={message.id} message={message} steps={[]}
      {...callbacks} onBuild={async()=>{}}/>)}
      <button id="old-refresh" onClick={()=>{
        holdNextSnapshot=true;
        emit({type:'MESSAGE_UPSERT',payload:{...offer,suggestedAction:null}});
      }}>Delay old snapshot</button>
      <button id="supersede" onClick={()=>{
        currentOffer={...offer,suggestedAction:null};
        emit({type:'MESSAGE_UPSERT',payload:currentOffer});
        releaseOldSnapshot?.();
      }}>Supersede</button>
    </section>
    <section id="viewer-case"><ChatMessage message={{...offer,id:'viewer-offer',suggestedAction:null}} steps={[]} {...callbacks}/></section>
    <section id="plan-case"><ChatMessage message={planMessage} run={planRun} steps={[]} {...callbacks} onBuild={async()=>{}}/></section>
    <output id="plan-state">{planState}</output>
  </main>;
}
createRoot(document.getElementById('root')!).render(<QueryClientProvider client={new QueryClient()}><App/></QueryClientProvider>);
`);
const { build } = await import(pathToFileURL(path.join(frontend, 'node_modules/vite/dist/node/index.js')));
const { default: react } = await import(pathToFileURL(path.join(frontend, 'node_modules/@vitejs/plugin-react/dist/index.js')));
await build({ configFile: false, root: fixture, plugins: [react()], logLevel: 'warn',
  resolve: { alias: [{ find: /^.*\/realtime\/useRealtime$/, replacement: path.join(fixture, 'realtime.ts') }] },
  build: { outDir: path.join(fixture, 'site'), emptyOutDir: false } });
const recipes = [
  [
    { id: 'proposal-rejection-click', kind: 'CLICK', selector: '#send-case button' },
    { id: 'proposal-error-propagates', kind: 'ASSERT_TEXT', selector: '#send-result', value: 'rejected' },
    { id: 'proposal-error-visible', kind: 'ASSERT_TEXT', selector: '#send-case [role=alert]', value: 'Could not start the build' },
    { id: 'proposal-leaves-composer-alone', kind: 'ASSERT_TEXT', selector: '#composer-state', value: 'unchanged' },
    { id: 'proposal-has-no-second-error', kind: 'ASSERT_TEXT', selector: '#global-send-error', value: 'none' },
  ],
  [
    { id: 'initial-offer-visible', kind: 'ASSERT_COUNT', selector: '#stale-case .ws-flat-btn', value: '1' },
    { id: 'delay-old-offer-snapshot', kind: 'CLICK', selector: '#old-refresh' },
    { id: 'supersede-offer', kind: 'CLICK', selector: '#supersede' },
    { id: 'stale-offer-disappears', kind: 'ASSERT_COUNT', selector: '#stale-case .ws-flat-btn', value: '0' },
    { id: 'viewer-response-has-no-action', kind: 'ASSERT_COUNT', selector: '#viewer-case .ws-flat-btn', value: '0' },
  ],
  [
    { id: 'open-plan', kind: 'CLICK', selector: '#plan-case .msg-chip' },
    { id: 'approved-plan-loaded', kind: 'ASSERT_TEXT', selector: '[role=dialog]', value: 'Plan approved' },
    { id: 'no-magic-plan-phrase', kind: 'ASSERT_TEXT', selector: '#plan-state', value: '"magicPhrase":false' },
    { id: 'one-build-next-step', kind: 'ASSERT_TEXT', selector: '#plan-state', value: '"buildActions":1' },
  ],
];
const command = args => execFileSync('wsl.exe', ['-e', ...args], { encoding: 'utf8', timeout: 60000, maxBuffer: 1048576 }).trim();
let cleanupVerified = false;
const reports = [];
try {
  const site = command(['wslpath', '-u', path.join(fixture, 'site')]);
  const probe = command(['wslpath', '-u', path.join(root, 'src/main/resources/sandbox/browser/probe.mjs')]);
  const image = command(['docker', 'image', 'inspect', 'codepal-browser:efeb16a78e4c', '--format', '{{.Id}}']);
  assert.match(image, /^sha256:[a-f0-9]{64}$/);
  for (const [index, actions] of recipes.entries()) {
    const raw = command(['docker', 'run', '--rm', '--name', owner + '-' + index, '--label', 'com.codepal.smart-replies=' + owner,
      '--pull', 'never', '--network', 'none', '--user', '1000:1000', '--cpus', '1', '--memory', '512m', '--cap-drop', 'ALL',
      '--security-opt', 'no-new-privileges', '--mount', 'type=bind,source=' + site + ',target=/site,readonly',
      '--mount', 'type=bind,source=' + probe + ',target=/opt/codepal/probe.mjs,readonly', '--entrypoint', 'node', image,
      '/opt/codepal/probe.mjs', '/site', '200', Buffer.from(JSON.stringify({ schema: 1, actions })).toString('base64')]);
    reports.push(JSON.parse(raw.split('\n').filter(line => line.startsWith('{')).at(-1)));
  }
} finally {
  for (const container of command(['docker', 'ps', '-aq', '--filter', 'label=com.codepal.smart-replies=' + owner]).split('\n').filter(Boolean)) {
    const labels = JSON.parse(command(['docker', 'inspect', '--format', '{{json .Config.Labels}}', container]));
    assert.equal(labels['com.codepal.smart-replies'], owner);
    command(['docker', 'rm', '-f', container]);
  }
  cleanupVerified = command(['docker', 'ps', '-aq', '--filter', 'label=com.codepal.smart-replies=' + owner]) === '';
  await writeFile(path.join(fixture, 'result.json'), JSON.stringify({ owner, recipes, reports, cleanupVerified }, null, 2));
}
assert.equal(cleanupVerified, true);
for (const [index, report] of reports.entries()) {
  assert.equal(report.loaded, true, 'fixture must load');
  assert.equal(report.acceptance.length, recipes[index].length);
  assert.ok(report.acceptance.every(action => action.passed), JSON.stringify(report.acceptance));
}
console.log(JSON.stringify({ owner, checks: reports.flatMap(report => report.acceptance).length, cleanupVerified }));
