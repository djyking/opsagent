<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import {
  ArrowRight,
  LockKeyhole,
  UserRound,
  Eye,
  EyeOff,
  RefreshCw,
  ShieldCheck,
} from "@lucide/vue";
import { authApi } from "@/api/modules";
import { useAuthStore } from "@/stores/auth";
import InlineError from "@/components/InlineError.vue";
import AuthMotionScene from "@/components/auth/AuthMotionScene.vue";
import ActionButton from "@/components/feedback/ActionButton.vue";
import { useToast } from "@/composables/useToast";

const auth = useAuthStore();
const router = useRouter();
const route = useRoute();
const username = ref("");
const password = ref("");
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
const toast = useToast();
const formFocused = ref(false);
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
  if (busy.value || captchaLoading.value) return;
  if (!captchaId.value || captchaExpired.value) {
    await refreshCaptcha();
    return;
  }
  error.value = "";
  busy.value = true;
  try {
    await auth.login(username.value, password.value, captchaId.value, captchaCode.value);
    succeeded.value = true;
    toast.show("登录成功，欢迎回到工作台");
    await router.push(String(route.query.redirect || "/dashboard"));
  } catch (e) {
    error.value = e instanceof Error ? e.message : "登录失败";
    await refreshCaptcha();
  } finally {
    busy.value = false;
  }
}
onMounted(refreshCaptcha);
onBeforeUnmount(() => { ++captchaVersion; clearTimeout(expiryTimer); });
</script>
<template>
  <main class="auth-page auth-page--login">
    <AuthMotionScene :form-focused="formFocused" />
    <section class="auth-panel">
      <form class="auth-card" @focusin="formFocused = true" @focusout="onFormFocusOut" @submit.prevent="submit">
        <div>
          <span class="eyebrow">WELCOME BACK</span>
          <h2>欢迎回来</h2>
          <p>登录 OpsAgent，继续今天的工作</p>
        </div>
        <label
          >用户名
          <div class="input-with-icon">
            <UserRound :size="18" /><input
              v-model.trim="username"
              required
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
              :type="showPassword ? 'text' : 'password'"
              autocomplete="current-password"
              placeholder="请输入密码"
            /><button
              type="button"
              class="password-toggle"
              :aria-label="showPassword ? '隐藏密码' : '显示密码'"
              :title="showPassword ? '隐藏密码' : '显示密码'"
              @click="showPassword = !showPassword"
            >
              <EyeOff v-if="showPassword" :size="18" /><Eye v-else :size="18" />
            </button></div
        ></label>
        <div class="auth-captcha-field">
          <label for="login-captcha">验证码</label>
          <div class="auth-captcha-row">
            <div class="input-with-icon"><ShieldCheck :size="18" /><input id="login-captcha" v-model.trim="captchaCode" required maxlength="5" autocomplete="off" autocapitalize="characters" :spellcheck="false" aria-describedby="captcha-help captcha-status" placeholder="输入图中字符" :disabled="captchaLoading || !captchaId || busy" /></div>
            <button type="button" class="auth-captcha-image" :disabled="captchaLoading || busy" aria-label="换一张图形验证码" title="看不清？点击换一张" @click="refreshCaptcha">
              <img v-if="captchaImage" :src="captchaImage" alt="五位字母或数字组成的图形验证码" width="192" height="64" />
              <span v-else>{{ captchaLoading ? '加载中…' : '点击重试' }}</span>
            </button>
          </div>
          <div class="auth-captcha-meta"><span id="captcha-help">不区分大小写 · 2 分钟内有效</span><button type="button" :disabled="captchaLoading || busy" @click="refreshCaptcha"><RefreshCw :size="13" />换一张</button></div>
          <p id="captcha-status" class="auth-captcha-status" role="status" aria-live="polite">{{ captchaError || (captchaExpired ? '验证码已过期，请换一张' : '') }}</p>
        </div>
        <InlineError v-if="error" :message="error" dismissible @dismiss="error = ''" />
        <ActionButton class="primary auth-submit" :disabled="captchaLoading || !captchaId || captchaExpired" :loading="busy && !succeeded" :success="succeeded" loading-text="正在验证…" success-text="登录成功">进入工作台 <ArrowRight :size="18" /></ActionButton>
        <p class="auth-switch">
          还没有账号？<RouterLink to="/register">创建账号</RouterLink>
        </p>
      </form>
    </section>
  </main>
</template>
