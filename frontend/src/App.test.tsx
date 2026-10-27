import { render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter } from "react-router";
import { describe, expect, it, vi } from "vitest";
import App from "./App";
import { setTokens } from "./auth/session";

function jsonResponse(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
}

/** Answers by URL; the profile has the given role (a visitor never asks for it). */
function stubApi(role: "STUDENT" | "INSTRUCTOR" | "ADMINISTRATOR") {
  vi.stubGlobal(
    "fetch",
    vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      if (url === "/api/v1/auth/refresh") {
        return jsonResponse(200, { accessToken: "access-2", refreshToken: "refresh-2" });
      }
      if (url === "/api/v1/auth/me") {
        return jsonResponse(200, { id: "u-1", email: "me@example.com", role, createdAt: "2026-01-01T00:00:00Z" });
      }
      if (url.startsWith("/api/v1/courses")) {
        return jsonResponse(200, { items: [], page: 0, size: 12, totalItems: 0 });
      }
      if (url.startsWith("/api/v1/categories")) {
        return jsonResponse(200, []);
      }
      return jsonResponse(404, { title: "Not Found", status: 404 });
    }),
  );
}

/** A signed-in session: a refresh token survives the reload, so the app restores it. */
function stubSession(role: "STUDENT" | "INSTRUCTOR" | "ADMINISTRATOR") {
  setTokens("access-1", "refresh-1");
  stubApi(role);
}

function renderApp(path = "/") {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <App />
    </MemoryRouter>,
  );
}

describe("App navigation", () => {
  it("offers the Dashboard link to an instructor", async () => {
    stubSession("INSTRUCTOR");
    renderApp();

    expect(await screen.findByRole("link", { name: "Dashboard" })).toBeTruthy();
  });

  it("offers the Dashboard link to an administrator", async () => {
    stubSession("ADMINISTRATOR");
    renderApp();

    expect(await screen.findByRole("link", { name: "Dashboard" })).toBeTruthy();
  });

  it("does not offer the Dashboard link to a student", async () => {
    stubSession("STUDENT");
    renderApp();

    await screen.findByRole("link", { name: "My courses" });
    expect(screen.queryByRole("link", { name: "Dashboard" })).toBeNull();
  });

  it("does not offer the Dashboard link to a visitor who is not signed in", async () => {
    stubApi("STUDENT");
    renderApp();

    await screen.findByRole("link", { name: "Sign in" });
    expect(screen.queryByRole("link", { name: "Dashboard" })).toBeNull();
  });

  it("shows a student the not-allowed page at /instructor", async () => {
    stubSession("STUDENT");
    renderApp("/instructor");

    await waitFor(() => expect(screen.getByRole("heading", { name: /not allowed/i })).toBeTruthy());
  });
});
