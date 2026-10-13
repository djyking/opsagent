import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import vm from 'node:vm';

const require = createRequire(import.meta.url);
const { parse, compileScript, compileTemplate } = require('vue/compiler-sfc');
const ts = require('typescript');
const vue = require('vue');
const { descriptor } = parse(readFileSync(new URL('../src/views/LoginView.vue', import.meta.url), 'utf8'));
const script = compileScript(descriptor, { id: 'login-entry-test' });
const template = compileTemplate({ source: descriptor.template.content, filename: 'LoginView.vue', id: 'login-entry-test' });
assert.deepEqual(template.errors, []);
const javascript = ts.transpileModule(script.content, {
  compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 },
}).outputText;

function createPage(features) {
  const mounted = [], logins = [], routes = [];
  let challenges = 0;
  const auth = { login: async (...args) => { logins.push(args); }, fetchMe: async () => {} };
  const context = { exports: {}, setTimeout: () => 1, clearTimeout: () => {}, Error,
    require: name => {
      if (name === 'vue') return { ...vue, onMounted: fn => mounted.push(fn), onBeforeUnmount: () => {} };
      if (name === 'vue-router') return { isNavigationFailure: () => false, useRoute: () => ({ query: { redirect: '/operations' } }), useRouter: () => ({ push: async path => routes.push(path) }) };
      if (name === '@/api/modules') return { authApi: {
        features: async () => { if (features instanceof Error) throw features; return features; },
        captcha: async () => ({ captchaId: `challenge-${++challenges}`, imageDataUrl: 'data:image/png;base64,test', expiresInSeconds: 120 }),
      } };
      if (name === '@/stores/auth') return { useAuthStore: () => auth };
      if (name === '@/composables/useToast') return { useToast: () => ({ show() {} }) };
      if (name === '@/api/session') return { safeReturnPath: value => value, SessionError: class extends Error {} };
      return {};
    },
  };
  vm.runInNewContext(javascript, context);
  const page = context.exports.default.setup({}, { expose() {} });
  return { page, auth, logins, routes, mount: async () => Promise.all(mounted.map(fn => fn())) };
}

{
  const { page, mount, logins, routes } = createPage({ demoEnabled: true, registrationEnabled: false });
  await page.submit();
  assert.equal(logins.length, 0, 'wait for actual server capability before selecting an identity');
  await mount();
  assert.equal(page.loginMode.value, 'demo');
  assert.equal(page.username.value, '');
  assert.equal(page.password.value, '');
  page.captchaCode.value = '7Q2KM';
  await page.submit();
  assert.deepEqual(logins, [['user', 'user', 'challenge-1', '7Q2KM']]);
  assert.deepEqual(routes, ['/operations']);
  console.log('PASS default demonstration entry uses the ordinary captcha/login flow and safe return path');
}
{
  const { page, mount, logins } = createPage({ demoEnabled: true, registrationEnabled: false });
  await mount();
  await page.selectLoginMode('account');
  page.username.value = 'assigned-account';
  page.password.value = 'entered-password';
  page.captchaCode.value = 'ABCDE';
  await page.submit();
  assert.deepEqual(logins, [['assigned-account', 'entered-password', 'challenge-1', 'ABCDE']]);
  page.busy.value = true;
  await page.selectLoginMode('demo');
  assert.equal(page.loginMode.value, 'account', 'an in-flight login keeps its selected identity');
  console.log('PASS account entry uses only entered credentials and cannot switch during authentication');
}
for (const features of [{ demoEnabled: false, registrationEnabled: false }, new Error('features offline')]) {
  const { page, mount } = createPage(features);
  await mount();
  await page.selectLoginMode('demo');
  assert.equal(page.loginMode.value, 'account');
  assert.equal(page.demoEnabled.value, false);
  assert.equal(page.featuresLoading.value, false);
}
console.log('PASS disabled or unavailable demonstration capability leaves normal account login available');
{
  const { page, auth, mount, logins, routes } = createPage({ demoEnabled: true, registrationEnabled: false });
  await mount();
  page.captchaCode.value = 'ABCDE';
  let release;
  auth.fetchMe = () => new Promise((_, reject) => { release = reject; });
  const pending = page.submit();
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(page.busy.value, true);
  assert.equal(page.phase.value, 'loading');
  assert.match(page.loadingHint.value, /加载工作台/);
  await page.submit();
  assert.equal(logins.length, 1, 'repeated clicks cannot create an extra visitor');
  release(new Error('page resources offline'));
  await pending;
  assert.equal(page.phase.value, 'load-failed');
  assert.equal(page.succeeded.value, false);
  assert.equal(page.captchaId.value, 'challenge-1', 'page failure does not restart authentication');
  auth.fetchMe = async () => {};
  await page.submit();
  assert.equal(logins.length, 1, 'retry opens the workbench under the already authenticated identity');
  assert.deepEqual(routes, ['/operations']);
  console.log('PASS continuous loading and resource-error retry preserve authenticated visitor identity');
}
{
  const { page, mount, auth, logins } = createPage({ demoEnabled: true, registrationEnabled: false });
  await mount();
  page.captchaExpired.value = true;
  await page.submit();
  assert.equal(logins.length, 0);
  assert.equal(page.captchaId.value, 'challenge-2');
  auth.login = async () => { throw new Error('验证码错误'); };
  page.captchaCode.value = 'WRONG';
  await page.submit();
  assert.equal(page.captchaId.value, 'challenge-3');
  assert.equal(page.captchaCode.value, '');
  assert.equal(page.error.value, '验证码错误');
  assert.equal(page.loginMode.value, 'demo');
  assert.equal(page.succeeded.value, false);
  console.log('PASS expired and rejected captchas are replaced without bypassing authentication');
}
