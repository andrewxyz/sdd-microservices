import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router";
import { describe, expect, it, vi } from "vitest";
import VerifyPage from "./VerifyPage";

function jsonResponse(status: number, body: unknown, headers?: Record<string, string>): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json", ...headers },
  });
}

function renderAt(path: string) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route path="/verify" element={<VerifyPage />} />
        <Route path="/verify/:code" element={<VerifyPage />} />
      </Routes>
    </MemoryRouter>,
  );
}

describe("VerifyPage", () => {
  it("trims and upper-cases the code before sending", async () => {
    const fetchMock = vi.fn(async () =>
      jsonResponse(200, { valid: true, courseTitle: "Intro to Testing", issuedAt: "2024-01-15T00:00:00Z" }),
    );
    vi.stubGlobal("fetch", fetchMock);

    renderAt("/verify");
    fireEvent.change(screen.getByRole("textbox"), { target: { value: " abc123 " } });
    fireEvent.click(screen.getByRole("button", { name: /verify/i }));

    await waitFor(() => expect(screen.getByText(/Intro to Testing/)).not.toBeNull());
    const sentUrl = String((fetchMock.mock.calls[0] as unknown[])[0]);
    expect(sentUrl).toContain("ABC123");
    expect(sentUrl).not.toContain(" abc123 ");
  });

  it("shows valid result with courseTitle and issuedAt on 200, nothing about the holder", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(async () =>
        jsonResponse(200, {
          valid: true,
          courseTitle: "Advanced TypeScript",
          issuedAt: "2024-06-01T00:00:00Z",
        }),
      ),
    );

    renderAt("/verify/SOMECODE");

    await waitFor(() => expect(screen.getByText(/Advanced TypeScript/)).not.toBeNull());
    expect(screen.getByText(/2024-06-01/)).not.toBeNull();
    expect(screen.queryByText(/email/i)).toBeNull();
    expect(screen.queryByText(/student/i)).toBeNull();
  });

  it("renders 404 as the 'not valid' result, not an error notice, no reference code", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(async () =>
        jsonResponse(404, {
          type: "about:blank",
          title: "Not Found",
          status: 404,
          correlationId: "some-id",
        }),
      ),
    );

    renderAt("/verify/FAKECODE");

    await waitFor(() => expect(screen.getByText(/not valid/i)).not.toBeNull());
    expect(screen.queryByText(/reference/i)).toBeNull();
    expect(document.querySelector(".error-notice")).toBeNull();
  });

  it("shows Retry-After wait on 429", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(async () =>
        jsonResponse(
          429,
          { type: "about:blank", title: "Too Many Requests", status: 429, detail: "Rate limit" },
          { "Retry-After": "60" },
        ),
      ),
    );

    renderAt("/verify/RATE");

    await waitFor(() => expect(screen.getByText(/60/)).not.toBeNull());
  });
});
