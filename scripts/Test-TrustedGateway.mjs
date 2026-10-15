import http from 'node:http';import net from 'node:net';import {spawn}from'node:child_process';import{createHmac}from'node:crypto';import{fileURLToPath}from'node:url';import path from'node:path';import assert from'node:assert/strict';
const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'..');
const listen=server=>new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
const upstream=http.createServer((request,response)=>{assert.equal(request.headers['x-codepal-signature'],undefined);response.writeHead(200,{'content-type':'application/json'}).end('{"ok":true}')});await listen(upstream);
const reserve=net.createServer();await listen(reserve);const port=reserve.address().port;await new Promise(resolve=>reserve.close(resolve));
const secret='a'.repeat(64),target='127.0.0.1:'+upstream.address().port;
const child=spawn(process.execPath,[path.join(root,'src/main/resources/sandbox/gateway/gateway.mjs')],{windowsHide:true,stdio:['ignore','ignore','ignore'],env:{...process.env,GATEWAY_PORT:String(port),API_RELAY_KEY:secret,APPS_SUBNET:'',MONGO_TARGET:''}});
let passed=0;
try {
  for(let i=0;i<100;i++){try{if((await fetch('http://127.0.0.1:'+port+'/__gateway/health',{signal:AbortSignal.timeout(200)})).ok)break;}catch{}await new Promise(resolve=>setTimeout(resolve,50));}
  const headers=expiry=>({'x-codepal-upstream':target,'x-codepal-expires':String(expiry),'x-codepal-signature':createHmac('sha256',Buffer.from(secret,'hex')).update(target+'\n'+expiry).digest('hex')});
  const endpoint='http://127.0.0.1:'+port+'/api/check',now=Math.floor(Date.now()/1000);
  assert.equal((await fetch(endpoint,{headers:headers(now+60),signal:AbortSignal.timeout(2000)})).status,200);passed++;
  for(const value of [headers(now-1),headers(now+10000),{'x-codepal-upstream':target},{...headers(now+60),'x-codepal-upstream':'127.0.0.1:65530'}]){const response=await fetch(endpoint,{headers:value,signal:AbortSignal.timeout(2000)});assert.equal(response.status,403);assert.equal(response.headers.get('x-codepal-relay-error'),'1');passed++;}
}finally {child.kill();await new Promise(resolve=>{if(child.exitCode!==null || child.signalCode!==null)resolve();else child.once('exit',resolve)});await new Promise(resolve=>upstream.close(resolve));}
console.log(JSON.stringify({passed,scope:'actual trusted gateway with controlled local HTTP and owned child cleanup'}));
