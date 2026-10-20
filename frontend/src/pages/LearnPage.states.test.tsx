import { render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router";
import { beforeEach, describe, expect, it, vi } from "vitest";
import AuthProvider from "../auth/AuthProvider";
import { clear } from "../auth/session";
import LearnPage from "./LearnPage";

function jsonResponse(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
}

function renderAt(courseId: string) {
  return render(
    <MemoryRouter initialEntries={[`/learn/${courseId}`]}>
      <AuthProvider>
        <Routes>
          <Route path="/learn/:courseId" element={<LearnPage />} />
        </Routes>
      </AuthProvider>
    </MemoryRouter>,
  );
}

describe("LearnPage states", () => {
  beforeEach(() => {
    clear();
  });

  it("renders the enroll invitation on a 403", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(async (input: RequestInfo | URL) => {
        if (String(input).includes("/content")) {
          return jsonResponse(403, { title: "Forbidden", status: 403 });
        }
        return jsonResponse(403, { title: "Forbidden", status: 403 });
      }),
    );

    renderAt("course-1");

    await waitFor(() => expect(screen.getByText(/not enrolled/i)).not.toBeNull());
  });

  it("renders a retry explanation on a 503, never the not-enrolled wording", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(async (input: RequestInfo | URL) => {
        if (String(input).includes("/content")) {
          return jsonResponse(503, { title: "Unavailable", status: 503 });
        }
        return jsonResponse(503, { title: "Unavailable", status: 503 });
      }),
    );

    renderAt("course-1");

    await waitFor(() => expect(screen.getByRole("alert")).not.toBeNull());
    expect(screen.getByRole("button", { name: /retry/i })).not.toBeNull();
    expect(screen.queryByText(/not enrolled/i)).toBeNull();
  });

  it("renders not-found on a 404", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(async (input: RequestInfo | URL) => {
        if (String(input).includes("/content")) {
          return jsonResponse(404, { title: "Not Found", status: 404 });
        }
        return jsonResponse(404, { title: "Not Found", status: 404 });
      }),
    );

    renderAt("course-1");

    await waitFor(() => expect(screen.getByText(/no longer available/i)).not.toBeNull());
  });
});
