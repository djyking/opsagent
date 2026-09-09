import { computed, ref } from "vue";
import { defineStore } from "pinia";
import { authApi } from "@/api/modules";
import type { CurrentUser } from "@/types/api";
import { ensureAccessToken, readSession, saveLogin, subscribeSession, logoutSession } from "@/api/session";

export const useAuthStore = defineStore("auth", () => {
  const user = ref<CurrentUser | null>(null);
  const token = ref(readSession()?.accessToken ?? null);
  const identity = ref(readSession()?.identity ?? null);
  subscribeSession(() => {
    const session = readSession();
    token.value = session?.accessToken ?? null;
    if ((session?.identity ?? null) !== identity.value) user.value = null;
    identity.value = session?.identity ?? null;
  });
  const loading = ref(false);
  const isAuthenticated = computed(() => Boolean(token.value));
  const isAdmin = computed(() => user.value?.roles.includes("ADMIN") ?? false);
  const isOps = computed(() => user.value?.roles.includes("OPS") ?? false);
  const isDemo = computed(() => user.value?.roles.includes("DEMO") ?? false);

  async function login(username: string, password: string, captchaId: string, captchaCode: string) {
    const result = await authApi.login({ username, password, captchaId, captchaCode });
    saveLogin(result);
    return result;
  }

  async function fetchMe() {
    if (!token.value) return;
    loading.value = true;
    try {
      await ensureAccessToken();
      const requestedSession = readSession()?.identity;
      const profile = await authApi.me();
      if (readSession()?.identity === requestedSession) user.value = profile;
    } finally {
      loading.value = false;
    }
  }

  function logout() {
    logoutSession();
  }

  return {
    user,
    token,
    identity,
    loading,
    isAuthenticated,
    isAdmin,
    isOps,
    isDemo,
    login,
    fetchMe,
    logout,
  };
});
