import { beforeEach, describe, expect, it, vi } from "vitest";
import { clear, setTokens } from "../auth/session";
import { request, setUnauthorizedHandler } from "./client";

function jsonResponse(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

describe("client single-flight refresh", () => {
  beforeEach(() => {
    clear();
    setUnauthorizedHandler(null);
  });

  it("five parallel requests that all receive 401 cause exactly one refresh call, and each is retried once", async () => {
    setTokens("stale-access", "refresh-1");

    let refreshCalls = 0;
    const seenBefore = new Set<string>();

    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      const method = init?.method ?? "GET";

      if (url === "/api/v1/auth/refresh" && method === "POST") {
        refreshCalls += 1;
        return jsonResponse(200, { accessToken: "fresh-access", refreshToken: "refresh-2" });
      }

      // Each distinct course path returns 401 the first time it is called, then 200.
      if (!seenBefore.has(url)) {
        seenBefore.add(url);
        return jsonResponse(401, { title: "Unauthorized", status: 401 });
      }
      return jsonResponse(200, { id: url });
    });
    vi.stubGlobal("fetch", fetchMock);

    const results = await Promise.all(
      [0, 1, 2, 3, 4].map((index) => request<{ id: string }>(`/v1/courses/${index}`)),
    );

    expect(refreshCalls).toBe(1);
    results.forEach((result, index) => {
      expect(result.id).toBe(`/api/v1/courses/${index}`);
    });

    // Each original path was hit exactly twice: once 401, once retried-and-succeeded.
    const courseCalls = fetchMock.mock.calls.filter(([input]) => String(input).includes("/v1/courses/"));
    expect(courseCalls).toHaveLength(10);
  });
});
