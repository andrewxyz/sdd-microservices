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

describe("CatalogPage — ratings", () => {
  const courses = [
    { id: "c-1", title: "Reviewed course", level: "BEGINNER", priceCents: 0 },
    { id: "c-2", title: "Fresh course", level: "BEGINNER", priceCents: 0 },
  ];

  function stubFetch(summaries: () => Response) {
    vi.stubGlobal(
      "fetch",
      vi.fn(async (input: RequestInfo | URL) => {
        const url = String(input);
        if (url.startsWith("/api/v1/categories")) {
          return jsonResponse(200, []);
        }
        if (url.startsWith("/api/v1/reviews/summaries")) {
          return summaries();
        }
        return coursesPage(courses);
      }),
    );
  }

  function summaryCalls(): string[] {
    const fetchMock = fetch as unknown as ReturnType<typeof vi.fn>;
    return fetchMock.mock.calls
      .map((call: unknown[]) => String(call[0]))
      .filter((url: string) => url.startsWith("/api/v1/reviews/summaries"));
  }

  it("shows each card's average and count, or 'No reviews yet', from one summaries request", async () => {
    stubFetch(() =>
      jsonResponse(200, {
        items: [
          { courseId: "c-1", averageRating: 4.5, reviewCount: 2 },
          { courseId: "c-2", averageRating: null, reviewCount: 0 },
        ],
      }),
    );

    renderAt("/");

    await waitFor(() => expect(screen.getByLabelText("Rated 4.5 out of 5 from 2 reviews")).not.toBeNull());
    expect(screen.getByText("No reviews yet")).not.toBeNull();
    expect(summaryCalls()).toEqual(["/api/v1/reviews/summaries?courseIds=c-1,c-2"]);
  });

  it("still shows the courses, without ratings or an error banner, when ratings fail to load", async () => {
    stubFetch(() => jsonResponse(503, { title: "Service Unavailable", status: 503 }));

    renderAt("/");

    await waitFor(() => expect(screen.getByText("Reviewed course")).not.toBeNull());
    await waitFor(() => expect(summaryCalls().length).toBe(1));
    expect(screen.queryByRole("alert")).toBeNull();
    expect(screen.queryByText("No reviews yet")).toBeNull();
  });

  it("asks for ratings in groups of at most 50 ids", async () => {
    const many = Array.from({ length: 51 }, (_, i) => ({ id: `c-${i}`, title: `Course ${i}`, priceCents: 0 }));
    vi.stubGlobal(
      "fetch",
      vi.fn(async (input: RequestInfo | URL) => {
        const url = String(input);
        if (url.startsWith("/api/v1/categories")) {
          return jsonResponse(200, []);
        }
        if (url.startsWith("/api/v1/reviews/summaries")) {
          return jsonResponse(200, { items: [] });
        }
        return coursesPage(many);
      }),
    );

    renderAt("/?size=100");

    await waitFor(() => expect(summaryCalls().length).toBe(2));
    const sizes = summaryCalls().map((url) => url.split("=")[1]!.split(",").length);
    expect(sizes).toEqual([50, 1]);
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
