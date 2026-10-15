// Deterministic React projection checks; no browser, server or external requests.
const fs=require('node:fs'),path=require('node:path'),vm=require('node:vm'),assert=require('node:assert/strict');
const root=path.resolve(__dirname,'..'),ts=require(path.join(root,'frontend/node_modules/typescript'));
function load(relative,dependencies){
  const source=fs.readFileSync(path.join(root,relative),'utf8');
  const code=ts.transpileModule(source,{compilerOptions:{module:ts.ModuleKind.CommonJS,jsx:ts.JsxEmit.ReactJSX,target:ts.ScriptTarget.ES2022}}).outputText;
  const module={exports:{}};vm.runInNewContext(code,{module,exports:module.exports,require:id=>{
    if(id==='react/jsx-runtime')return {jsx:(type,props)=>({type,props}),jsxs:(type,props)=>({type,props})};
    if(Object.hasOwn(dependencies,id))return dependencies[id];throw Error('Unexpected import '+id);
  },Intl,Date,Set});return module.exports;
}
function nodes(tree){if(!tree || typeof tree!=='object')return [];return [tree,...[].concat(tree.props?.children??[]).flatMap(nodes)];}
function text(tree){if(Array.isArray(tree))return tree.map(text).join(' ');if(tree==null || typeof tree==='boolean')return '';if(typeof tree!=='object')return String(tree);return text(tree.props?.children);}
function meter(data,isError=false){const component=load('frontend/src/rendra/shell/UsageMeter.tsx',{
  'react-router-dom':{Link:'Link'},'../../api/queries':{useDailyUsage:()=>({data,isError})},
  '../../components/icons':{AppIcon:'Icon'},'../../lib/cx':{cx:(...v)=>v.filter(Boolean).join(' ')}
}).default;return component({onNavigate(){}});}
let checks=0;function check(fn){fn();checks++;}
check(()=>{const tree=meter({tokensUsed:300,quota:1000,resetsAt:'2026-10-13T00:00:00Z',capacityUsed:500,unknownTokens:200,reservedTokens:200});assert.equal(nodes(tree).find(n=>n.props?.role==='progressbar').props['aria-valuenow'],50);assert.match(text(tree),/held for unreported usage/);assert.match(text(tree),/reserved for active calls/);});
check(()=>{const tree=meter({tokensUsed:300,quota:1000,resetsAt:'2026-10-13T00:00:00Z'});assert.equal(nodes(tree).find(n=>n.props?.role==='progressbar').props['aria-valuenow'],30);assert.doesNotMatch(text(tree),/held for/);});
check(()=>{const tree=meter({tokensUsed:300,quota:0,resetsAt:'2026-10-13T00:00:00Z',capacityUsed:500});assert.equal(nodes(tree).find(n=>n.props?.role==='progressbar').props['aria-valuenow'],undefined);assert.match(text(tree),/no daily limit/);});
check(()=>assert.match(text(meter(undefined,true)),/unavailable/));
const activity=load('frontend/src/pages/project/activity.ts',{});
check(()=>{const result=activity.timelineEntries([{seq:1,type:'TOOL_RESULT',content:'patchFile',activity:{kind:'STATUS',title:'Change attempt failed',paths:['a.ts'],ok:false}},{seq:2,type:'TOOL_RESULT',content:'patchFile',activity:{kind:'EDIT',title:'Edited',paths:['a.ts'],ok:true}}],'');assert.equal(result.length,2);assert.equal(result[0].activity.ok,false);});
check(()=>assert.equal(activity.timelineEntries([{seq:1,type:'STATUS',content:'Queued'}],'')[0].type,'legacy'));
console.log(`${checks} usage/timeline projection checks passed; component projections, not browser/E2E.`);
