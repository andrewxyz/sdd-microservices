import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { clear } from "../auth/session";
import AuthProvider from "../auth/AuthProvider";
import LoginPage from "./LoginPage";

function jsonResponse(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
}

function renderPage(initialPath = "/login") {
  return render(
    <MemoryRouter initialEntries={[initialPath]}>
      <AuthProvider>
        <Routes>
          <Route path="/login" element={<LoginPage />} />
          <Route path="/" element={<p>Home page</p>} />
          <Route path="/courses/abc" element={<p>Course page</p>} />
        </Routes>
      </AuthProvider>
    </MemoryRouter>,
  );
}

function submit() {
  fireEvent.change(screen.getByLabelText("Email"), { target: { value: "student@example.com" } });
  fireEvent.change(screen.getByLabelText("Password"), { target: { value: "password123" } });
  fireEvent.click(screen.getByRole("button", { name: /sign in/i }));
}

describe("LoginPage", () => {
  beforeEach(() => {
    clear();
  });

  it("renders a 401 as a form-level message, never blamed on one field", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => jsonResponse(401, { title: "Unauthorized", status: 401 })));

    renderPage();
    submit();

    await waitFor(() => expect(screen.getByRole("alert").textContent).toMatch(/wrong email or password/i));
  });

  it("shows the Retry-After wait on a 429", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(
        async () =>
          new Response(JSON.stringify({ title: "Too Many Requests", status: 429 }), {
            status: 429,
            headers: { "Retry-After": "20" },
          }),
      ),
    );

    renderPage();
    submit();

    await waitFor(() => expect(screen.getByRole("alert").textContent).toMatch(/20 seconds/));
  });

  it("honours returnTo on a successful sign-in", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(async (input: RequestInfo | URL) => {
        const url = String(input);
        if (url === "/api/v1/auth/login") {
          return jsonResponse(200, { accessToken: "access", refreshToken: "refresh" });
        }
        if (url === "/api/v1/auth/me") {
          return jsonResponse(200, { id: "u1", email: "student@example.com", role: "STUDENT" });
        }
        throw new Error(`unexpected fetch ${url}`);
      }),
    );

    renderPage("/login?returnTo=%2Fcourses%2Fabc");
    submit();

    await waitFor(() => expect(screen.getByText("Course page")).not.toBeNull());
  });
});
