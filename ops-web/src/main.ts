import { createApp } from "vue";
import { createPinia } from "pinia";
import App from "./App.vue";
import router from "./router";
import { installNavigationRecovery } from "./utils/navigation-recovery";
import { installSessionLifecycle } from "./api/session";
import "./styles/tokens.css";
import "./styles/main.css";
import "./styles/motion.css";

installNavigationRecovery(router);
installSessionLifecycle();
createApp(App).use(createPinia()).use(router).mount("#app");
