import { act, renderHook } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { useCheckout } from "./useCheckout";

function jsonResponse(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
}

function succeededPayment() {
  return jsonResponse(201, {
    id: "pay-1",
    courseId: "course-1",
    amountCents: 1000,
    currency: "USD",
    status: "SUCCEEDED",
  });
}

describe("useCheckout — post-payment polling", () => {
  beforeEach(() => {
    vi.useFakeTimers();
    vi.stubGlobal("crypto", { randomUUID: () => "key-1" });
  });

  afterEach(() => {
    vi.useRealTimers();
    vi.restoreAllMocks();
  });

  it("stops polling and reports enrolled on a 200", async () => {
    let progressCalls = 0;
    vi.stubGlobal(
      "fetch",
      vi.fn(async (input: RequestInfo | URL) => {
        const url = String(input);
        if (url === "/api/v1/checkouts") {
          return succeededPayment();
        }
        progressCalls += 1;
        return jsonResponse(200, { courseId: "course-1", completedLessons: 0, totalLessons: 3, percentComplete: 0 });
      }),
    );

    const { result } = renderHook(() => useCheckout("course-1"));
    act(() => {
      result.current.setChoice("sim-success");
    });
    await act(async () => {
      await result.current.submit();
    });

    expect(result.current.phase).toBe("enrolled");
    expect(progressCalls).toBe(1);
  });

  it("keeps waiting on 403 (not yet created) and resolves once the enrollment appears", async () => {
    let progressCalls = 0;
    vi.stubGlobal(
      "fetch",
      vi.fn(async (input: RequestInfo | URL) => {
        const url = String(input);
        if (url === "/api/v1/checkouts") {
          return succeededPayment();
        }
        progressCalls += 1;
        if (progressCalls < 3) {
          return jsonResponse(403, { title: "Forbidden", status: 403 });
        }
        return jsonResponse(200, { courseId: "course-1", completedLessons: 0, totalLessons: 3, percentComplete: 0 });
      }),
    );

    const { result } = renderHook(() => useCheckout("course-1"));
    act(() => {
      result.current.setChoice("sim-success");
    });
    const submitPromise = act(async () => {
      await result.current.submit();
    });

    // Let the first (403) poll happen, then advance one second at a time for the rest.
    await vi.advanceTimersByTimeAsync(0);
    await vi.advanceTimersByTimeAsync(1000);
    await vi.advanceTimersByTimeAsync(1000);
    await submitPromise;

    expect(result.current.phase).toBe("enrolled");
    expect(progressCalls).toBe(3);
  });

  it("keeps waiting on 503 (transient) the same way as 403", async () => {
    let progressCalls = 0;
    vi.stubGlobal(
      "fetch",
      vi.fn(async (input: RequestInfo | URL) => {
        const url = String(input);
        if (url === "/api/v1/checkouts") {
          return succeededPayment();
        }
        progressCalls += 1;
        if (progressCalls < 2) {
          return jsonResponse(503, { title: "Unavailable", status: 503 });
        }
        return jsonResponse(200, { courseId: "course-1", completedLessons: 0, totalLessons: 3, percentComplete: 0 });
      }),
    );

    const { result } = renderHook(() => useCheckout("course-1"));
    act(() => {
      result.current.setChoice("sim-success");
    });
    const submitPromise = act(async () => {
      await result.current.submit();
    });

    await vi.advanceTimersByTimeAsync(0);
    await vi.advanceTimersByTimeAsync(1000);
    await submitPromise;

    expect(result.current.phase).toBe("enrolled");
  });

  it("gives up after 15 seconds with pending and the check-back-shortly message", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(async (input: RequestInfo | URL) => {
        const url = String(input);
        if (url === "/api/v1/checkouts") {
          return succeededPayment();
        }
        return jsonResponse(403, { title: "Forbidden", status: 403 });
      }),
    );

    const { result } = renderHook(() => useCheckout("course-1"));
    act(() => {
      result.current.setChoice("sim-success");
    });
    const submitPromise = act(async () => {
      await result.current.submit();
    });

    await vi.advanceTimersByTimeAsync(0);
    await vi.advanceTimersByTimeAsync(16000);
    await submitPromise;

    expect(result.current.phase).toBe("pending");
  });
});
