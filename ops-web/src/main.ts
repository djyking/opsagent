import { createApp } from "vue";
import { createPinia } from "pinia";
import App from "./App.vue";
import router from "./router";
import { installNavigationRecovery } from "./utils/navigation-recovery";
import "./styles/tokens.css";
import "./styles/main.css";
import "./styles/motion.css";

installNavigationRecovery(router);
createApp(App).use(createPinia()).use(router).mount("#app");
