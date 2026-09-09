<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref } from "vue";
import { isNavigationFailure, useRoute, useRouter } from "vue-router";
import {
  ArrowRight,
  LockKeyhole,
  UserRound,
  Eye,
  EyeOff,
  RefreshCw,
  ShieldCheck,
  Sparkles,
} from "@lucide/vue";
import { authApi } from "@/api/modules";
import { useAuthStore } from "@/stores/auth";
import InlineError from "@/components/InlineError.vue";
import AuthMotionScene from "@/components/auth/AuthMotionScene.vue";
import ActionButton from "@/components/feedback/ActionButton.vue";
import { useToast } from "@/composables/useToast";
import { safeReturnPath, SessionError } from "@/api/session";

const auth = useAuthStore();
const router = useRouter();
const route = useRoute();
const username = ref("");
const password = ref("");
const demoEnabled = ref(false);
const featuresLoading = ref(true);
const loginMode = ref<"demo" | "account">("account");
const usernameInput = ref<HTMLInputElement>();
const captchaInput = ref<HTMLInputElement>();
const captchaId = ref("");
const captchaCode = ref("");
const captchaImage = ref("");
const captchaLoading = ref(false);
const captchaError = ref("");
const captchaExpired = ref(false);
let captchaVersion = 0;
let expiryTimer: ReturnType<typeof setTimeout> | undefined;
const showPassword = ref(false);
const error = ref("");
const busy = ref(false);
const succeeded = ref(false);
const phase = ref<'idle' | 'verifying' | 'loading' | 'load-failed'>('idle');
const loadingHint = ref('');
let loadingTimer: ReturnType<typeof setTimeout> | undefined;
const toast = useToast();
const formFocused = ref(false);
const registrationEnabled = ref(false);
async function selectLoginMode(mode: "demo" | "account") {
  if (busy.value || featuresLoading.value || (mode === "demo" && !demoEnabled.value)) return;
  if (phase.value === 'load-failed') {
    auth.logout(); phase.value = 'idle'; loadingHint.value = ''; succeeded.value = false;
    await refreshCaptcha();
  }
  loginMode.value = mode;
  error.value = "";
  showPassword.value = false;
  await nextTick();
  (mode === "demo" ? captchaInput.value : usernameInput.value)?.focus();
}
function onFormFocusOut(event: FocusEvent) {
  formFocused.value = (event.currentTarget as HTMLElement).contains(event.relatedTarget as Node | null);
}
async function refreshCaptcha() {
  const version = ++captchaVersion;
  clearTimeout(expiryTimer);
  captchaLoading.value = true;
  captchaError.value = "";
  captchaCode.value = "";
  captchaId.value = "";
  captchaImage.value = "";
  captchaExpired.value = false;
  try {
    const challenge = await authApi.captcha();
    if (version !== captchaVersion) return;
    captchaId.value = challenge.captchaId;
    captchaImage.value = challenge.imageDataUrl;
    expiryTimer = setTimeout(() => { captchaExpired.value = true; }, challenge.expiresInSeconds * 1000);
  } catch (cause) {
    if (version === captchaVersion) captchaError.value = cause instanceof Error ? cause.message : "验证码加载失败，请重试";
  } finally {
    if (version === captchaVersion) captchaLoading.value = false;
  }
}
async function submit() {
  if (phase.value === 'load-failed') { await enterWorkbench(); return; }
  if (busy.value || captchaLoading.value || featuresLoading.value) return;
  if (!captchaId.value || captchaExpired.value) {
    await refreshCaptcha();
    return;
  }
  error.value = "";
  busy.value = true;
  phase.value = 'verifying';
  loadingHint.value = '正在验证，请稍候…';
  try {
    const demo = loginMode.value === "demo" && demoEnabled.value;
    const result = await auth.login(demo ? "user" : username.value, demo ? "user" : password.value, captchaId.value, captchaCode.value);
    if (result?.visitorExperience === 'EXPIRED' || result?.visitorExperience === 'ENDED')
      toast.show('原体验已到期或结束，已创建新体验；旧记录未删除，但不归属新的身份。');
    await enterWorkbench();
  } catch (e) {
    error.value = e instanceof Error ? e.message : "登录失败";
    phase.value = 'idle';
    loadingHint.value = '';
    await refreshCaptcha();
  } finally {
    busy.value = false;
  }
}
async function enterWorkbench() {
  if (phase.value === 'loading') return;
  busy.value = true;
  phase.value = 'loading';
  error.value = '';
  loadingHint.value = '验证成功，正在加载工作台…';
  clearTimeout(loadingTimer);
  loadingTimer = setTimeout(() => { loadingHint.value = '登录已成功，工作台资源仍在加载，请稍候…'; }, 4000);
  try {
    await auth.fetchMe();
    const navigation = await router.push(safeReturnPath(route.query.redirect));
    if (isNavigationFailure(navigation)) throw new Error('页面跳转未完成，请重新打开工作台。');
    succeeded.value = true;
  } catch (cause) {
    if (cause instanceof SessionError && cause.expired) {
      phase.value = 'idle';
      error.value = cause.message;
      loadingHint.value = '';
      await refreshCaptcha();
      return;
    }
    phase.value = 'load-failed';
    error.value = `登录已成功，工作台加载失败。${cause instanceof Error ? cause.message : '请重试打开页面。'}`;
    loadingHint.value = '登录状态已保留，可重试打开工作台。';
  } finally {
    clearTimeout(loadingTimer);
    busy.value = false;
  }
}
onMounted(refreshCaptcha);
onMounted(async () => {
  try {
    const features = await authApi.features();
    registrationEnabled.value = features.registrationEnabled;
    demoEnabled.value = features.demoEnabled;
    if (features.demoEnabled && !username.value && !password.value) loginMode.value = "demo";
  }
  catch { registrationEnabled.value = false; demoEnabled.value = false; }
  finally { featuresLoading.value = false; }
});
onBeforeUnmount(() => { ++captchaVersion; clearTimeout(expiryTimer); clearTimeout(loadingTimer); });
</script>
<template>
  <main class="auth-page auth-page--login">
    <AuthMotionScene :form-focused="formFocused" />
    <section class="auth-panel">
      <form class="auth-card" @focusin="formFocused = true" @focusout="onFormFocusOut" @submit.prevent="submit">
        <div>
          <span class="eyebrow">WELCOME TO OPSAGENT</span>
          <h2>{{ loginMode === 'demo' ? '体验智能运维工作台' : '欢迎回来' }}</h2>
          <p>{{ loginMode === 'demo' ? '从服务观测到 AI 处置，探索完整工作流程' : '使用已分配的账号登录 OpsAgent' }}</p>
          <p v-if="route.query.reason === 'expired'" role="status">登录已到期，重新登录后将返回刚才的页面。</p>
        </div>
        <div v-if="demoEnabled" class="auth-entry-switch" role="group" aria-label="选择登录方式">
          <button type="button" :aria-pressed="loginMode === 'demo'" :disabled="busy || featuresLoading" @click="selectLoginMode('demo')"><Sparkles :size="16" />演示体验</button>
          <button type="button" :aria-pressed="loginMode === 'account'" :disabled="busy || featuresLoading" @click="selectLoginMode('account')"><UserRound :size="16" />账号登录</button>
        </div>
        <div v-if="loginMode === 'demo'" class="auth-demo-entry">
          <span class="auth-demo-entry-icon"><Sparkles :size="21" /></span>
          <div><strong>无需记住账号密码</strong><p>输入下方验证码，即可进入演示工作台。</p><small>同浏览器体验保留 24 小时 · 每 30 分钟验证码续入</small></div>
        </div>
        <template v-else>
        <label
          >用户名
          <div class="input-with-icon">
            <UserRound :size="18" /><input
              v-model.trim="username"
              ref="usernameInput"
              required
              :disabled="busy || featuresLoading"
              maxlength="64"
              autocomplete="username"
              placeholder="请输入用户名"
            /></div></label
        ><label
          >密码
          <div class="input-with-icon password-field">
            <LockKeyhole :size="18" /><input
              v-model="password"
              required
              :disabled="busy || featuresLoading"
              :type="showPassword ? 'text' : 'password'"
              autocomplete="current-password"
              placeholder="请输入密码"
            /><button
              type="button"
              :disabled="busy || featuresLoading"
              class="password-toggle"
              :aria-label="showPassword ? '隐藏密码' : '显示密码'"
              :title="showPassword ? '隐藏密码' : '显示密码'"
              @click="showPassword = !showPassword"
            >
              <EyeOff v-if="showPassword" :size="18" /><Eye v-else :size="18" />
            </button></div
        ></label>
        </template>
        <div class="auth-captcha-field">
          <label for="login-captcha">验证码</label>
          <div class="auth-captcha-row">
            <div class="input-with-icon"><ShieldCheck :size="18" /><input id="login-captcha" ref="captchaInput" v-model.trim="captchaCode" required maxlength="5" autocomplete="off" autocapitalize="characters" :spellcheck="false" aria-describedby="captcha-help captcha-status" placeholder="输入图中字符" :disabled="captchaLoading || !captchaId || busy" /></div>
            <button type="button" class="auth-captcha-image" :disabled="captchaLoading || busy" aria-label="换一张图形验证码" title="看不清？点击换一张" @click="refreshCaptcha">
              <img v-if="captchaImage" :src="captchaImage" alt="五位字母或数字组成的图形验证码" width="192" height="64" />
              <span v-else>{{ captchaLoading ? '加载中…' : '点击重试' }}</span>
            </button>
          </div>
          <div class="auth-captcha-meta"><span id="captcha-help">不区分大小写 · 2 分钟内有效</span><button type="button" :disabled="captchaLoading || busy" @click="refreshCaptcha"><RefreshCw :size="13" />换一张</button></div>
          <p id="captcha-status" class="auth-captcha-status" role="status" aria-live="polite">{{ captchaError || (captchaExpired ? '验证码已过期，请换一张' : '') }}</p>
        </div>
        <InlineError v-if="error" :message="error" dismissible @dismiss="error = ''" />
        <p v-if="loadingHint" role="status" aria-live="polite">{{ loadingHint }}</p>
        <ActionButton class="primary auth-submit" :type="phase === 'load-failed' ? 'button' : 'submit'" :disabled="phase !== 'load-failed' && (featuresLoading || captchaLoading || !captchaId || captchaExpired)" :loading="busy" :success="succeeded" :loading-text="phase === 'loading' ? '正在加载工作台…' : '正在验证…'" success-text="登录成功" @click="phase === 'load-failed' && enterWorkbench()">{{ phase === 'load-failed' ? '重新打开工作台' : featuresLoading ? '正在加载登录方式…' : loginMode === 'demo' ? '进入演示工作台' : '登录工作台' }} <ArrowRight :size="18" /></ActionButton>
        <p class="auth-switch">
          <template v-if="loginMode === 'demo'">管理配置与正式处置，请切换到账号登录</template>
          <template v-else-if="registrationEnabled">还没有账号？<RouterLink to="/register">创建账号</RouterLink></template>
          <template v-else>请使用已分配的账号登录</template>
        </p>
      </form>
    </section>
  </main>
</template>
