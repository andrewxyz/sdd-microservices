import { render, screen } from "@testing-library/react";
import { MemoryRouter, Route, Routes, useLocation } from "react-router";
import { describe, expect, it, vi } from "vitest";
import RequireRole from "./RequireRole";
import { AuthContext, type AuthContextValue, type AuthStatus } from "./useAuth";

function LoginProbe() {
  const location = useLocation();
  return <p>Login page {location.search}</p>;
}

function auth(status: AuthStatus, role?: "STUDENT" | "INSTRUCTOR" | "ADMINISTRATOR"): AuthContextValue {
  return {
    status,
    user: role ? { id: "u-1", email: "me@example.com", role, createdAt: "2026-01-01T00:00:00Z" } : null,
    login: vi.fn(),
    register: vi.fn(),
    logout: vi.fn(),
  };
}

function renderGuard(value: AuthContextValue) {
  return render(
    <MemoryRouter initialEntries={["/instructor?tab=1"]}>
      <AuthContext.Provider value={value}>
        <Routes>
          <Route
            path="/instructor"
            element={
              <RequireRole roles={["INSTRUCTOR", "ADMINISTRATOR"]}>
                <p>Dashboard content</p>
              </RequireRole>
            }
          />
          <Route path="/login" element={<LoginProbe />} />
          <Route path="/" element={<p>Catalog</p>} />
        </Routes>
      </AuthContext.Provider>
    </MemoryRouter>,
  );
}

describe("RequireRole", () => {
  it("renders its children for an instructor", () => {
    renderGuard(auth("authenticated", "INSTRUCTOR"));

    expect(screen.getByText("Dashboard content")).toBeTruthy();
  });

  it("renders its children for an administrator", () => {
    renderGuard(auth("authenticated", "ADMINISTRATOR"));

    expect(screen.getByText("Dashboard content")).toBeTruthy();
  });

  it("shows the not-allowed page, not the content, for a student", () => {
    renderGuard(auth("authenticated", "STUDENT"));

    expect(screen.queryByText("Dashboard content")).toBeNull();
    expect(screen.getByRole("heading", { name: /not allowed/i })).toBeTruthy();
  });

  it("sends a signed-out visitor to sign in and remembers where they were going", () => {
    renderGuard(auth("anonymous"));

    expect(screen.queryByText("Dashboard content")).toBeNull();
    expect(screen.getByText(/Login page/).textContent).toContain("returnTo=%2Finstructor%3Ftab%3D1");
  });

  it("shows a loading state, never the content or the login page, while the session is restored", () => {
    renderGuard(auth("restoring"));

    expect(screen.getByRole("status").textContent).toMatch(/checking your session/i);
    expect(screen.queryByText("Dashboard content")).toBeNull();
    expect(screen.queryByText(/Login page/)).toBeNull();
  });
});
