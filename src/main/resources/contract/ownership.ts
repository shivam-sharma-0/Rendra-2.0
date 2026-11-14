import { AsyncLocalStorage } from 'node:async_hooks'
import mongoose from 'mongoose'
import type { Response, NextFunction } from 'express'
import { roleOf, type AccountRequest } from './auth.js'

const owners = new AsyncLocalStorage<{id: string; admin: boolean}>()
function owner() { const value=owners.getStore(); if(!value) throw new Error('Verified account context is required'); return value.id }
function isAdmin() { return owners.getStore()?.admin === true }
export async function bindRecordOwner(req: AccountRequest,res: Response,next: NextFunction) {
  if(!req.accountId){res.status(401).json({error:'Sign in required'});return}
  let admin=false
  try {admin=(await roleOf(req.accountId))==='admin'} catch {res.status(503).json({error:'Account unavailable'});return}
  owners.run({id:req.accountId,admin},next)
}
export class ForbiddenError extends Error {
  readonly status = 403
}
function safeOptions(value: any) {
  if(value && typeof value==='object' && Object.prototype.hasOwnProperty.call(value,'middleware')) throw new Error('Ownership middleware cannot be disabled')
}
function ownedUpdate(value: any,accountId: string) {
  if(Array.isArray(value)) throw new Error('Unreviewed update pipelines are unavailable')
  const update={...(value??{})}
  if(!Object.keys(update).some(key=>key.startsWith('$')))return {...update,accountId}
  // Timestamps can add operators to an otherwise plain update. Normalize ordinary fields
  // before Mongoose casts them into $set, so they cannot overwrite the enforced owner later.
  const plain:Record<string,any>={}
  for(const key of Object.keys(update))if(!key.startsWith('$')){plain[key]=update[key];delete update[key]}
  update.$set={...plain,...(update.$set??{})}
  for(const key of Object.keys(update)) if(key.startsWith('$') && update[key] && typeof update[key]==='object') {
    update[key]={...update[key]}
    for(const field of Object.keys(update[key])) if(field==='accountId' || field.startsWith('accountId.') || (key==='$rename' && (update[key][field]==='accountId' || String(update[key][field]).startsWith('accountId.'))))delete update[key][field]
  }
  update.$set={...(update.$set??{}),accountId};return update
}
export function ownedSchema(fields: Record<string,any>) {
  const schema=new mongoose.Schema({...fields,accountId:{type:String,required:true,index:true}}, {timestamps:true,toJSON:{virtuals:true},toObject:{virtuals:true}})
  Object.defineProperty(schema,'__rendra_owned',{value:true})
  schema.pre('validate',function(){this.set('accountId',owner())})
  schema.pre('save',function(){const accountId=owner();this.set('accountId',accountId);if(!this.isNew)this.$where={...(this.$where??{}),accountId}})
  schema.pre(['find','findOne','countDocuments','updateOne','updateMany','findOneAndUpdate','findOneAndDelete','findOneAndReplace','replaceOne','deleteOne','deleteMany'],function(this:any){
    const accountId=owner();safeOptions(this.getOptions());this.setQuery({$and:[this.getFilter(),{accountId}]});this.maxTimeMS(5000)
    if(this.getUpdate())this.setUpdate(ownedUpdate(this.getUpdate(),accountId))
  })
  schema.pre('insertMany',function(docs:any[]){const accountId=owner();for(const document of docs)document.accountId=accountId})
  schema.pre('bulkWrite',function(){throw new Error('Use owner-bound individual CRUD operations')})
  schema.pre('deleteOne',{document:true,query:false},function(){throw new Error('Use Model.deleteOne for owner-bound deletion')})
  schema.pre('aggregate',function(){
    safeOptions(this.options);const pipeline=this.pipeline()
    for(const stage of pipeline)if(Object.keys(stage).some(key=>!['$match','$sort','$limit','$skip','$project','$group','$count','$unwind','$addFields','$set','$unset'].includes(key)))throw new Error('Unreviewed cross-collection aggregation is unavailable')
    pipeline.unshift({$match:{accountId:owner()}});this.option({maxTimeMS:5000})
  })
  return schema
}

// Guard ordinary generated query/document use, including attempts to opt out of Mongoose hooks.
// Application authors still control their backend; raw database code needs separate review.
function result(value:any):any {
  if(Array.isArray(value))return value.map(result)
  if(!(value instanceof mongoose.Document))return value
  return new Proxy(value,{get(target:any,key,receiver){
    if(['collection','db','$model','constructor','populate','deleteOne'].includes(String(key)))throw new Error('Use reviewed owner-bound model operations')
    const member=Reflect.get(target,key,target)
    if(typeof member!=='function')return member
    if(key==='save')return(...args:any[])=>{args.forEach(safeOptions);const accountId=owner();target.set('accountId',accountId);if(!target.isNew)target.$where={...(target.$where??{}),accountId};return member.apply(target,args).then(result)}
    return(...args:any[])=>{args.forEach(safeOptions);return query(member.apply(target,args))}
  }})
}
function query(value:any, wrap:(value:any)=>any = result):any {
  if(!(value instanceof mongoose.Query) && !(value instanceof mongoose.Aggregate))return value
  let proxy:any
  const execute=()=>{safeOptions((value as any).getOptions?.()??(value as any).options);return value.exec().then(wrap)}
  proxy=new Proxy(value,{get(target,key){
    if(['model','collection','mongooseCollection','constructor','populate','cursor'].includes(String(key)))throw new Error('Unreviewed model access is unavailable')
    if(key==='exec')return execute
    if(key==='then')return(resolve:any,reject:any)=>execute().then(resolve,reject)
    if(key==='catch')return(reject:any)=>execute().catch(reject)
    if(key==='finally')return(done:any)=>execute().finally(done)
    const member=Reflect.get(target,key,target)
    if(typeof member!=='function')return member
    return(...args:any[])=>{args.forEach(safeOptions);const next=member.apply(target,args);return next===target?proxy:query(next, wrap)}
  }});return proxy
}
export function ownedModel(model:any):any {
  if(!model.schema.__rendra_owned)throw new Error('Existing model needs reviewed ownership migration')
  return new Proxy(model,{get(target,key){
    if(['db','collection','$__collection','base','schema','prototype','discriminators','estimatedDocumentCount','bulkWrite','watch','distinct'].includes(String(key)))throw new Error('Use reviewed owner-bound model operations')
    const member=Reflect.get(target,key,target)
    if(typeof member!=='function')return member
    return(...args:any[])=>{owner();args.forEach(safeOptions);const value=member.apply(target,args);return value instanceof Promise?value.then(result):query(value)}
  },construct(target,args){owner();return result(Reflect.construct(target,args))}})
}
function keepCreator(value: any) {
  if(Array.isArray(value)) throw new Error('Unreviewed update pipelines are unavailable')
  const update={...(value??{})}
  delete update.accountId
  for(const key of Object.keys(update)) if(key.startsWith('$') && update[key] && typeof update[key]==='object') {
    update[key]={...update[key]}
    for(const field of Object.keys(update[key])) if(field==='accountId' || field.startsWith('accountId.') || (key==='$rename' && (update[key][field]==='accountId' || String(update[key][field]).startsWith('accountId.'))))delete update[key][field]
  }
  return update
}
export function sharedSchema(fields: Record<string,any>, mode: 'shared' | 'admin' = 'shared') {
  const schema=new mongoose.Schema({...fields,accountId:{type:String,required:true,index:true}}, {timestamps:true,toJSON:{virtuals:true},toObject:{virtuals:true}})
  Object.defineProperty(schema,'__rendra_shared',{value:mode})
  const writer=()=>{const accountId=owner();if(mode==='admin' && !isAdmin())throw new ForbiddenError('Admins only');return accountId}
  schema.pre('validate',function(){const accountId=writer();if(this.isNew)this.set('accountId',accountId)})
  schema.pre('save',function(){const accountId=writer();if(this.isNew)this.set('accountId',accountId);else if(mode==='shared')this.$where={...(this.$where??{}),accountId}})
  schema.pre(['find','findOne','countDocuments'],function(this:any){owner();safeOptions(this.getOptions());this.maxTimeMS(5000)})
  schema.pre(['updateOne','updateMany','findOneAndUpdate','findOneAndDelete','findOneAndReplace','replaceOne','deleteOne','deleteMany'],function(this:any){
    const accountId=writer();safeOptions(this.getOptions());this.maxTimeMS(5000)
    if(mode==='shared')this.setQuery({$and:[this.getFilter(),{accountId}]})
    if(this.getUpdate())this.setUpdate(keepCreator(this.getUpdate()))
  })
  schema.pre('insertMany',function(docs:any[]){const accountId=writer();for(const document of docs)document.accountId=accountId})
  schema.pre('bulkWrite',function(){throw new Error('Use reviewed individual CRUD operations')})
  schema.pre('deleteOne',{document:true,query:false},function(){throw new Error('Use Model.deleteOne for reviewed deletion')})
  schema.pre('aggregate',function(){
    owner();safeOptions(this.options)
    for(const stage of this.pipeline())if(Object.keys(stage).some(key=>!['$match','$sort','$limit','$skip','$project','$group','$count','$unwind','$addFields','$set','$unset'].includes(key)))throw new Error('Unreviewed cross-collection aggregation is unavailable')
    this.option({maxTimeMS:5000})
  })
  return schema
}
function sharedResult(value:any):any {
  if(Array.isArray(value))return value.map(sharedResult)
  if(!(value instanceof mongoose.Document))return value
  return new Proxy(value,{get(target:any,key){
    if(['collection','db','$model','constructor','populate','deleteOne'].includes(String(key)))throw new Error('Use reviewed model operations')
    const member=Reflect.get(target,key,target)
    if(typeof member!=='function')return member
    return(...args:any[])=>{args.forEach(safeOptions);const next=member.apply(target,args);return next instanceof Promise?next.then(sharedResult):next}
  }})
}
export function sharedModel(model:any):any {
  if(!model.schema.__rendra_shared)throw new Error('Existing model needs a reviewed sharing migration')
  return new Proxy(model,{get(target,key){
    if(['db','collection','$__collection','base','schema','prototype','discriminators','estimatedDocumentCount','bulkWrite','watch','distinct'].includes(String(key)))throw new Error('Use reviewed model operations')
    const member=Reflect.get(target,key,target)
    if(typeof member!=='function')return member
    return(...args:any[])=>{owner();args.forEach(safeOptions);const value=member.apply(target,args);return value instanceof Promise?value.then(sharedResult):query(value, sharedResult)}
  },construct(target,args){owner();return sharedResult(Reflect.construct(target,args))}})
}
