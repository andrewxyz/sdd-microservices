/// <reference types="vitest/config" />
import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// Dev server on 9100 (constitution amendment: frontend port 9100), proxying /api to the gateway so
// every call is same-origin and CORS never enters the picture (research.md R1, constitution XIII).
export default defineConfig({
  plugins: [react()],
  server: {
    port: 9100,
    strictPort: true,
    proxy: {
      "/api": {
        target: "http://localhost:9080",
        changeOrigin: true,
      },
    },
  },
  preview: {
    port: 9100,
    strictPort: true,
  },
  test: {
    environment: "jsdom",
    setupFiles: ["./src/test/setup.ts"],
    globals: true,
  },
});
