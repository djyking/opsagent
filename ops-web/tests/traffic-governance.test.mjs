import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { createRequire } from 'node:module';
const require=createRequire(new URL('../package.json',import.meta.url));
const ts=require('typescript'),vue=require('vue'),compiler=require('vue/compiler-sfc');
const {renderToString}=require('vue/server-renderer');
function evaluate(source,imports={}) {const module={exports:{}};new Function('require','module','exports',ts.transpileModule(source,{compilerOptions:{module:ts.ModuleKind.CommonJS,target:ts.ScriptTarget.ES2022}}).outputText)(name=>imports[name]||require(name),module,module.exports);return module.exports;}
const format=evaluate(readFileSync(new URL('../src/utils/traffic-governance.ts',import.meta.url),'utf8'));
assert.equal(format.trafficNumber(null),'—');assert.equal(format.trafficNumber(NaN),'—');assert.equal(format.trafficNumber(-1),'—');
assert.equal(format.trafficNumber(0),'0');assert.equal(format.trafficNumber(0.002),'<0.01');assert.equal(format.trafficNumber(0.03,true),'<0.1');assert.equal(format.trafficNumber(351.26,true),'约 351.3');
const {descriptor}=compiler.parse(readFileSync(new URL('../src/views/observability/TrafficGovernanceOverview.vue',import.meta.url),'utf8'));
const script=compiler.compileScript(descriptor,{id:'traffic-overview-test'});
const template=compiler.compileTemplate({source:descriptor.template.content,filename:'TrafficGovernanceOverview.vue',id:'traffic-overview-test',compilerOptions:{bindingMetadata:script.bindings}});
assert.deepEqual(template.errors,[]);
const component=evaluate(script.content,{'@/utils/traffic-governance':format,vue}).default;
const render=evaluate(template.code,{vue}).render;
const overview={windowSeconds:300,source:'PROMETHEUS',refreshedAt:'2026-09-08T10:00:00Z',streams:[
 {id:'gateway',label:'平台 / API 网关',serviceId:'ops-gateway',status:'AVAILABLE',requestsPerSecond:1.17,requestCount:351.26,apiRequestsPerSecond:0,blockedCount:null,sampledAt:'2026-09-08T09:59:50Z',scope:'含健康检查；不是在线人数',message:'真实采样'},
 {id:'question',label:'AI 问答入口',serviceId:'ops-rag-service',status:'NO_SAMPLES',requestsPerSecond:null,requestCount:null,blockedCount:null,sampledAt:null,scope:'不是模型调用次数',message:'—不代表0'}]};
const input={overview},state=component.setup(input,{expose(){}}),context=vue.proxyRefs({...state,...input});
const html=await renderToString(vue.createSSRApp({render:()=>render(context,[],input,vue.proxyRefs(state),{},{})}));
assert.equal((html.match(/data-traffic-stream=/g)||[]).length,2);
assert(html.includes('1.17')&&html.includes('约 351.3'));
assert(html.includes('含健康 / 监控请求')&&html.includes('API 转发 0'));
assert(html.includes('暂无有效样本')&&html.includes('—不代表0'));
assert(html.includes('最近 5 分钟')&&html.includes('采样于'));
assert(html.includes('<details')&&!html.includes('<details open'),'Scope details start folded');
console.log('PASS traffic overview: independent HTTP/API/question values, real zero vs unknown, tiny nonzero rates, approximate count and folded scope');
