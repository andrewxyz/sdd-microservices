import { act, renderHook, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { useCheckout } from "./useCheckout";

function jsonResponse(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
}

function paymentView(status: "SUCCEEDED" | "DECLINED" | "FAILED") {
  return jsonResponse(status === "SUCCEEDED" ? 201 : 201, {
    id: "pay-1",
    courseId: "course-1",
    amountCents: 1000,
    currency: "USD",
    status,
    failureReason: status === "DECLINED" ? "card_declined" : null,
  });
}

function keysUsed(fetchMock: ReturnType<typeof vi.fn>): string[] {
  return fetchMock.mock.calls
    .filter((call: unknown[]) => String(call[0]) === "/api/v1/checkouts")
    .map((call: unknown[]) => JSON.parse(String((call[1] as RequestInit | undefined)?.body)).idempotencyKey as string);
}

describe("useCheckout — idempotency key lifecycle", () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });

  it("generates the key via crypto.randomUUID() on the first submit", async () => {
    const fetchMock = vi.fn(async () => paymentView("SUCCEEDED"));
    vi.stubGlobal("fetch", fetchMock);
    vi.stubGlobal("crypto", { randomUUID: () => "generated-key-1" });

    const { result } = renderHook(() => useCheckout("course-1"));
    act(() => {
      result.current.setChoice("sim-success");
    });
    await act(async () => {
      await result.current.submit();
    });

    expect(keysUsed(fetchMock)).toEqual(["generated-key-1"]);
  });

  it("reuses the key for a retry after a network failure", async () => {
    let call = 0;
    const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
      if (String(input) === "/api/v1/checkouts") {
        call += 1;
        if (call === 1) {
          throw new TypeError("Failed to fetch");
        }
        return paymentView("SUCCEEDED");
      }
      return paymentView("SUCCEEDED");
    });
    vi.stubGlobal("fetch", fetchMock);
    vi.stubGlobal("crypto", { randomUUID: () => "stable-key" });

    const { result } = renderHook(() => useCheckout("course-1"));
    act(() => {
      result.current.setChoice("sim-success");
    });
    await act(async () => {
      await result.current.submit();
    });
    expect(result.current.phase).toBe("failed");

    await act(async () => {
      await result.current.submit();
    });

    expect(keysUsed(fetchMock)).toEqual(["stable-key", "stable-key"]);
  });

  it("regenerates the key when the payment choice changes", async () => {
    let nextKey = 0;
    const fetchMock = vi.fn(async () => paymentView("SUCCEEDED"));
    vi.stubGlobal("fetch", fetchMock);
    vi.stubGlobal("crypto", { randomUUID: () => `key-${++nextKey}` });

    const { result } = renderHook(() => useCheckout("course-1"));
    act(() => {
      result.current.setChoice("sim-success");
    });
    await act(async () => {
      await result.current.submit();
    });

    act(() => {
      result.current.setChoice("sim-decline");
    });
    await act(async () => {
      await result.current.submit();
    });

    expect(keysUsed(fetchMock)).toEqual(["key-1", "key-2"]);
  });

  it("regenerates the key when the user retries after a terminal FAILED", async () => {
    let nextKey = 0;
    const fetchMock = vi.fn(async () => paymentView("FAILED"));
    vi.stubGlobal("fetch", fetchMock);
    vi.stubGlobal("crypto", { randomUUID: () => `key-${++nextKey}` });

    const { result } = renderHook(() => useCheckout("course-1"));
    act(() => {
      result.current.setChoice("sim-unavailable");
    });
    await act(async () => {
      await result.current.submit();
    });
    await waitFor(() => expect(result.current.phase).toBe("failed"));

    await act(async () => {
      await result.current.submit();
    });

    expect(keysUsed(fetchMock)).toEqual(["key-1", "key-2"]);
  });
});
