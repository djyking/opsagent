<script setup lang="ts">
import { onMounted, ref } from "vue";
import { useRouter } from "vue-router";
import { ArrowLeft, UserPlus, Eye, EyeOff } from "@lucide/vue";
import { authApi } from "@/api/modules";
import InlineError from "@/components/InlineError.vue";
import { usePageFeedback } from "@/composables/usePageFeedback";
import ActionButton from "@/components/feedback/ActionButton.vue";
import AuthBrandPanel from "@/components/auth/AuthBrandPanel.vue";
const router = useRouter();
const form = ref({ username: "", displayName: "", password: "", confirm: "" });
const showPassword = ref(false);
const showConfirmPassword = ref(false);
const error = ref("");
const toast = usePageFeedback(error);
const busy = ref(false);
const registrationEnabled = ref(false);
const featuresLoading = ref(true);
onMounted(async () => {
  try { registrationEnabled.value = (await authApi.features()).registrationEnabled; }
  catch { error.value = "暂时无法确认注册状态，请稍后重试"; }
  finally { featuresLoading.value = false; }
});
async function submit() {
  if (busy.value || !registrationEnabled.value) return;
  if (form.value.password !== form.value.confirm) {
    error.value = "两次输入的密码不一致";
    return;
  }
  busy.value = true;
  error.value = "";
  try {
    await authApi.register({
      username: form.value.username,
      password: form.value.password,
      displayName: form.value.displayName || undefined,
    });
    await router.push({ path: "/login", query: { registered: "1" } });
    toast.show("账号已创建，请登录工作台");
  } catch (e) {
    error.value = e instanceof Error ? e.message : "注册失败";
  } finally {
    busy.value = false;
  }
}
</script>
<template>
  <main class="register-page">
    <AuthBrandPanel />
    <section class="auth-panel register-panel">
      <form v-if="registrationEnabled" class="auth-card register-card" @submit.prevent="submit">
        <div>
          <button
            type="button"
            class="text-button"
            @click="router.push('/login')"
          >
            <ArrowLeft :size="16" /> 返回登录
          </button>
          <h2>创建账号</h2>
          <p>创建用于登录 OpsAgent 工作台的账号</p>
        </div>
        <label
          >用户名<input
            v-model.trim="form.username"
            required
            autocomplete="username"
            maxlength="64"
            placeholder="用于登录" /></label
        ><label
          >显示名称<input
            v-model.trim="form.displayName"
            autocomplete="nickname"
            maxlength="64"
            placeholder="选填" /></label
        ><label
          >密码
          <small class="field-help">使用 6～72 位字符</small>
          <div class="password-field">
            <input
              v-model="form.password"
              required
              minlength="6"
              maxlength="72"
              :type="showPassword ? 'text' : 'password'"
              autocomplete="new-password"
            /><button
              type="button"
              class="password-toggle"
              :aria-label="showPassword ? '隐藏密码' : '显示密码'"
              :title="showPassword ? '隐藏密码' : '显示密码'"
              @click="showPassword = !showPassword"
            >
              <EyeOff v-if="showPassword" :size="18" /><Eye v-else :size="18" />
            </button></div></label
        ><label
          >确认密码
          <div class="password-field">
            <input
              v-model="form.confirm"
              required
              :type="showConfirmPassword ? 'text' : 'password'"
              autocomplete="new-password"
            /><button
              type="button"
              class="password-toggle"
              :aria-label="
                showConfirmPassword ? '隐藏确认密码' : '显示确认密码'
              "
              :title="showConfirmPassword ? '隐藏确认密码' : '显示确认密码'"
              @click="showConfirmPassword = !showConfirmPassword"
            >
              <EyeOff v-if="showConfirmPassword" :size="18" /><Eye
                v-else
                :size="18"
              />
            </button></div
        ></label>
        <InlineError v-if="error" :message="error" dismissible @dismiss="error = ''" />
        <ActionButton class="primary auth-submit" :loading="busy" loading-text="创建中…">
          <UserPlus :size="18" />创建账号
        </ActionButton>
      </form>
      <div v-else class="auth-card register-card">
        <h2>{{ featuresLoading ? '正在确认注册状态' : '使用已有账号登录' }}</h2>
        <p>{{ error || (featuresLoading ? '请稍候…' : '当前环境未开放注册，请联系管理员获取账号。') }}</p>
        <RouterLink to="/login" class="button secondary"><ArrowLeft :size="16" />返回登录</RouterLink>
      </div>
    </section>
  </main>
</template>

<style scoped>
.register-page { display: grid; grid-template-columns: minmax(0, 1.15fr) minmax(390px, .85fr); min-height: 100svh; background: var(--oa-bg-surface); }
.register-page .register-panel { padding: 42px clamp(28px, 4vw, 72px); }
.register-page .register-card { width: min(400px, 100%); gap: 18px; }
.register-card > div:first-child { margin-bottom: 4px; }
.register-card h2 { margin: 18px 0 9px; font-size: 32px; line-height: 1.25; color: var(--oa-text-primary); }
.register-card .text-button { color: var(--oa-text-secondary); padding: 0; }
.register-card .text-button:hover { color: var(--oa-primary); }
@media (max-width: 900px) {
  .register-page { display: flex; flex-direction: column; }
  .register-page .register-panel { flex: 1; min-height: 0; padding: 28px; }
  .register-page .register-card { gap: 16px; }
  .register-card h2 { font-size: 28px; margin-top: 12px; }
}
</style>
