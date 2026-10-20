// Global Vitest setup: fetch is always mocked, never real (research.md R14 — no test ever touches
// a real network). Individual tests install their own fetch implementation via vi.fn(); this file
// only guarantees a clean slate between tests so one test's mock can never leak into the next.
import { afterEach, vi } from "vitest";

afterEach(() => {
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
  sessionStorage.clear();
  localStorage.clear();
});
