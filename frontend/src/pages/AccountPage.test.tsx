import { render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter } from "react-router";
import { beforeEach, describe, expect, it, vi } from "vitest";
import AuthProvider from "../auth/AuthProvider";
import { clear } from "../auth/session";
import AccountPage from "./AccountPage";

function jsonResponse(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

function renderPage() {
  return render(
    <MemoryRouter>
      <AuthProvider>
        <AccountPage />
      </AuthProvider>
    </MemoryRouter>,
  );
}

describe("AccountPage", () => {
  beforeEach(() => {
    clear();
  });

  it("shows email and role from GET /auth/me", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(async (input: RequestInfo | URL) => {
        const url = String(input);
        if (url.includes("/auth/me")) {
          return jsonResponse(200, {
            id: "1",
            email: "student@test.com",
            role: "STUDENT",
            createdAt: "2024-01-01T00:00:00Z",
          });
        }
        if (url.includes("/payments")) {
          return jsonResponse(200, { items: [] });
        }
        if (url.includes("/certificates")) {
          return jsonResponse(200, { items: [] });
        }
        return jsonResponse(404, { title: "Not Found", status: 404 });
      }),
    );

    renderPage();

    await waitFor(() => expect(screen.getByText("student@test.com")).not.toBeNull());
    expect(screen.getByText(/STUDENT/)).not.toBeNull();
  });

  it("renders payments newest first", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(async (input: RequestInfo | URL) => {
        const url = String(input);
        if (url.includes("/auth/me")) {
          return jsonResponse(200, {
            id: "1",
            email: "student@test.com",
            role: "STUDENT",
            createdAt: "2024-01-01T00:00:00Z",
          });
        }
        if (url.includes("/payments")) {
          return jsonResponse(200, {
            items: [
              {
                id: "pay-1",
                courseId: "course-older",
                amountCents: 2000,
                currency: "USD",
                status: "SUCCEEDED",
                createdAt: "2024-01-01T00:00:00Z",
              },
              {
                id: "pay-2",
                courseId: "course-newer",
                amountCents: 4999,
                currency: "USD",
                status: "SUCCEEDED",
                createdAt: "2024-06-01T00:00:00Z",
              },
            ],
          });
        }
        if (url.includes("/certificates")) {
          return jsonResponse(200, { items: [] });
        }
        return jsonResponse(404, { title: "Not Found", status: 404 });
      }),
    );

    renderPage();

    await waitFor(() => expect(screen.getByText(/course-newer/)).not.toBeNull());
    expect(screen.getByText(/course-older/)).not.toBeNull();
    const html = document.body.innerHTML;
    expect(html.indexOf("course-newer")).toBeLessThan(html.indexOf("course-older"));
  });

  it("shows an empty state when payment history is empty", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(async (input: RequestInfo | URL) => {
        const url = String(input);
        if (url.includes("/auth/me")) {
          return jsonResponse(200, {
            id: "1",
            email: "student@test.com",
            role: "STUDENT",
            createdAt: "2024-01-01T00:00:00Z",
          });
        }
        if (url.includes("/payments")) {
          return jsonResponse(200, { items: [] });
        }
        if (url.includes("/certificates")) {
          return jsonResponse(200, { items: [] });
        }
        return jsonResponse(404, { title: "Not Found", status: 404 });
      }),
    );

    renderPage();

    await waitFor(() => expect(screen.getByText(/no payments yet/i)).not.toBeNull());
  });
});
