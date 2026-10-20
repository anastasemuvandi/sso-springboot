/// <reference types="vitest/config" />
import { defineConfig, loadEnv } from "vite";
import react from "@vitejs/plugin-react";
import { fileURLToPath, URL } from "node:url";

// The console talks to exactly one origin: the BFF. In dev, Vite proxies the
// BFF-owned prefixes to it so cookies stay first-party. Override the target
// with CONSOLE_BFF_URL (default http://localhost:3001).
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), "");
  const bff = env.CONSOLE_BFF_URL ?? "http://localhost:3001";
  const proxied = ["/api", "/bff", "/oauth2", "/login"];
  const proxy = Object.fromEntries(
    proxied.map((p) => [p, { target: bff, changeOrigin: false, xfwd: true }]),
  );
  return {
    plugins: [react()],
    resolve: { alias: { "@": fileURLToPath(new URL("./src", import.meta.url)) } },
    server: { port: 5173, proxy },
    preview: { port: 4173, proxy },
    build: {
      sourcemap: true,
      chunkSizeWarningLimit: 4000,
    },
    test: {
      globals: true,
      environment: "jsdom",
      setupFiles: ["./src/test/setup.ts"],
      include: ["src/**/*.test.{ts,tsx}"],
      css: false,
    },
  };
});
