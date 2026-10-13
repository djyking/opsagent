import { defineConfig } from "vite";
import vue from "@vitejs/plugin-vue";
import { fileURLToPath, URL } from "node:url";
import { graphCdnPlugin } from "./build/graph-cdn-plugin";

export default defineConfig({
  base: "/",
  plugins: [vue(), graphCdnPlugin(process.env.OPSAGENT_GRAPH_CDN_ORIGIN || "")],
  worker: { rollupOptions: { output: { entryFileNames: "assets/worker-[name]-[hash].js" } } },
  experimental: {
    renderBuiltUrl(filename, context) {
      if (context.hostType === "js" && filename.startsWith("assets/worker-")) {
        // The outer Worker URL may be evaluated in a CDN module; anchor it to the page origin.
        return { runtime: `new URL(${JSON.stringify("/" + filename)},globalThis.location.origin).href` };
      }
    },
  },
  resolve: {
    alias: { "@": fileURLToPath(new URL("./src", import.meta.url)) },
  },
  server: {
    port: 5173,
    open: false,
    proxy: {
      "/api": { target: "http://localhost:18080", changeOrigin: true },
      "/actuator": { target: "http://localhost:18080", changeOrigin: true },
    },
  },
});
