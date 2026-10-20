import { render, screen, waitFor, fireEvent } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router";
import { beforeEach, describe, expect, it, vi } from "vitest";
import CatalogPage from "./CatalogPage";

function jsonResponse(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
}

function coursesPage(items: unknown[] = []) {
  return jsonResponse(200, { items, page: 0, size: 20, totalItems: items.length });
}

function renderAt(path: string) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route path="/" element={<CatalogPage />} />
      </Routes>
    </MemoryRouter>,
  );
}

describe("CatalogPage — URL-driven state", () => {
  beforeEach(() => {
    vi.stubGlobal(
      "fetch",
      vi.fn(async (input: RequestInfo | URL) => {
        const url = String(input);
        if (url.startsWith("/api/v1/categories")) {
          return jsonResponse(200, []);
        }
        return coursesPage([]);
      }),
    );
  });

  it("applies the defaults sort=newest, page=0, size=20 when the URL carries no query", async () => {
    renderAt("/");

    await waitFor(() => {
      const fetchMock = fetch as unknown as ReturnType<typeof vi.fn>;
      const call = fetchMock.mock.calls.find((call: unknown[]) => String(call[0]).startsWith("/api/v1/courses"));
      expect(call).toBeDefined();
      const url = new URL(String(call![0]), "http://localhost");
      expect(url.searchParams.get("sort")).toBe("newest");
      expect(url.searchParams.get("page")).toBe("0");
      expect(url.searchParams.get("size")).toBe("20");
    });
  });

  it("passes q, category, level, free and sort straight through to GET /api/v1/courses", async () => {
    renderAt("/?q=react&category=cat-1&level=BEGINNER&free=true&sort=title&page=2&size=10");

    await waitFor(() => {
      const fetchMock = fetch as unknown as ReturnType<typeof vi.fn>;
      const call = fetchMock.mock.calls.find((call: unknown[]) => String(call[0]).startsWith("/api/v1/courses"));
      expect(call).toBeDefined();
      const url = new URL(String(call![0]), "http://localhost");
      expect(url.searchParams.get("q")).toBe("react");
      expect(url.searchParams.get("category")).toBe("cat-1");
      expect(url.searchParams.get("level")).toBe("BEGINNER");
      expect(url.searchParams.get("free")).toBe("true");
      expect(url.searchParams.get("sort")).toBe("title");
      expect(url.searchParams.get("page")).toBe("2");
      expect(url.searchParams.get("size")).toBe("10");
    });
  });

  it("falls back to newest for a sort value outside the four accepted ones", async () => {
    renderAt("/?sort=bogus");

    await waitFor(() => {
      const fetchMock = fetch as unknown as ReturnType<typeof vi.fn>;
      const call = fetchMock.mock.calls.find((call: unknown[]) => String(call[0]).startsWith("/api/v1/courses"));
      const url = new URL(String(call![0]), "http://localhost");
      expect(url.searchParams.get("sort")).toBe("newest");
    });
  });
});

describe("CatalogPage — empty results", () => {
  it("renders the friendly empty state with a clear-filters action, not an error", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(async (input: RequestInfo | URL) => {
        const url = String(input);
        if (url.startsWith("/api/v1/categories")) {
          return jsonResponse(200, []);
        }
        return coursesPage([]);
      }),
    );

    renderAt("/?q=nothing-matches");

    await waitFor(() => expect(screen.getByText(/no courses match/i)).not.toBeNull());
    expect(screen.queryByRole("alert")).toBeNull();
    const clearButton = screen.getByRole("button", { name: /clear filters/i });
    fireEvent.click(clearButton);
  });
});
