const fs=require('fs'),path=require('path'),crypto=require('crypto');
const [base,workspace,evidence,scenario='mern']=process.argv.slice(2);
if(!/^http:\/\/127\.0\.0\.1:\d+$/.test(base))throw Error('Local isolated origin required');
if(!['mern','personal-todo'].includes(scenario))throw Error('Unknown full-stack fixture profile');
let token,refreshCookie;
async function send(route,method,body){let response;for(let attempt=0;;attempt++){try{response=await fetch(base+route,{method,headers:{'content-type':'application/json',...(token?{Authorization:'Bearer '+token}:{})},...(body?{body:JSON.stringify(body)}:{})});break;}catch(error){if(method!=='GET'||attempt>=2)throw Error('Transport failure at '+route+' ('+(error.cause?.code||error.name)+')');await new Promise(resolve=>setTimeout(resolve,250));}}if(route==='/api/auth/signup')refreshCookie=response.headers.getSetCookie().map(value=>value.split(';')[0]).join('; ');return response;}
// A build plus a follow-up outlives one access token: renew it once with the rotating refresh cookie.
async function renew(){const response=await fetch(base+'/api/auth/refresh',{method:'POST',headers:{Cookie:refreshCookie,Origin:'http://localhost:5173'}});if(!response.ok)return false;token=(await response.json()).accessToken;refreshCookie=response.headers.getSetCookie().map(value=>value.split(';')[0]).join('; ')||refreshCookie;return true;}
async function api(route,method='GET',body){let response=await send(route,method,body);if(response.status===401&&refreshCookie&&!route.startsWith('/api/auth/')&&await renew())response=await send(route,method,body);const text=await response.text();const result=text?JSON.parse(text):{};if(!response.ok)throw Error('HTTP '+response.status+' at '+route+': '+(result.message||result.error||'refused'));return result;}
const fixture=require('./fixtures/planner-intake.json');
const personal=scenario==='personal-todo';
const custom=process.env.CODEPAL_E2E_PROMPT;
const prompt=custom||(personal?fixture.prompt:fixture.fullStackPrompt);
(async()=>{
    const id=crypto.randomUUID().replaceAll('-','');const auth=await api('/api/auth/signup','POST',{email:'codex-fresh-'+id+'@example.invalid',password:crypto.randomBytes(16).toString('hex'),name:'Fresh app E2E'});token=auth.accessToken;
    const workspaces=await api('/api/workspaces');const project=await api('/api/workspaces/'+workspaces[0].id+'/projects','POST',{name:'Fresh Todo E2E',type:'BUILD',pendingPrompt:prompt});
    fs.writeFileSync(path.join(evidence,'project.json'),JSON.stringify({id:project.id}));
    const prefix='/api/projects/'+project.id+'/requirements';let requirements=await api(prefix+'/start','POST');
    if(custom)for(const question of requirements.questions.filter(q=>q.required&&!q.selectedOption&&!q.otherText))
        requirements=await api(prefix+'/questions/'+question.id,'PUT',{selectedOption:question.recommendedOption||question.options?.[0]?.id,otherText:null,revision:requirements.revision});
    fs.writeFileSync(path.join(evidence,'requirements-intake.json'),JSON.stringify(requirements,null,2));
    fs.writeFileSync(path.join(evidence,'model-usage.json'),JSON.stringify(await api('/api/projects/'+project.id+'/model-usage'),null,2));
    if(!custom&&(!requirements.complete||requirements.questions.some(q=>!q.key.startsWith('platform_'))
            ||requirements.questions.some(q=>q.required&&!q.selectedOption&&!q.otherText)
            ||requirements.questions.find(q=>q.key==='platform_storage')?.selectedOption!=='MERN'
            ||!(personal?['NONE','ACCOUNTS']:['ACCOUNTS']).includes(requirements.questions.find(q=>q.key==='platform_auth')?.selectedOption)
            ||(!personal&&(requirements.review?.pages?.length||0)<3)))throw Error('Complete server-backed task scope was not inferred without redundant questions');
    const buildStarted=Date.now();
    requirements=await api(prefix+'/approve','POST',{revision:requirements.revision});
    fs.writeFileSync(path.join(evidence,'requirements.json'),JSON.stringify(requirements,null,2));
    // Wait out the run's own 15-minute contract limit (plus a minute) instead of reporting a live run as failed.
    let run;for(let until=Date.now()+(custom?1_900_000:960000);Date.now()<until;){const runs=await api('/api/projects/'+project.id+'/agent-runs');run=runs[0];if(run&&!fs.existsSync(path.join(evidence,'build.json')))fs.writeFileSync(path.join(evidence,'build.json'),JSON.stringify({projectId:project.id,runId:run.id,status:run.status,evidence:'initial run identity; final result pending'}));if(run&&['DONE','FAILED','CANCELLED','AWAITING_INPUT'].includes(run.status))break;await new Promise(resolve=>setTimeout(resolve,1000));}
    const root=path.join(workspace,project.id);const result={projectId:project.id,runId:run?.id,status:run?.status,error:run?.error,codeSavedAt:run?.codeSavedAt,previewStatus:run?.previewStatus,promptTokens:run?.promptTokens,completionTokens:run?.completionTokens,changed:fs.existsSync(path.join(root,'client/src/App.tsx'))&&fs.existsSync(path.join(root,'server/src/index.ts')),provider:'configured FREE routes',scenario:(personal?'personal Todo; ':'account-backed Todo; ')+'actual requirements approval/planner/unit generation/browser API/CRUD/restart/save'};
    result.benchmark=process.env.CODEPAL_E2E_NAME||null;result.seconds=Math.round((Date.now()-buildStarted)/1000);
    const shots=path.join(root,'.codepal/browser-evidence/current');
    if(fs.existsSync(shots))for(const name of fs.readdirSync(shots).filter(file=>file.endsWith('.png')))fs.copyFileSync(path.join(shots,name),path.join(evidence,name));
    fs.writeFileSync(path.join(evidence,'build.json'),JSON.stringify(result,null,2));
    fs.writeFileSync(path.join(evidence,'model-usage.json'),JSON.stringify(await api('/api/projects/'+project.id+'/model-usage'),null,2));
    if(run?.id)await require('./AgentRunEvidence.cjs').retainRunEvidence(api,project.id,run,evidence);
    if(run?.status!=='DONE'||!run?.codeSavedAt||!result.changed||run?.previewStatus!=='READY')throw Error('No verified fresh full-stack build: '+run?.status+' '+(run?.error||''));
    const followUp=process.env.CODEPAL_E2E_FOLLOW_UP;
    if(followUp){
        // A change to the saved app: it must patch the app's own files, not re-plan it or touch the platform scaffold.
        const started=Date.now();
        await api('/api/projects/'+project.id+'/messages','POST',{id:'umsg_'+crypto.randomUUID().replaceAll('-',''),message:followUp,mode:'BUILD',aiMessageId:'amsg_'+crypto.randomUUID().replaceAll('-','')});
        let edit;for(let until=Date.now()+960000;Date.now()<until;){const runs=await api('/api/projects/'+project.id+'/agent-runs');edit=runs.find(r=>r.id!==run.id);if(edit&&['DONE','FAILED','CANCELLED','AWAITING_INPUT'].includes(edit.status))break;await new Promise(resolve=>setTimeout(resolve,1000));}
        if(edit?.id)await require('./AgentRunEvidence.cjs').retainRunEvidence(api,project.id,edit,evidence);
        const changed=edit?.id?[...new Set((await api('/api/projects/'+project.id+'/agent-runs/'+edit.id+'/changes')).map(c=>c.path))]:[];
        const own=changed.filter(p=>p!=='RENDRA.md');
        const scaffolds=fs.existsSync(path.join(root,'server/src/rendra'))?fs.readdirSync(path.join(root,'server/src/rendra')).length:0;
        const followResult={request:followUp,runId:edit?.id,mode:edit?.mode,status:edit?.status,error:edit?.error,codeSavedAt:edit?.codeSavedAt,previewStatus:edit?.previewStatus,
            seconds:Math.round((Date.now()-started)/1000),promptTokens:edit?.promptTokens,completionTokens:edit?.completionTokens,changed,serverScaffolds:scaffolds};
        fs.writeFileSync(path.join(evidence,'follow-up.json'),JSON.stringify(followResult,null,2));
        const problems=[];
        if(edit?.mode!=='BUILD')problems.push('routed as '+edit?.mode+', not a build');
        if(edit?.status!=='DONE'||!edit?.codeSavedAt||edit?.previewStatus!=='READY')problems.push('not saved/READY: '+edit?.status+' '+(edit?.error||''));
        if(!own.length)problems.push('no app file changed');
        if(own.length>4)problems.push(own.length+' files changed for one targeted request');
        if(changed.some(p=>/^(client|server)\/src\/rendra\//.test(p)||p==='server/src/index.ts'))problems.push('platform scaffold or server entry changed');
        if(scaffolds!==1)problems.push(scaffolds+' server scaffolds after the edit');
        // Every quoted text in the request must be rendered by the app's own source, not only a page title.
        const source=[];(function walk(dir){for(const entry of fs.readdirSync(dir,{withFileTypes:true})){const full=path.join(dir,entry.name);if(entry.isDirectory()){if(entry.name!=='node_modules'&&entry.name!=='rendra')walk(full);}else if(/.[jt]sx?$/.test(entry.name))source.push(fs.readFileSync(full,'utf8'));}})(path.join(root,'client/src'));
        for(const quoted of [...followUp.matchAll(/'([^']{2,80})'/g)].map(match=>match[1]))if(!source.some(text=>text.includes(quoted)))problems.push('"'+quoted+'" is not rendered by client/src');
        if(problems.length)throw Error('Follow-up edit failed: '+problems.join('; '));
        result.followUp=followResult;
    }
    // Last: its logout checks revoke this session.
    const security=await require('./Test-RealSecurity.cjs')(base,project.id,token,refreshCookie);fs.writeFileSync(path.join(evidence,'security.json'),JSON.stringify(security,null,2));
    console.log(JSON.stringify(result));
})().catch(error=>{console.error(error.message);process.exitCode=1});
