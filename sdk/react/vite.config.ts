/// <reference types="vitest" />
import { resolve } from "node:path";
import react from "@vitejs/plugin-react";
import { defineConfig } from "vite";

// Library mode: ESM + CJS bundles; React stays a peer (never bundled).
export default defineConfig({
  plugins: [react()],
  build: {
    lib: {
      entry: resolve(__dirname, "src/index.ts"),
      name: "GorssoReact",
      fileName: "gorsso-react",
      formats: ["es", "cjs"],
    },
    sourcemap: true,
    rollupOptions: {
      external: ["react", "react-dom", "react/jsx-runtime"],
    },
  },
  test: {
    environment: "jsdom",
    globals: true,
    setupFiles: ["./src/test-setup.ts"],
  },
});
