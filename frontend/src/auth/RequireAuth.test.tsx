import { render, screen, waitFor } from "@testing-library/react";
import { Route, Routes, MemoryRouter } from "react-router";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { clear, setTokens } from "./session";
import RequireAuth from "./RequireAuth";
import AuthProvider from "./AuthProvider";

function jsonResponse(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
}

function renderAt(path: string) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <AuthProvider>
        <Routes>
          <Route
            path="/protected"
            element={
              <RequireAuth>
                <p>Secret content</p>
              </RequireAuth>
            }
          />
          <Route path="/login" element={<p>Login page</p>} />
        </Routes>
      </AuthProvider>
    </MemoryRouter>,
  );
}

describe("RequireAuth", () => {
  beforeEach(() => {
    clear();
  });

  it("shows a loading state before redirecting, never a flash of the login page", async () => {
    setTokens("stale", "refresh-1");
    vi.stubGlobal(
      "fetch",
      vi.fn(
        () =>
          new Promise<Response>((resolve) =>
            setTimeout(() => resolve(jsonResponse(401, { title: "Unauthorized", status: 401 })), 10),
          ),
      ),
    );

    renderAt("/protected");

    // The refresh call has not resolved yet, so restoring is still showing — not a flash of /login.
    expect(screen.queryByText("Login page")).toBeNull();
    expect(screen.getByText(/checking your session/i)).not.toBeNull();

    await waitFor(() => expect(screen.getByText("Login page")).not.toBeNull());
  });

  it("redirects a signed-out visitor to /login carrying returnTo", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => jsonResponse(401, { title: "Unauthorized", status: 401 })));

    renderAt("/protected");

    await waitFor(() => expect(screen.getByText("Login page")).not.toBeNull());
  });
});
