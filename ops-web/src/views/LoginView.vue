<script setup lang="ts">
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Activity, ArrowRight, LockKeyhole, UserRound, CheckCircle2, Eye, EyeOff } from '@lucide/vue'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore(); const router = useRouter(); const route = useRoute()
const username = ref(''); const password = ref(''); const showPassword = ref(false); const error = ref(''); const busy = ref(false)
async function submit() { error.value=''; busy.value=true; try { await auth.login(username.value, password.value); await router.push(String(route.query.redirect || '/dashboard')) } catch(e) { error.value = e instanceof Error ? e.message : '登录失败' } finally { busy.value=false } }
</script>
<template>
  <main class="auth-page">
    <section class="auth-story">
      <div class="auth-brand"><span><Activity :size="22" /></span> OpsAgent</div>
      <div class="story-copy"><span class="eyebrow light">INTELLIGENT OPERATIONS</span><h1>让每一次故障处理，<br><em>都有迹可循。</em></h1><p>统一管理运维工单、故障文档与智能问答，在一个清晰的工作流中完成问题闭环。</p><ul><li><CheckCircle2 :size="18" /> 工单状态与责任人全程可追踪</li><li><CheckCircle2 :size="18" /> 多格式文档解析与结构化切片</li><li><CheckCircle2 :size="18" /> 基于文档上下文的可信问答</li></ul></div>
      <div class="story-metric"><strong>01</strong><span>业务闭环<br>从创建到归档</span></div>
    </section>
    <section class="auth-panel"><form class="auth-card" @submit.prevent="submit"><div><span class="eyebrow">WELCOME BACK</span><h2>登录工作台</h2><p>使用你的 OpsAgent 账号继续</p></div><label>用户名<div class="input-with-icon"><UserRound :size="18" /><input v-model.trim="username" required maxlength="64" autocomplete="username" placeholder="请输入用户名" /></div></label><label>密码<div class="input-with-icon password-field"><LockKeyhole :size="18" /><input v-model="password" required :type="showPassword?'text':'password'" autocomplete="current-password" placeholder="请输入密码" /><button type="button" class="password-toggle" :aria-label="showPassword?'隐藏密码':'显示密码'" :title="showPassword?'隐藏密码':'显示密码'" @click="showPassword=!showPassword"><EyeOff v-if="showPassword" :size="18"/><Eye v-else :size="18"/></button></div></label><p v-if="error" class="form-error">{{ error }}</p><button class="button primary auth-submit" :disabled="busy">{{ busy ? '正在验证…' : '进入工作台' }} <ArrowRight :size="18" /></button><p class="auth-switch">还没有账号？<RouterLink to="/register">创建账号</RouterLink></p></form></section>
  </main>
</template>
