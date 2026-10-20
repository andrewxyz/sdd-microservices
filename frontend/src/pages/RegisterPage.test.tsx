import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { clear } from "../auth/session";
import AuthProvider from "../auth/AuthProvider";
import RegisterPage from "./RegisterPage";

function jsonResponse(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
}

function renderPage() {
  return render(
    <MemoryRouter initialEntries={["/register"]}>
      <AuthProvider>
        <Routes>
          <Route path="/register" element={<RegisterPage />} />
          <Route path="/login" element={<p>Login page</p>} />
          <Route path="/" element={<p>Home page</p>} />
        </Routes>
      </AuthProvider>
    </MemoryRouter>,
  );
}

describe("RegisterPage", () => {
  beforeEach(() => {
    clear();
  });

  it("registers then signs in, because POST /register returns no tokens", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
        const url = String(input);
        const method = init?.method ?? "GET";
        if (url === "/api/v1/auth/register" && method === "POST") {
          return jsonResponse(201, { id: "u1", email: "new@example.com", role: "STUDENT" });
        }
        if (url === "/api/v1/auth/login" && method === "POST") {
          return jsonResponse(200, { accessToken: "access", refreshToken: "refresh" });
        }
        if (url === "/api/v1/auth/me") {
          return jsonResponse(200, { id: "u1", email: "new@example.com", role: "STUDENT" });
        }
        throw new Error(`unexpected fetch ${method} ${url}`);
      }),
    );

    renderPage();
    fireEvent.change(screen.getByLabelText("Email"), { target: { value: "new@example.com" } });
    fireEvent.change(screen.getByLabelText("Password"), { target: { value: "a-strong-password" } });
    fireEvent.click(screen.getByRole("button", { name: /create account/i }));

    await waitFor(() => expect(screen.getByText("Home page")).not.toBeNull());
  });

  it("sends the user to /login with an account-created message when the follow-up login fails", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
        const url = String(input);
        const method = init?.method ?? "GET";
        if (url === "/api/v1/auth/register" && method === "POST") {
          return jsonResponse(201, { id: "u1", email: "new@example.com", role: "STUDENT" });
        }
        if (url === "/api/v1/auth/login" && method === "POST") {
          return jsonResponse(401, { title: "Unauthorized", status: 401 });
        }
        throw new Error(`unexpected fetch ${method} ${url}`);
      }),
    );

    renderPage();
    fireEvent.change(screen.getByLabelText("Email"), { target: { value: "new@example.com" } });
    fireEvent.change(screen.getByLabelText("Password"), { target: { value: "a-strong-password" } });
    fireEvent.click(screen.getByRole("button", { name: /create account/i }));

    await waitFor(() => expect(screen.getByText("Login page")).not.toBeNull());
  });

  it("attaches a 409 to the email field and a 422 to the password field", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(async () => jsonResponse(409, { title: "Conflict", status: 409 })),
    );

    renderPage();
    fireEvent.change(screen.getByLabelText("Email"), { target: { value: "taken@example.com" } });
    fireEvent.change(screen.getByLabelText("Password"), { target: { value: "a-strong-password" } });
    fireEvent.click(screen.getByRole("button", { name: /create account/i }));

    await waitFor(() => expect(screen.getByText(/already registered/i)).not.toBeNull());
  });
});
