import tailwindcss from "@tailwindcss/vite";
import react from "@vitejs/plugin-react";
import { loadEnv } from "vite";
import { defineConfig } from "vitest/config";

// The repo-root .env is shared by the backend and the frontend.
const ENV_DIR = "..";

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, ENV_DIR, "VITE_");
  // In development the Vite server forwards /api to the Java backend, so the browser still
  // sees a single origin — the same as in the packaged app, where the backend serves both.
  const apiTarget = env.VITE_API_PROXY_TARGET;

  return {
    envDir: ENV_DIR,
    plugins: [react(), tailwindcss()],
    server: {
      proxy: apiTarget ? { "/api": { target: apiTarget, changeOrigin: true } } : undefined,
    },
    test: {
      environment: "jsdom",
      include: ["tests/**/*.test.{ts,tsx}"],
      setupFiles: ["tests/setup.ts"],
      css: false,
    },
  };
});
