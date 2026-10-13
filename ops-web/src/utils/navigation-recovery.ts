import { ref } from "vue";
import type { Router } from "vue-router";

export const navigationError = ref<{ destination: string; message: string }>();
const retryKey = "opsagent-navigation-reload-at";

export function installNavigationRecovery(router: Router) {
  router.onError((error, to) => {
    const isAssetError = /Failed to fetch dynamically imported module|Importing a module script failed|error loading dynamically imported module|Unable to preload CSS/i.test(error.message);
    if (!isAssetError) {
      navigationError.value = { destination: to.fullPath, message: "页面暂时无法打开，请重试。" };
      return;
    }
    // Recover an open tab after a deployment, but never create a reload loop.
    const now = Date.now();
    let canReload = false;
    try {
      const lastAttempt = Number(sessionStorage.getItem(retryKey) || 0);
      if (navigator.onLine && now - lastAttempt > 60_000) {
        sessionStorage.setItem(retryKey, String(now));
        canReload = true;
      }
    } catch { /* Storage may be unavailable; keep the explicit retry action. */ }
    if (canReload) {
      window.location.replace(to.fullPath);
    } else {
      navigationError.value = { destination: to.fullPath, message: "页面资源加载失败，请检查连接后重新打开。" };
    }
  });
  router.afterEach((_to, _from, failure) => {
    if (!failure) navigationError.value = undefined;
  });
}

export function retryNavigation() {
  if (navigationError.value) window.location.assign(navigationError.value.destination);
}
