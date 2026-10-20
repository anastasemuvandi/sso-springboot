/// <reference types="vitest" />
import react from "@vitejs/plugin-react";
import { defineConfig, loadEnv } from "vite";

// One SPA, two ministries: `--mode minagri` / `--mode minisante` pick `.env.<mode>`.
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), "");
  return {
    plugins: [react()],
    resolve: {
      // @gorsso/react is linked from ../../sdk/react; make sure there is ONE React.
      dedupe: ["react", "react-dom", "react-router-dom"],
    },
    server: {
      port: Number(env.VITE_DEV_PORT || 5173),
      strictPort: true,
      fs: { allow: ["..", "../../sdk/react"] },
      // Same-origin /api in dev → the sample API (no CORS needed).
      proxy: { "/api": { target: env.API_PROXY_TARGET || "http://localhost:8081", changeOrigin: true } },
    },
    build: { outDir: `dist/${mode === "production" ? "minagri" : mode}`, emptyOutDir: true },
    test: { environment: "jsdom", globals: true, setupFiles: ["./src/test-setup.ts"] },
  };
});
