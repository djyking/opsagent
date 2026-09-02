import { computed, ref } from "vue";
import { defineStore } from "pinia";
import { authApi } from "@/api/modules";
import type { CurrentUser } from "@/types/api";

export const useAuthStore = defineStore("auth", () => {
  const user = ref<CurrentUser | null>(null);
  const token = ref(localStorage.getItem("opsagent_token"));
  const loading = ref(false);
  const isAuthenticated = computed(() => Boolean(token.value));
  const isAdmin = computed(() => user.value?.roles.includes("ADMIN") ?? false);
  const isOps = computed(() => user.value?.roles.includes("OPS") ?? false);

  async function login(username: string, password: string) {
    const result = await authApi.login({ username, password });
    token.value = result.accessToken;
    localStorage.setItem("opsagent_token", result.accessToken);
    localStorage.setItem("opsagent_refresh_token", result.refreshToken);
    localStorage.setItem("opsagent_token_expire_at", result.expiresAt);
    await fetchMe();
  }

  async function fetchMe() {
    if (!token.value) return;
    loading.value = true;
    try {
      user.value = await authApi.me();
    } finally {
      loading.value = false;
    }
  }

  function logout() {
    token.value = null;
    user.value = null;
    localStorage.removeItem("opsagent_token");
    localStorage.removeItem("opsagent_refresh_token");
    localStorage.removeItem("opsagent_token_expire_at");
  }

  return {
    user,
    token,
    loading,
    isAuthenticated,
    isAdmin,
    isOps,
    login,
    fetchMe,
    logout,
  };
});
