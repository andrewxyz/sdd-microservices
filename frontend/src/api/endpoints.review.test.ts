import { beforeEach, describe, expect, it, vi } from "vitest";
import * as endpoints from "./endpoints";

function jsonResponse(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
}

function lastCall(): { url: string; init: RequestInit } {
  const fetchMock = fetch as unknown as ReturnType<typeof vi.fn>;
  const call = fetchMock.mock.calls.at(-1) as [string, RequestInit];
  return { url: String(call[0]), init: call[1] };
}

describe("review endpoint helpers", () => {
  beforeEach(() => {
    vi.stubGlobal("fetch", vi.fn(async () => jsonResponse(200, { items: [] })));
  });

  it("listReviews GETs the course's reviews with the page number", async () => {
    await endpoints.listReviews("c-1", 2);
    const { url, init } = lastCall();
    expect(url).toBe("/api/v1/reviews/courses/c-1?page=2");
    expect(init.method).toBe("GET");
  });

  it("getReviewSummary GETs the single-course summary", async () => {
    await endpoints.getReviewSummary("c-1");
    expect(lastCall().url).toBe("/api/v1/reviews/courses/c-1/summary");
  });

  it("getReviewSummaries joins the ids with commas", async () => {
    await endpoints.getReviewSummaries(["a", "b", "c"]);
    expect(lastCall().url).toBe("/api/v1/reviews/summaries?courseIds=a,b,c");
  });

  it("getReviewSummaries refuses more than 50 ids without calling the network", () => {
    const ids = Array.from({ length: 51 }, (_, i) => `id-${i}`);
    expect(() => endpoints.getReviewSummaries(ids)).toThrow();
    expect(fetch).not.toHaveBeenCalled();
  });

  it("createReview POSTs rating and comment to the course", async () => {
    await endpoints.createReview("c-1", { rating: 5, comment: "Great" });
    const { url, init } = lastCall();
    expect(url).toBe("/api/v1/reviews/courses/c-1");
    expect(init.method).toBe("POST");
    expect(JSON.parse(String(init.body))).toEqual({ rating: 5, comment: "Great" });
  });

  it("updateReview PUTs to the review id", async () => {
    await endpoints.updateReview("r-1", { rating: 3, comment: null });
    const { url, init } = lastCall();
    expect(url).toBe("/api/v1/reviews/r-1");
    expect(init.method).toBe("PUT");
    expect(JSON.parse(String(init.body))).toEqual({ rating: 3, comment: null });
  });

  it("deleteReview DELETEs the review id", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => new Response(null, { status: 204 })));
    await endpoints.deleteReview("r-1");
    const { url, init } = lastCall();
    expect(url).toBe("/api/v1/reviews/r-1");
    expect(init.method).toBe("DELETE");
  });
});
