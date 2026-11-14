import { Router, type Request, type Response, type NextFunction } from 'express'
import mongoose from 'mongoose'
import { randomBytes, scrypt as scryptCallback, createHmac, timingSafeEqual } from 'node:crypto'
import { promisify } from 'node:util'

const scrypt = promisify(scryptCallback)
const User = mongoose.models.RendraUser ?? mongoose.model('RendraUser', new mongoose.Schema({
  email: {type: String, required: true, unique: true}, salt: {type: String, required: true}, passwordHash: {type: String, required: true},
  role: {type: String, enum: ['admin', 'member'], default: 'member'},
}, {collection: '_rendra_users'}))
const Setting = mongoose.models.RendraSetting ?? mongoose.model('RendraSetting', new mongoose.Schema({
  _id: String, value: {type: String, required: true},
}, {collection: '_rendra_settings'}))
const Key = mongoose.models.RendraSigningKey ?? mongoose.model('RendraSigningKey', new mongoose.Schema({
  _id: String, value: {type: String, required: true},
}, {collection: '_rendra_signing_keys'}))

async function signingKey(): Promise<string> {
  try {
    const row = await Key.findOneAndUpdate({_id:'session-v1'}, {$setOnInsert:{value:randomBytes(32).toString('hex')}}, {upsert:true, returnDocument:'after'}).lean()
    if (row && typeof row.value === 'string') return row.value
  } catch (error) {
    if ((error as {code?:number}).code !== 11000) throw error
  }
  const row = await Key.findById('session-v1').lean()
  if (!row || typeof row.value !== 'string') throw new Error('Session signing unavailable')
  return row.value
}
async function session(userId: string, role: 'admin' | 'member' = 'member') {
  const payload = Buffer.from(JSON.stringify({userId, expires:Date.now()+3600000})).toString('base64url')
  const signature = createHmac('sha256',await signingKey()).update(payload).digest('base64url')
  return {token:`${payload}.${signature}`, userId, role}
}
async function claimFirstAdmin(userId: string): Promise<boolean> {
  try {
    const row = await Setting.findOneAndUpdate({_id:'first-admin'}, {$setOnInsert:{value:userId}}, {upsert:true, returnDocument:'after'}).lean()
    return !!row && row.value === userId
  } catch (error) {
    if ((error as {code?:number}).code !== 11000) throw error
    return false
  }
}
export async function roleOf(accountId: string): Promise<'admin' | 'member'> {
  const user = await User.findById(accountId).select('role').lean()
  return user && user.role === 'admin' ? 'admin' : 'member'
}
function credentials(req: Request) {
  const email = typeof req.body?.email === 'string' ? req.body.email.trim().toLowerCase() : ''
  const password = typeof req.body?.password === 'string' ? req.body.password : ''
  if (!/^[^\s@]{1,120}@[^\s@]{1,120}\.[^\s@]{1,30}$/.test(email) || password.length<8 || password.length>1024) return null
  return {email,password}
}
export const authRouter = Router()
authRouter.post('/signup',async(req,res)=>{
  const value=credentials(req);if(!value){res.status(400).json({error:'Invalid credentials'});return}
  try {
    await User.init()
    const salt=randomBytes(16).toString('hex'), hash=await scrypt(value.password,salt,64) as Buffer
    const user=await User.create({email:value.email,salt,passwordHash:hash.toString('hex')})
    const admin=await claimFirstAdmin(String(user._id))
    if(admin)await User.updateOne({_id:user._id},{role:'admin'})
    res.status(201).json(await session(String(user._id),admin?'admin':'member'))
  } catch(error) {
    res.status((error as {code?:number}).code===11000?409:503).json({error:'Account unavailable'})
  }
})
authRouter.post('/login',async(req,res)=>{
  const value=credentials(req);if(!value){res.status(401).json({error:'Invalid credentials'});return}
  try {
    const user=await User.findOne({email:value.email}).lean()
    const salt=user && typeof user.salt==='string'?user.salt:'missing-account-salt'
    const actual=await scrypt(value.password,salt,64) as Buffer
    const expected=user && typeof user.passwordHash==='string'?Buffer.from(user.passwordHash,'hex'):Buffer.alloc(64)
    if(!user || expected.length!==actual.length || !timingSafeEqual(expected,actual)){res.status(401).json({error:'Invalid credentials'});return}
    res.json(await session(String(user._id),user.role==='admin'?'admin':'member'))
  } catch {res.status(503).json({error:'Account unavailable'})}
})
export type AccountRequest = Request & {accountId?:string}
export async function requireAccount(req: AccountRequest,res: Response,next: NextFunction) {
  try {
    const token=req.headers.authorization?.match(/^Bearer ([A-Za-z0-9_-]+\.[A-Za-z0-9_-]+)$/)?.[1]
    if(!token || token.length>2048){res.status(401).json({error:'Sign in required'});return}
    const [payload,signature]=token.split('.')
    const expected=createHmac('sha256',await signingKey()).update(payload!).digest()
    const actual=Buffer.from(signature!,'base64url')
    if(actual.length!==expected.length || !timingSafeEqual(actual,expected))throw new Error('Invalid session')
    const claims=JSON.parse(Buffer.from(payload!,'base64url').toString('utf8'))
    if(typeof claims.userId!=='string' || !/^[a-f0-9]{24}$/.test(claims.userId) || !Number.isFinite(claims.expires) || claims.expires<=Date.now())throw new Error('Invalid session')
    req.accountId=claims.userId;next()
  } catch {res.status(401).json({error:'Sign in required'})}
}
export async function requireAdmin(req: AccountRequest,res: Response,next: NextFunction) {
  if(!req.accountId){res.status(401).json({error:'Sign in required'});return}
  try {
    if(await roleOf(req.accountId)!=='admin'){res.status(403).json({error:'Admins only'});return}
    next()
  } catch {res.status(503).json({error:'Account unavailable'})}
}
authRouter.get('/me',requireAccount,async(req: AccountRequest,res)=>{
  try {
    const user=await User.findById(req.accountId).select('email role').lean()
    if(!user){res.status(401).json({error:'Sign in required'});return}
    res.json({userId:req.accountId,email:user.email,role:user.role==='admin'?'admin':'member'})
  } catch {res.status(503).json({error:'Account unavailable'})}
})
authRouter.post('/admins',requireAccount,requireAdmin,async(req: AccountRequest,res)=>{
  const email=typeof req.body?.email==='string'?req.body.email.trim().toLowerCase():''
  if(!/^[^\s@]{1,120}@[^\s@]{1,120}\.[^\s@]{1,30}$/.test(email)){res.status(400).json({error:'Invalid email'});return}
  try {
    const updated=await User.findOneAndUpdate({email},{role:'admin'},{returnDocument:'after'}).select('_id').lean()
    if(!updated){res.status(404).json({error:'Account not found'});return}
    res.json({email,role:'admin'})
  } catch {res.status(503).json({error:'Account unavailable'})}
})
