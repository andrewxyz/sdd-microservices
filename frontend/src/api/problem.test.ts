import { describe, expect, it } from "vitest";
import { ApiError, parseProblemResponse } from "./problem";

function problemResponse(status: number, body: Record<string, unknown>, headers: Record<string, string> = {}) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/problem+json", ...headers },
  });
}

describe("parseProblemResponse", () => {
  it("parses a problem+json body into an ApiError with status, title, detail and correlationId", async () => {
    const response = problemResponse(404, {
      type: "about:blank",
      title: "Not Found",
      status: 404,
      detail: "No such course.",
      correlationId: "6f1c2b7e-9d3a-4c55-8a10-2f4e7d9b1a33",
    });

    const error = await parseProblemResponse(response);

    expect(error).toBeInstanceOf(ApiError);
    expect(error.status).toBe(404);
    expect(error.title).toBe("Not Found");
    expect(error.detail).toBe("No such course.");
    expect(error.correlationId).toBe("6f1c2b7e-9d3a-4c55-8a10-2f4e7d9b1a33");
  });

  it("reads Retry-After on a 429 into retryAfterSeconds", async () => {
    const response = problemResponse(
      429,
      { type: "about:blank", title: "Too Many Requests", status: 429, correlationId: "abc12345" },
      { "Retry-After": "30" },
    );

    const error = await parseProblemResponse(response);

    expect(error.status).toBe(429);
    expect(error.retryAfterSeconds).toBe(30);
    expect(error.message).toContain("30");
  });

  it("produces a network-failure ApiError with status 0 when fetch itself throws", () => {
    const error = ApiError.fromNetworkFailure(new TypeError("Failed to fetch"));

    expect(error.status).toBe(0);
    expect(error.message).toMatch(/could not reach/i);
    expect(error.correlationId).toBeUndefined();
  });

  it("maps a 503 to a friendly temporarily-unavailable message", async () => {
    const response = problemResponse(503, {
      type: "about:blank",
      title: "Service Unavailable",
      status: 503,
      correlationId: "service-down-1",
    });

    const error = await parseProblemResponse(response);

    expect(error.message).toMatch(/temporarily unavailable/i);
  });

  it("falls back to a generic message for an unrecognized status", async () => {
    const response = problemResponse(418, {
      type: "about:blank",
      title: "I'm a teapot",
      status: 418,
      correlationId: "teapot-1",
    });

    const error = await parseProblemResponse(response);

    expect(error.message.length).toBeGreaterThan(0);
  });

  it("tolerates a non-JSON error body without throwing", async () => {
    const response = new Response("plain text failure", { status: 500 });

    const error = await parseProblemResponse(response);

    expect(error.status).toBe(500);
    expect(error.message.length).toBeGreaterThan(0);
  });
});
