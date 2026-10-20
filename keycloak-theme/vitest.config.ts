import { defineConfig } from "vitest/config";

// Kept separate from vite.config.ts so tests do not run the keycloakify plugin.
export default defineConfig({
    test: {
        include: ["src/**/*.test.ts"],
        environment: "node"
    }
});
