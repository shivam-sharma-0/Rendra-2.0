// Runs only in the runner-owned private Docker network; generated code has no internet/host route.
const fs=require('node:fs'),assert=require('node:assert/strict'),{fork}=require('node:child_process'),{once}=require('node:events');
const base='/checks/server', namespace='00000000-0000-0000-0000-000000000071';
const owner=JSON.parse(fs.readFileSync('/inputs/owned.json','utf8'));
if(!/^codepal-codex-starter-it-[a-f0-9]{12}$/.test(owner.project))throw Error('Fixture identity missing');
const uri='mongodb://codex-db:27017/contract_fixture';
const mongoose=require(base+'/node_modules/mongoose');
async function owned(){await mongoose.connect(uri,{serverSelectionTimeoutMS:5000});const row=await mongoose.connection.db.collection('_codex_fixture').findOne({_id:'owner'});assert.equal(row?.project,owner.project);}
if(process.argv.includes('--server')){
  (async()=>{
    await owned();const express=require(base+'/node_modules/express');const app=express();app.use(express.json());
    const {registerContract}=require(base+'/dist/rendra/'+namespace+'/register.js');registerContract(app);
    const {authRouter,requireAccount}=require(base+'/dist/rendra/'+namespace+'/auth.js');
    app.use('/api/auth',authRouter);app.get('/api/protected',requireAccount,(req,res)=>res.json({userId:req.accountId}));
    const {ownedSchema,ownedModel,bindRecordOwner}=require(base+'/dist/rendra/'+namespace+'/ownership.js');
    const Owned=ownedModel(mongoose.model('OwnedFixtureRecord',ownedSchema({value:{type:String,required:true}})));
    const records=express.Router();
    records.post('/',async(req,res)=>res.status(201).json(await Owned.create(req.body)));
    records.get('/',async(_req,res)=>res.json(await Owned.find().sort('value')));
    records.get('/:id',async(req,res)=>{const row=await Owned.findById(req.params.id);if(!row){res.status(404).json({error:'Missing'});return}res.json(row)});
    records.put('/:id',async(req,res)=>{const row=await Owned.findByIdAndUpdate(req.params.id,req.body,{returnDocument:'after'});if(!row){res.status(404).json({error:'Missing'});return}res.json(row)});
    records.delete('/:id',async(req,res)=>{const row=await Owned.findByIdAndDelete(req.params.id);if(!row){res.status(404).json({error:'Missing'});return}res.json(row)});
    records.post('/blocked/middleware',async(_req,res)=>{try{await Owned.find().setOptions({middleware:false});res.status(500).json({error:'Unsafe'});}catch{res.status(400).json({error:'Unsupported operation'})}});
    records.post('/blocked/lookup',async(_req,res)=>{try{await Owned.aggregate([{$lookup:{from:'_rendra_users',as:'other',pipeline:[]}}]);res.status(500).json({error:'Unsafe'});}catch{res.status(400).json({error:'Unsupported operation'})}});
    app.use('/api/owned',requireAccount,bindRecordOwner,records);
    app.get('/api/health',(_req,res)=>res.json({ok:true}));
    const server=app.listen(0,'127.0.0.1',()=>process.send({port:server.address().port}));
    process.on('SIGTERM',()=>server.close(async()=>{await mongoose.disconnect();process.exit(0)}));
  })().catch(()=>process.exit(1));
}else{
  let child,origin;const checks=[];
  async function start(){child=fork(__filename,['--server'],{stdio:['ignore','ignore','ignore','ipc']});let timer;try{const message=await new Promise((resolve,reject)=>{child.once('message',resolve);child.once('exit',()=>reject(Error('Fixture child did not start')));timer=setTimeout(()=>{child.kill('SIGKILL');reject(Error('Fixture start timed out'))},10000)});origin='http://127.0.0.1:'+message.port;}finally{clearTimeout(timer)}}
  async function stop(){if(!child)return;if(child.exitCode!==null || child.signalCode!==null){child=null;return}const exit=once(child,'exit');child.kill('SIGTERM');const timer=setTimeout(()=>child.kill('SIGKILL'),5000);try{await exit;}finally{clearTimeout(timer);child=null;}}
  async function request(method,path,body,status,token){const response=await fetch(origin+path,{method,headers:{'Content-Type':'application/json',...(token?{Authorization:'Bearer '+token}:{})},...(body===undefined?{}:{body:JSON.stringify(body)}),signal:AbortSignal.timeout(5000)});assert.equal(response.status,status,method+' '+path);checks.push(method+' '+path+' '+status);return response.status===204?null:await response.json();}
  (async()=>{
    await mongoose.connect(uri,{serverSelectionTimeoutMS:5000});await mongoose.connection.db.collection('_codex_fixture').insertOne({_id:'owner',project:owner.project});await mongoose.disconnect();
    await start();await request('GET','/api/health',undefined,200);
    const created=await request('POST','/api/items',{value:'one'},201);assert.equal(created.value,'one');
    const item='/api/items/'+created.id;assert.equal((await request('GET',item,undefined,200)).value,'one');
    assert.equal((await request('PUT',item,{value:'two'},200)).value,'two');
    const credentials={email:'fixture-'+owner.project+'@example.invalid',password:'fixture-password-123'};
    await request('POST','/api/auth/signup',credentials,201);
    const login=await request('POST','/api/auth/login',credentials,200);assert.ok(login.token);
    await request('POST','/api/auth/login',{...credentials,password:'wrong-password'},401);
    await request('GET','/api/protected',undefined,401);await request('GET','/api/protected',undefined,401,'invalid.signature');
    assert.equal((await request('GET','/api/protected',undefined,200,login.token)).userId,login.userId);
    const other=await request('POST','/api/auth/signup',{email:'other-'+owner.project+'@example.invalid',password:'fixture-password-456'},201);
    const own=await request('POST','/api/owned',{value:'private-one',accountId:other.userId},201,login.token);assert.equal(own.accountId,login.userId,'create must force verified account');
    assert.equal((await request('GET','/api/owned',undefined,200,other.token)).length,0);
    await request('GET','/api/owned/'+own.id,undefined,404,other.token);
    await request('PUT','/api/owned/'+own.id,{value:'stolen',accountId:other.userId},404,other.token);
    await request('DELETE','/api/owned/'+own.id,undefined,404,other.token);
    const edited=await request('PUT','/api/owned/'+own.id,{value:'private-two',accountId:other.userId},200,login.token);assert.equal(edited.accountId,login.userId,'update cannot transfer ownership');
    await request('POST','/api/owned/blocked/middleware',{},400,login.token);await request('POST','/api/owned/blocked/lookup',{},400,login.token);
    await stop();await start();assert.equal((await request('GET',item,undefined,200)).value,'two');
    assert.equal((await request('GET','/api/protected',undefined,200,login.token)).userId,login.userId);
    assert.equal((await request('GET','/api/owned/'+own.id,undefined,200,login.token)).value,'private-two');await request('GET','/api/owned/'+own.id,undefined,404,other.token);
    await request('DELETE','/api/owned/'+own.id,undefined,200,login.token);
    await request('DELETE',item,undefined,200);await request('GET',item,undefined,404);
    await stop();fs.writeFileSync('/checks/contract-behavior-result.json',JSON.stringify({project:owner.project,checks,restartVerified:true,complete:true},null,2));
    console.log(JSON.stringify({project:owner.project,checks:checks.length,restartVerified:true,complete:true}));
  })().catch(async error=>{await stop();console.error(error.stack);process.exitCode=1;});
}
