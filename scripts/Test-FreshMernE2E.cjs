const fs=require('fs'),path=require('path'),crypto=require('crypto');
const [base,workspace,evidence]=process.argv.slice(2);
if(!/^http:\/\/127\.0\.0\.1:\d+$/.test(base))throw Error('Local isolated origin required');
let token,refreshCookie;
async function api(route,method='GET',body){let response;for(let attempt=0;;attempt++){try{response=await fetch(base+route,{method,headers:{'content-type':'application/json',...(token?{Authorization:'Bearer '+token}:{})},...(body?{body:JSON.stringify(body)}:{})});break;}catch(error){if(method!=='GET'||attempt>=2)throw Error('Transport failure at '+route+' ('+(error.cause?.code||error.name)+')');await new Promise(resolve=>setTimeout(resolve,250));}}if(route==='/api/auth/signup')refreshCookie=response.headers.getSetCookie().map(value=>value.split(';')[0]).join('; ');const text=await response.text();const result=text?JSON.parse(text):{};if(!response.ok)throw Error('HTTP '+response.status+' at '+route+': '+(result.message||result.error||'refused'));return result;}
const prompt='Build a simple end-to-end Todo application using React, Express and MongoDB. One page, no sign-in, no external services. Tasks have title and completed. Add, list, edit, complete and delete tasks. Save tasks in MongoDB and show the same data after a page refresh and server restart. Use at most two implementation units: backend router and frontend app; shared types/models are coordinator-owned. Keep styling simple. Provide accessible labelled controls and verify real UI interactions, CRUD and persistence, not just headings.';
(async()=>{
    const id=crypto.randomUUID().replaceAll('-','');const auth=await api('/api/auth/signup','POST',{email:'codex-fresh-'+id+'@example.invalid',password:crypto.randomBytes(16).toString('hex'),name:'Fresh app E2E'});token=auth.accessToken;
    const workspaces=await api('/api/workspaces');const project=await api('/api/workspaces/'+workspaces[0].id+'/projects','POST',{name:'Fresh Todo E2E',type:'BUILD',pendingPrompt:prompt});
    fs.writeFileSync(path.join(evidence,'project.json'),JSON.stringify({id:project.id}));
    const prefix='/api/projects/'+project.id+'/requirements';let requirements=await api(prefix+'/start','POST');
    for(let count=0;count<12;count++){
        const question=requirements.questions.find(q=>!q.selectedOption&&!q.otherText);
        if(!question)break;
        let answer;if(question.key==='platform_storage')answer={selectedOption:'MERN'};else if(question.key==='platform_auth')answer={selectedOption:'NONE'};else if(question.options.some(option=>option.id==='other'))answer={selectedOption:'other',otherText:prompt};else answer={selectedOption:question.recommendedOption||question.options[0].id};
        requirements=await api(prefix+'/questions/'+question.id,'PUT',{...answer,revision:requirements.revision});
    }
    if(!requirements.complete)throw Error('Requirements did not complete');
    requirements=await api(prefix+'/approve','POST',{revision:requirements.revision});
    fs.writeFileSync(path.join(evidence,'requirements.json'),JSON.stringify(requirements,null,2));
    let run;for(let until=Date.now()+600000;Date.now()<until;){const runs=await api('/api/projects/'+project.id+'/agent-runs');run=runs[0];if(run&&!fs.existsSync(path.join(evidence,'build.json')))fs.writeFileSync(path.join(evidence,'build.json'),JSON.stringify({projectId:project.id,runId:run.id,status:run.status,evidence:'initial run identity; final result pending'}));if(run&&['DONE','FAILED','CANCELLED','AWAITING_INPUT'].includes(run.status))break;await new Promise(resolve=>setTimeout(resolve,1000));}
    const root=path.join(workspace,project.id);const result={projectId:project.id,runId:run?.id,status:run?.status,error:run?.error,codeSavedAt:run?.codeSavedAt,previewStatus:run?.previewStatus,promptTokens:run?.promptTokens,completionTokens:run?.completionTokens,changed:fs.existsSync(path.join(root,'client/src/App.tsx'))&&fs.existsSync(path.join(root,'server/src/index.ts')),provider:'configured FREE routes',scenario:'fresh MERN app; actual requirements approval/planner/unit generation/browser API/CRUD/restart/save'};
    fs.writeFileSync(path.join(evidence,'build.json'),JSON.stringify(result,null,2));
    if(run?.status!=='DONE'||!run?.codeSavedAt||!result.changed||run?.previewStatus!=='READY')throw Error('No verified fresh full-stack build: '+run?.status+' '+(run?.error||''));
    const security=await require('./Test-RealSecurity.cjs')(base,project.id,token,refreshCookie);fs.writeFileSync(path.join(evidence,'security.json'),JSON.stringify(security,null,2));
    console.log(JSON.stringify(result));
})().catch(error=>{console.error(error.message);process.exitCode=1});
