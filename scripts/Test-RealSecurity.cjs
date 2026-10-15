// Full-Boot copied-database runner only. Never logs credentials.
const assert=require('node:assert/strict'),http=require('node:http'),crypto=require('node:crypto');
module.exports=async function verify(base,project,access,cookie){
  const checks=[];const headers=token=>({Authorization:'Bearer '+token});const origin='http://localhost:5173';
  async function request(path,method='GET',extra={},body){const response=await fetch(base+path,{method,headers:{'Content-Type':'application/json',...extra},...(body?{body:JSON.stringify(body)}:{}),signal:AbortSignal.timeout(10000)});const text=await response.text();return {status:response.status,body:text?JSON.parse(text):null,cookie:response.headers.getSetCookie().map(value=>value.split(';')[0]).join('; ')};}
  const prefix='/api/projects/'+project;
  const other=await request('/api/auth/signup','POST',{}, {email:'codex-other-'+crypto.randomUUID()+'@example.invalid',password:crypto.randomBytes(16).toString('hex'),name:'Boundary fixture'});assert.equal(other.status,201);
  assert.ok([403,404].includes((await request(prefix,'GET',headers(other.body.accessToken))).status));checks.push('current project permissions refuse second account');
  assert.equal((await request('/api/auth/refresh','POST',{Cookie:cookie,Origin:'https://foreign.example.invalid'})).status,403);checks.push('foreign cookie mutation origin refused');
  const renewed=await request('/api/auth/refresh','POST',{Cookie:cookie,Origin:origin});assert.equal(renewed.status,200);assert.notEqual(renewed.body.accessToken,access);assert.ok(renewed.cookie);access=renewed.body.accessToken;cookie=renewed.cookie;
  assert.equal((await request('/api/auth/me','GET',headers(access))).status,200);checks.push('real signup and refresh preserve usable session');
  for(const [mode,message] of [['CHAT','Say hello in one short sentence.'],['PLAN','Give a short improvement plan for this Todo app. Do not edit or build code.']]) {
    const id=crypto.randomUUID().replaceAll('-','').slice(0,24),assistant='aimsg_'+id;
    assert.equal((await request(prefix+'/messages','POST',headers(access),{id:'umsg_'+id,aiMessageId:assistant,mode,message})).status,201);
    let run;for(const until=Date.now()+120000;Date.now()<until;){const response=await request(prefix+'/agent-runs','GET',headers(access));assert.equal(response.status,200);run=response.body.find(value=>value.messageId===assistant);if(run && ['DONE','FAILED','CANCELLED','AWAITING_INPUT'].includes(run.status))break;await new Promise(resolve=>setTimeout(resolve,500));}
    assert.equal(run?.status,'DONE',mode+' completes');assert.equal(run.codeSavedAt,null);checks.push(mode+' responds without saving code');
  }
  const preview=await request(prefix+'/preview-token','GET',headers(access));assert.equal(preview.status,200);assert.ok(preview.body.expiresAt);const url=new URL(preview.body.url);
  const readPreview=()=>new Promise((resolve,reject)=>{const req=http.request(base,{path:url.pathname+url.search,headers:{Host:url.host},timeout:10000},res=>{res.resume();res.on('end',()=>resolve(res.statusCode))});req.on('error',reject);req.on('timeout',()=>req.destroy(Error('Preview timeout')));req.end();});
  assert.equal(await readPreview(),200);checks.push('private saved preview resolves valid session grant');
  assert.equal((await request(prefix+'/preview-token','DELETE',headers(access))).status,204);assert.equal(await readPreview(),404);checks.push('explicit grant revocation immediately refuses old link');
  const next=await request(prefix+'/preview-token','GET',headers(access));assert.equal(next.status,200);assert.notEqual(next.body.url,preview.body.url);
  assert.equal(typeof WebSocket,'function');const socket=new WebSocket(base.replace('http:','ws:')+'/realtime?token='+encodeURIComponent(access)+'&projectId='+project);
  try {
    await new Promise((resolve,reject)=>{const timer=setTimeout(()=>reject(Error('Socket did not open')),10000);socket.addEventListener('open',()=>{clearTimeout(timer);resolve()},{once:true});socket.addEventListener('error',()=>{clearTimeout(timer);reject(Error('Socket refused valid session'))},{once:true});});
    const closed=new Promise((resolve,reject)=>{const timer=setTimeout(()=>reject(Error('Revoked socket remained connected')),10000);socket.addEventListener('close',event=>{clearTimeout(timer);resolve(event.code)},{once:true});});
    assert.equal((await request('/api/auth/logout','POST',{Cookie:cookie,Origin:origin})).status,204);socket.send(JSON.stringify({type:'PING'}));assert.equal(await closed,1008);checks.push('logout revokes open realtime socket');
    assert.ok([401,403].includes((await request('/api/auth/me','GET',headers(access))).status));checks.push('logout revokes previously issued access token');
    const nextUrl=new URL(next.body.url);url.host=nextUrl.host;url.pathname=nextUrl.pathname;assert.equal(await readPreview(),404);checks.push('logout revokes outstanding private preview link');
    assert.equal((await request('/api/auth/refresh','POST',{Cookie:cookie,Origin:origin})).status,401);checks.push('logout family cannot refresh');
  } finally {socket.close();}
  return {checks,passed:checks.length,scope:'full Spring Boot HTTP and WebSocket using copied real dev database'};
};
