<script setup lang="ts">
import { ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import {
  Activity,
  ArrowRight,
  LockKeyhole,
  UserRound,
  CheckCircle2,
  Eye,
  EyeOff,
} from "@lucide/vue";
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
const showPassword = ref(false);
const error = ref("");
const busy = ref(false);
const succeeded = ref(false);
const toast = useToast();
const formFocused = ref(false);
function onFormFocusOut(event: FocusEvent) {
  formFocused.value = (event.currentTarget as HTMLElement).contains(event.relatedTarget as Node | null);
}
async function submit() {
  if (busy.value) return;
  error.value = "";
  busy.value = true;
  try {
    await auth.login(username.value, password.value);
    succeeded.value = true;
    toast.show("登录成功，欢迎回到工作台");
    await router.push(String(route.query.redirect || "/dashboard"));
  } catch (e) {
    error.value = e instanceof Error ? e.message : "登录失败";
  } finally {
    busy.value = false;
  }
}
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
        <InlineError v-if="error" :message="error" dismissible @dismiss="error = ''" />
        <ActionButton class="primary auth-submit" :loading="busy && !succeeded" :success="succeeded" loading-text="正在验证…" success-text="登录成功">进入工作台 <ArrowRight :size="18" /></ActionButton>
        <p class="auth-switch">
          还没有账号？<RouterLink to="/register">创建账号</RouterLink>
        </p>
      </form>
    </section>
  </main>
</template>
