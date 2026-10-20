import { render, screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { clear, getAccessToken, getRefreshToken, setTokens } from "./session";
import { useAuth } from "./useAuth";
import AuthProvider from "./AuthProvider";

function jsonResponse(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
}

function Probe() {
  const { status, user } = useAuth();
  return (
    <div>
      <span data-testid="status">{status}</span>
      <span data-testid="email">{user?.email ?? ""}</span>
    </div>
  );
}

describe("AuthProvider restore-on-load", () => {
  beforeEach(() => {
    clear();
  });

  it("goes straight to anonymous when there is no refresh token", async () => {
    render(
      <AuthProvider>
        <Probe />
      </AuthProvider>,
    );

    await waitFor(() => expect(screen.getByTestId("status").textContent).toBe("anonymous"));
  });

  it("becomes authenticated with the /me profile when the refresh token is valid", async () => {
    setTokens("stale", "refresh-1");
    vi.stubGlobal(
      "fetch",
      vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
        const url = String(input);
        if (url === "/api/v1/auth/refresh") {
          return jsonResponse(200, { accessToken: "fresh", refreshToken: "refresh-2" });
        }
        if (url === "/api/v1/auth/me") {
          return jsonResponse(200, { id: "u1", email: "student@example.com", role: "STUDENT" });
        }
        throw new Error(`unexpected fetch ${String(init?.method)} ${url}`);
      }),
    );

    render(
      <AuthProvider>
        <Probe />
      </AuthProvider>,
    );

    await waitFor(() => expect(screen.getByTestId("status").textContent).toBe("authenticated"));
    expect(screen.getByTestId("email").textContent).toBe("student@example.com");
    expect(getAccessToken()).toBe("fresh");
  });

  it("clears storage and becomes anonymous when the refresh fails", async () => {
    setTokens("stale", "spent-refresh");
    vi.stubGlobal("fetch", vi.fn(async () => jsonResponse(401, { title: "Unauthorized", status: 401 })));

    render(
      <AuthProvider>
        <Probe />
      </AuthProvider>,
    );

    await waitFor(() => expect(screen.getByTestId("status").textContent).toBe("anonymous"));
    expect(getAccessToken()).toBeNull();
    expect(getRefreshToken()).toBeNull();
  });
});
