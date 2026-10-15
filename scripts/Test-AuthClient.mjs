import fs from 'node:fs/promises';
import path from 'node:path';
import {fileURLToPath,pathToFileURL} from 'node:url';
import assert from 'node:assert/strict';
const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'..');
const {default:ts}=await import(pathToFileURL(path.join(root,'frontend/node_modules/typescript/lib/typescript.js')));
const source=(await fs.readFile(path.join(root,'frontend/src/auth/api.ts'),'utf8')).replace(/import\.meta\.env\.VITE_API_URL/g,'undefined');
const js=ts.transpileModule(source,{compilerOptions:{target:ts.ScriptTarget.ES2022,module:ts.ModuleKind.ES2022}}).outputText;
const storage=new Map();globalThis.sessionStorage={getItem:key=>storage.get(key)??null,setItem:(key,value)=>storage.set(key,value),removeItem:key=>storage.delete(key)};
const profile=id=>({id,email:id+'@example.invalid',name:null,role:'USER'});
const response=(status,value)=>new Response(JSON.stringify(value),{status,headers:{'Content-Type':'application/json'}});
let caseNumber=0;
const client=()=>import('data:text/javascript;base64,'+Buffer.from(js+'\n// case '+caseNumber++).toString('base64'));
let passed=0;
{
  const api=await client();api.session.set({accessToken:'old',user:profile('a')});let refreshes=0,requests=0;
  globalThis.fetch=async(url,options)=>{if(url.endsWith('/refresh')){refreshes++;await new Promise(resolve=>setTimeout(resolve,10));return response(200,{accessToken:'new',user:profile('a')});}
    requests++;return new Headers(options.headers).get('Authorization')==='Bearer new'?response(200,{ok:true}):response(401,{message:'Expired'});};
  assert.deepEqual(await Promise.all([api.apiGet('/api/one'),api.apiGet('/api/two')]),[{ok:true},{ok:true}]);assert.equal(refreshes,1);assert.equal(requests,4);passed++;
}
for(const action of ['logout','switch-user','login-again']) {
  storage.clear();const api=await client();api.session.set({accessToken:'old',user:profile('a')});let release,started;
  const pending=new Promise(resolve=>release=resolve),refreshStarted=new Promise(resolve=>started=resolve);let requests=0;
  globalThis.fetch=async(url)=>{if(url.endsWith('/refresh')){started();await pending;return response(200,{accessToken:'stale',user:profile('a')});}
    if(url.endsWith('/login'))return response(200,{accessToken:'login',user:profile('a')});requests++;return response(401,{message:'Expired'});};
  const call=api.apiGet('/api/private');await refreshStarted;
  if(action==='logout'){api.markLoggedOut();api.session.clear();}
  if(action==='switch-user')api.session.set({accessToken:'other',user:profile('b')});
  if(action==='login-again')api.session.set(await api.authApi.login('a@example.invalid','fixture'));
  release();await assert.rejects(call,error=>error.status===401);assert.equal(requests,1);
  assert.equal(api.session.access(),action==='logout'?null:action==='switch-user'?'other':'login');passed++;
}
{
  storage.clear();const api=await client();api.session.set({accessToken:'old',user:profile('a')});let writes=0;
  globalThis.fetch=async(url)=>{if(url.endsWith('/refresh'))return response(200,{accessToken:'other',user:profile('b')});writes++;return response(401,{message:'Expired'});};
  await assert.rejects(api.apiSend('POST','/api/private',{request:'fixture'}),error=>error.status===401);assert.equal(writes,1);assert.equal(api.session.access(),null);passed++;
}
{
  storage.clear();const api=await client();const token=expiry=>'e30.'+Buffer.from(JSON.stringify({exp:expiry})).toString('base64url')+'.fixture';let calls=0;
  api.session.set({accessToken:token(Math.floor(Date.now()/1000)+300),user:profile('a')});
  globalThis.fetch=async()=>{calls++;return response(200,{accessToken:'renewed',user:profile('a')});};
  assert.equal(await api.realtimeAccess(),api.session.access());assert.equal(calls,0);
  api.session.set({accessToken:token(1),user:profile('a')});assert.equal(await api.realtimeAccess(),'renewed');assert.equal(calls,1);passed++;
}
{
  storage.clear();const api=await client();api.session.set({accessToken:'expired',user:profile('a')});let release,started;
  const pending=new Promise(resolve=>release=resolve),began=new Promise(resolve=>started=resolve);
  globalThis.fetch=async()=>{started();await pending;return response(200,{accessToken:'stale',user:profile('a')});};
  const call=api.realtimeAccess();await began;api.markLoggedOut();api.session.clear();release();assert.equal(await call,null);assert.equal(api.session.access(),null);passed++;
}
console.log(JSON.stringify({passed,scope:'actual transpiled auth client with controlled HTTP responses; no real browser tab/DB'}));
