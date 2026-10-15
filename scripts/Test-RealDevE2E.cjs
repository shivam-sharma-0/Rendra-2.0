const fs=require('fs'),path=require('path'),crypto=require('crypto');
const [base, workspace, evidence, scenario = 'static', gate] = process.argv.slice(2);
if (!/^http:\/\/127\.0\.0\.1:\d+$/.test(base)
    || !['static', 'vite', 'cancel', 'reject', 'stop-retry'].includes(scenario)) {
 throw Error('Local test origin and known scenario required');
}
let token,refreshCookie;
async function api(route,method='GET',body){const r=await fetch(base+route,{method,headers:{'content-type':'application/json',...(token?{Authorization:'Bearer '+token}:{})},...(body?{body:JSON.stringify(body)}:{})});if(route==='/api/auth/signup')refreshCookie=r.headers.getSetCookie().map(value=>value.split(';')[0]).join('; ');const text=await r.text();const data=text?JSON.parse(text):{};if(!r.ok)throw Error('HTTP '+r.status+' at '+route+': '+(data.message||data.error||'request refused'));return data;}
(async()=>{
 const id = crypto.randomUUID().replace(/-/g, '');
 const credentials = { email: 'codex-e2e-' + id + '@example.invalid', password: crypto.randomBytes(16).toString('hex') };
 const auth = await api('/api/auth/signup', 'POST', { ...credentials, name: 'Disposable E2E' });
 token = auth.accessToken;
 const list=await api('/api/workspaces');const project=await api('/api/workspaces/'+list[0].id+'/projects','POST',{name:'E2E static application',type:'BUILD'});if(!/^[a-f0-9-]{36}$/.test(project.id))throw Error('Invalid owned project');
 fs.writeFileSync(path.join(evidence,'project.json'),JSON.stringify({id:project.id}));
 const phaseOne = gate === 'phase-one' ? await require('./Test-PhaseOneGate.cjs')(base, project.id, credentials) : null;
 try {
 const root=path.join(workspace,project.id);fs.mkdirSync(root,{recursive:true});fs.writeFileSync(path.join(root,'index.html'),'<!doctype html><html><head><title>Original</title></head><body><h1 id="heading">Original</h1><button id="inc">Increment</button><output id="result">0</output><script>document.querySelector("#inc").onclick=()=>document.querySelector("#result").textContent=Number(document.querySelector("#result").textContent)+1</script></body></html>');
 if(scenario==='vite')fs.cpSync(path.resolve(__dirname,'../target/codex-structured-app-fixtures/vite'),root,{recursive:true});
 const message=scenario==='vite'?'Edit the existing Vite React TypeScript app: change the title in index.html from App to E2E Verified and h1 in src/App.tsx from Items to E2E Verified. Preserve the existing app and dependencies. No backend, authentication or storage changes.':'Edit the existing index.html: change the page title and h1 text from Original to E2E Verified. Preserve the counter behavior. Keep this a static HTML app; no framework, backend, authentication or storage changes.';
 await api('/api/projects/'+project.id+'/messages','POST',{id:'umsg_'+id.slice(0,24),aiMessageId:'aimsg_'+id.slice(0,24),mode:'BUILD',message});
 let run,stopped=false;for(let until=Date.now()+360000;Date.now()<until;){const runs=await api('/api/projects/'+project.id+'/agent-runs');run=runs[0];if(!stopped&&run&&((scenario==='cancel'&&fs.readFileSync(path.join(evidence,'app.log'),'utf8').includes('model_called runId='+run.id))||(scenario==='stop-retry'&&(await api('/api/projects/'+project.id+'/agent-runs/'+run.id+'/staged-files')).length>0))){await new Promise(r=>setTimeout(r,200));await api('/api/projects/'+project.id+'/agent-runs/'+run.id+'/cancel','POST');stopped=true;}if(run&&['DONE','FAILED','CANCELLED','AWAITING_INPUT'].includes(run.status))break;await new Promise(r=>setTimeout(r,scenario==='cancel'?100:1000));}
 const content=fs.readFileSync(path.join(root,'index.html'),'utf8');const result={projectId:project.id,runId:run?.id,status:run?.status,error:run?.error,codeSavedAt:run?.codeSavedAt,previewStatus:run?.previewStatus,promptTokens:run?.promptTokens,completionTokens:run?.completionTokens,changed:content.includes('E2E Verified')&&(scenario!=='vite'||fs.readFileSync(path.join(root,'src/App.tsx'),'utf8').includes('E2E Verified')),provider:'configured FREE routes',scenario:'existing '+scenario+' application edit; actual REST/worker/model/sandbox/save/preview'};
 fs.writeFileSync(path.join(evidence,'build.json'),JSON.stringify(result,null,2));if(['cancel','reject','stop-retry'].includes(scenario)){if(run?.status!==(scenario==='reject'?'FAILED':'CANCELLED')||run?.codeSavedAt||result.changed)throw Error('Expected stopped/rejected run with accepted files preserved');}else if(run?.status!=='DONE'||!run?.codeSavedAt||!result.changed)throw Error('No verified committed build: '+run?.status+' '+(run?.error||''));if(scenario==='vite'){const security=await require('./Test-RealSecurity.cjs')(base,project.id,token,refreshCookie);fs.writeFileSync(path.join(evidence,'security.json'),JSON.stringify(security,null,2));}
 if(scenario==='stop-retry'){
  const prefix='/api/projects/'+project.id+'/agent-runs/'+run.id+'/failed-draft';let draft;
  for(const until=Date.now()+15000;Date.now()<until;){draft=await api(prefix);if(draft.available)break;await new Promise(resolve=>setTimeout(resolve,500));}
  if(!draft?.available)throw Error('Stopped draft was not retained after cleanup');
  const retryId=crypto.randomUUID().replaceAll('-','').slice(0,24),request={id:'umsg_'+retryId,aiMessageId:'aimsg_'+retryId};
  const accepted=await api(prefix+'/retry','POST',request),again=await api(prefix+'/retry','POST',request);if(accepted.id!==again.id)throw Error('Retry was not idempotent');
  let retried;for(const until=Date.now()+360000;Date.now()<until;){const all=await api('/api/projects/'+project.id+'/agent-runs');retried=all.find(value=>value.id===accepted.id);if(retried&&['DONE','FAILED','CANCELLED','AWAITING_INPUT'].includes(retried.status))break;await new Promise(resolve=>setTimeout(resolve,500));}
  const proof={sourceRun:run.id,retryRun:accepted.id,status:retried?.status,codeSavedAt:retried?.codeSavedAt,previewStatus:retried?.previewStatus,idempotent:true,changed:fs.readFileSync(path.join(root,'index.html'),'utf8').includes('E2E Verified')};fs.writeFileSync(path.join(evidence,'retry.json'),JSON.stringify(proof,null,2));
  if(proof.status!=='DONE'||!proof.codeSavedAt||!proof.changed||proof.previewStatus!=='READY')throw Error('Explicit stopped-draft retry did not pass and save');
 }
 if (phaseOne) {
  const proof = await phaseOne.finish(run.id);
  fs.writeFileSync(path.join(evidence, 'phase-one.json'), JSON.stringify(proof, null, 2));
 }
 console.log(JSON.stringify(result));
 } finally {
  phaseOne?.close();
 }
})().catch(e=>{console.error(e.message);process.exitCode=1});
