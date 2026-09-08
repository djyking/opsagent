<script setup lang="ts">
import { computed, onBeforeUnmount, ref } from 'vue';
import { useRoute } from 'vue-router';
import { ensureAccessToken, getSessionNotice, readSession, safeReturnPath, subscribeSession } from '@/api/session';
const route = useRoute();
const notice = ref(getSessionNotice());
const busy = ref(false);
const unsubscribe = subscribeSession(() => { notice.value = getSessionNotice(); });
onBeforeUnmount(unsubscribe);
const login = computed(() => ({ path: '/login', query: { redirect: safeReturnPath(route.fullPath), reason: 'expired' } }));
async function retry() {
  busy.value = true;
  try { await ensureAccessToken(readSession()?.accessToken); } catch { /* The session service retains a precise message. */ }
  finally { busy.value = false; }
}
</script>
<template>
  <aside v-if="notice && route.path !== '/login'" class="session-notice" :class="notice.kind" role="alert" aria-live="polite">
    <span>{{ notice.message }}</span>
    <RouterLink v-if="notice.kind === 'expired'" :to="login">重新登录</RouterLink>
    <button v-else-if="notice.kind === 'network'" type="button" :disabled="busy" @click="retry">{{ busy ? '正在连接…' : '重试续期' }}</button>
  </aside>
</template>
<style scoped>
.session-notice { position: fixed; z-index: 1600; top: 14px; left: 50%; transform: translateX(-50%); display: flex; align-items: center; gap: 20px; width: max-content; max-width: calc(100vw - 32px); padding: 12px 18px; border: 1px solid #b6cef2; border-radius: 10px; background: #f5f9ff; box-shadow: 0 6px 24px #1e3a5f1a; color: #254369; font-size: 14px; }
.session-notice.expired, .session-notice.warning { border-color: #e8c48c; background: #fffaf2; color: #714c1e; }
.session-notice button, .session-notice a { flex-shrink: 0; color: #2563b4; background: transparent; border: 0; text-decoration: underline; cursor: pointer; }
</style>
