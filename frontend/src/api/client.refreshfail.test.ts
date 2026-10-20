import { beforeEach, describe, expect, it, vi } from "vitest";
import { clear, getAccessToken, getRefreshToken, setTokens } from "../auth/session";
import { ApiError } from "./problem";
import { request, setUnauthorizedHandler } from "./client";

function jsonResponse(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status });
}

describe("client refresh failure", () => {
  beforeEach(() => {
    clear();
    setUnauthorizedHandler(null);
  });

  it("clears the session and reports the current path when refresh itself returns 401", async () => {
    setTokens("stale-access", "spent-refresh");

    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      if (url === "/api/v1/auth/refresh" && (init?.method ?? "GET") === "POST") {
        return jsonResponse(401, { title: "Unauthorized", status: 401 });
      }
      return jsonResponse(401, { title: "Unauthorized", status: 401 });
    });
    vi.stubGlobal("fetch", fetchMock);

    const redirects: string[] = [];
    setUnauthorizedHandler((returnTo) => redirects.push(returnTo));

    await expect(request("/v1/enrollments")).rejects.toBeInstanceOf(ApiError);

    expect(getAccessToken()).toBeNull();
    expect(getRefreshToken()).toBeNull();
    expect(redirects).toHaveLength(1);
    expect(redirects[0]).toBe(window.location.pathname + window.location.search);

    // Only one refresh attempt — the retry is capped so a dead session cannot loop.
    const refreshCalls = fetchMock.mock.calls.filter(([input]) => String(input) === "/api/v1/auth/refresh");
    expect(refreshCalls).toHaveLength(1);
  });

  it("does not attempt a refresh at all when no access token was sent", async () => {
    const fetchMock = vi.fn(async (_input: RequestInfo | URL) =>
      jsonResponse(401, { title: "Unauthorized", status: 401 }),
    );
    vi.stubGlobal("fetch", fetchMock);

    await expect(request("/v1/auth/me")).rejects.toBeInstanceOf(ApiError);

    const refreshCalls = fetchMock.mock.calls.filter(([input]) => String(input) === "/api/v1/auth/refresh");
    expect(refreshCalls).toHaveLength(0);
  });
});
