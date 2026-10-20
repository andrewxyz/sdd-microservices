import { act, renderHook } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { useCheckout } from "./useCheckout";

function jsonResponse(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
}

async function submitAndGetResult(fetchImpl: (input: RequestInfo | URL, init?: RequestInit) => Promise<Response>) {
  vi.stubGlobal("fetch", vi.fn(fetchImpl));
  vi.stubGlobal("crypto", { randomUUID: () => "key-1" });
  const { result } = renderHook(() => useCheckout("course-1"));
  act(() => {
    result.current.setChoice("sim-success");
  });
  await act(async () => {
    await result.current.submit();
  });
  return result;
}

describe("useCheckout — outcome mapping (research.md R0.2)", () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });

  it("treats 200 (idempotent replay) the same as 201", async () => {
    const result = await submitAndGetResult(async (input) => {
      if (String(input) === "/api/v1/checkouts") {
        return jsonResponse(200, { id: "pay-1", courseId: "course-1", status: "SUCCEEDED" });
      }
      return jsonResponse(200, { courseId: "course-1", completedLessons: 0, totalLessons: 1, percentComplete: 0 });
    });
    expect(result.current.phase).toBe("enrolled");
  });

  it("maps 403 to 'your own course', not a 409", async () => {
    const result = await submitAndGetResult(async () =>
      jsonResponse(403, { title: "Forbidden", status: 403, detail: "Own course." }),
    );
    expect(result.current.phase).toBe("blocked");
    expect(result.current.error?.status).toBe(403);
  });

  it("treats 409 as already-enrolled by default", async () => {
    const result = await submitAndGetResult(async () =>
      jsonResponse(409, { title: "Conflict", status: 409, detail: "Already enrolled." }),
    );
    expect(result.current.phase).toBe("blocked");
    expect(result.current.error?.status).toBe(409);
  });

  it("gives 404, 422 and 503 each their own status, rather than collapsing them", async () => {
    for (const status of [404, 422]) {
      const result = await submitAndGetResult(async () => jsonResponse(status, { title: "x", status }));
      expect(result.current.phase).toBe("blocked");
      expect(result.current.error?.status).toBe(status);
    }

    const unavailable = await submitAndGetResult(async () => jsonResponse(503, { title: "x", status: 503 }));
    expect(unavailable.current.phase).toBe("failed");
    expect(unavailable.current.error?.status).toBe(503);
  });

  it("surfaces failureReason on a DECLINED payment", async () => {
    const result = await submitAndGetResult(async () =>
      jsonResponse(201, {
        id: "pay-1",
        courseId: "course-1",
        status: "DECLINED",
        failureReason: "insufficient_funds",
      }),
    );
    expect(result.current.phase).toBe("declined");
    expect(result.current.payment?.failureReason).toBe("insufficient_funds");
  });
});
