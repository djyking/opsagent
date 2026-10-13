import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { createRequire } from 'node:module';
const require = createRequire(new URL('../package.json', import.meta.url));
const ts = require('typescript'), vue = require('vue'), compiler = require('vue/compiler-sfc');
const { renderToString } = require('vue/server-renderer');
function evaluate(source, imports) {
  const code = ts.transpileModule(source, { compilerOptions:{ module:ts.ModuleKind.CommonJS, target:ts.ScriptTarget.ES2022 } }).outputText;
  const module = { exports:{} };
  new Function('require','module','exports',code)(id => imports[id] || require(id), module, module.exports);
  return module.exports;
}
const source = readFileSync(new URL('../src/components/configuration/ConfigurationValue.vue', import.meta.url), 'utf8');
const { descriptor } = compiler.parse(source);
const script = compiler.compileScript(descriptor, { id:'configuration-value-test' });
const template = compiler.compileTemplate({ source:descriptor.template.content, filename:'ConfigurationValue.vue', id:'configuration-value-test', compilerOptions:{bindingMetadata:script.bindings} });
assert.deepEqual(template.errors, []);
const imports = { vue, '@lucide/vue':{ Copy:{render:()=>vue.h('i')} } };
const component = evaluate(script.content, imports).default;
const render = evaluate(template.code, imports).render;
const clipboard=[];
const originalNavigator=Object.getOwnPropertyDescriptor(globalThis,'navigator');
Object.defineProperty(globalThis,'navigator',{configurable:true,value:{clipboard:{writeText:async text=>clipboard.push(text)}}});
const scopes=[];
function instance(props, slots={}) {
  const scope=vue.effectScope();scopes.push(scope);
  const input=vue.reactive(props),state=scope.run(()=>component.setup(input,{expose(){}}));
  return { input,state,html:async()=>{
    const context=vue.proxyRefs({...state,...input,$slots:slots});
    return renderToString(vue.createSSRApp({render:()=>render(context,[],input,vue.proxyRefs(state),{}, {})}));
  }};
}
try {
  const routes=[{id:'automation',predicates:['Path=/api/automation/**'],uri:'lb://ops-agent-service'}];
  const json=instance({value:routes,type:'json',label:'已登记服务路由'});
  const html=await json.html();
  assert(html.includes('数组 · 1 项'));
  assert(html.includes('<details') && !html.includes('<details open'), 'Complex values are initially collapsed');
  assert(html.includes('lb://ops-agent-service') && html.includes('Path=/api/automation/**'), 'Expanded content retains every field');
  await json.state.copy(); assert.equal(clipboard[0],JSON.stringify(routes,null,2));
  assert.equal(json.state.copyState.value,'已复制');
  const editor=instance({value:{enabled:true},type:'json',label:'可编辑配置'},{editor:()=>[vue.h('textarea',{'aria-label':'配置编辑'})]});
  const editorHtml=await editor.html();assert(editorHtml.includes('展开编辑') && editorHtml.includes('配置编辑'));
  const bool=instance({value:false,type:'boolean',label:'是否启用'});
  assert((await bool.html()).includes('关闭'));
  const missing=instance({value:null,label:'地址'});
  assert((await missing.html()).includes('未设置'));
  await missing.state.copy();assert.equal(clipboard.length,1,'Unset values do not copy an invented value');
  const secret=instance({value:'test-only-sensitive-sentinel',sensitive:true,hasValue:true,label:'密钥'});
  const secretHtml=await secret.html();assert(secretHtml.includes('已设置'));
  assert(!secretHtml.includes('test-only-sensitive-sentinel') && !secretHtml.includes('复制密钥'));
  await secret.state.copy();assert.equal(clipboard.length,1,'Secret values cannot reach the clipboard');
  const long=instance({value:'x'.repeat(240),type:'string',label:'长参数'});
  assert((await long.html()).includes('长文本 · 240 个字符'));
  globalThis.navigator.clipboard.writeText=async()=>{throw new Error('denied');};
  await long.state.copy();assert.equal(long.state.copyState.value,'复制失败，请展开后手动选择内容');
} finally {
  scopes.forEach(scope=>scope.stop());
  if(originalNavigator)Object.defineProperty(globalThis,'navigator',originalNavigator);else delete globalThis.navigator;
}
console.log('PASS ConfigurationValue: folded complex values, complete JSON copy, false/null rendering, sensitive masking and copy failure');
