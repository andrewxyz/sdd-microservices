import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router";
import { describe, expect, it, vi } from "vitest";
import { AuthContext, type AuthContextValue } from "../auth/useAuth";
import InstructorOverviewPage from "./InstructorOverviewPage";

function jsonResponse(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
}

const MINE = [
  { id: "course-a", title: "Alpha course", status: "PUBLISHED" },
  { id: "course-b", title: "Bravo draft", status: "DRAFT" },
  { id: "course-c", title: "Charlie archived", status: "ARCHIVED" },
];

const GENERATED_AT = "2026-03-15T12:34:56Z";

const SUMMARY = {
  scope: "OWN",
  totals: { enrollments: 6, revenueCents: 7996, completions: 3, certificates: 1 },
  truncated: false,
  generatedAt: GENERATED_AT,
};

const LIST = {
  items: [
    { courseId: "course-a", totals: { enrollments: 4, revenueCents: 7996, completions: 1, certificates: 1 } },
    { courseId: "course-c", totals: { enrollments: 2, revenueCents: 0, completions: 2, certificates: 0 } },
  ],
  page: 0,
  size: 2,
  totalItems: 2,
  truncated: false,
  generatedAt: GENERATED_AT,
};

type Handler = (url: string) => Response | undefined;

function stubApi(extra: Handler = () => undefined) {
  vi.stubGlobal(
    "fetch",
    vi.fn(async (input: RequestInfo | URL) => {
      const url = String(input);
      const custom = extra(url);
      if (custom) {
        return custom;
      }
      if (url === "/api/v1/my/courses") {
        return jsonResponse(200, MINE);
      }
      if (url === "/api/v1/instructor/stats/summary") {
        return jsonResponse(200, SUMMARY);
      }
      if (url.startsWith("/api/v1/instructor/stats/courses")) {
        return jsonResponse(200, LIST);
      }
      return jsonResponse(404, { title: "Not Found", status: 404 });
    }),
  );
}

function renderPage(role: "INSTRUCTOR" | "ADMINISTRATOR" = "INSTRUCTOR") {
  const auth: AuthContextValue = {
    status: "authenticated",
    user: { id: "u-1", email: "teacher@example.com", role, createdAt: "2026-01-01T00:00:00Z" },
    login: vi.fn(),
    register: vi.fn(),
    logout: vi.fn(),
  };
  return render(
    <MemoryRouter initialEntries={["/instructor"]}>
      <AuthContext.Provider value={auth}>
        <Routes>
          <Route path="/instructor" element={<InstructorOverviewPage />} />
          <Route path="/instructor/courses/:courseId" element={<p>Course detail page</p>} />
        </Routes>
      </AuthContext.Provider>
    </MemoryRouter>,
  );
}

function tile(label: string): string {
  const totals = screen.getByLabelText("Totals");
  return within(totals).getByText(label).nextElementSibling?.textContent ?? "";
}

function rowTitles(): string[] {
  const table = screen.getByRole("table");
  return within(table)
    .getAllByRole("row")
    .slice(1)
    .map((row) => within(row).getAllByRole("cell")[0]!.textContent ?? "");
}

describe("InstructorOverviewPage (instructor)", () => {
  it("shows a loading state first", () => {
    stubApi();
    renderPage();

    expect(screen.getByRole("status")).toBeTruthy();
  });

  it("shows totals that equal the sum of the rows, in dollars", async () => {
    stubApi();
    renderPage();

    await waitFor(() => expect(tile("Students enrolled")).toBe("6"));
    expect(tile("Revenue")).toBe("$79.96");
    expect(tile("Completions")).toBe("3");
    expect(tile("Certificates issued")).toBe("1");
  });

  it("lists every owned course, with zeros and 0% for a draft that has no activity", async () => {
    stubApi();
    renderPage();

    await screen.findByText("Bravo draft");
    const draftRow = screen.getByText("Bravo draft").closest("tr")!;
    const cells = within(draftRow).getAllByRole("cell").map((c) => c.textContent);
    expect(cells).toEqual(["Bravo draft", "Draft", "0", "$0.00", "0", "0", "0%"]);
    const alphaRow = screen.getByText("Alpha course").closest("tr")!;
    expect(within(alphaRow).getAllByRole("cell").map((c) => c.textContent)).toEqual([
      "Alpha course",
      "Published",
      "4",
      "$79.96",
      "1",
      "1",
      "25%",
    ]);
  });

  it("lets keyboard users scroll the wide table and gives the sort buttons room", async () => {
    stubApi();
    renderPage();

    const region = await screen.findByRole("region", { name: /your courses table/i });
    expect(region.getAttribute("tabindex")).toBe("0");
    expect(screen.getByRole("button", { name: /enrollments/i }).className).toContain("py-2");
  });

  it("sorts by a column when its header button is pressed and marks aria-sort", async () => {
    stubApi();
    renderPage();
    await screen.findByText("Bravo draft");

    fireEvent.click(screen.getByRole("button", { name: /Revenue/ }));
    expect(rowTitles()[0]).toBe("Alpha course");
    expect(screen.getByRole("columnheader", { name: /Revenue/ }).getAttribute("aria-sort")).toBe("descending");

    fireEvent.click(screen.getByRole("button", { name: /Completion rate/ }));
    expect(rowTitles()[0]).toBe("Charlie archived");

    fireEvent.click(screen.getByRole("button", { name: /Students enrolled|Enrollments/ }));
    expect(rowTitles()[0]).toBe("Alpha course");
  });

  it("links each row to the course detail page", async () => {
    stubApi();
    renderPage();

    fireEvent.click(await screen.findByRole("link", { name: "Alpha course" }));

    expect(await screen.findByText("Course detail page")).toBeTruthy();
  });

  it("shows an empty state when the instructor owns no courses", async () => {
    stubApi((url) => (url === "/api/v1/my/courses" ? jsonResponse(200, []) : undefined));
    renderPage();

    expect(await screen.findByText(/you have no courses yet/i)).toBeTruthy();
    expect(screen.queryByRole("table")).toBeNull();
  });

  it("shows the error with its reference code when a request fails", async () => {
    stubApi((url) =>
      url.startsWith("/api/v1/instructor/stats/")
        ? jsonResponse(500, { title: "Internal Server Error", status: 500, correlationId: "corr-1234-abcd" })
        : undefined,
    );
    renderPage();

    const alert = await screen.findByRole("alert");
    expect(alert.textContent).toContain("corr-1234-abcd");
    expect(screen.queryByRole("table")).toBeNull();
  });

  it("warns that totals cover only the newest 200 courses when the response is truncated", async () => {
    stubApi((url) =>
      url === "/api/v1/instructor/stats/summary" ? jsonResponse(200, { ...SUMMARY, truncated: true }) : undefined,
    );
    renderPage();

    expect(await screen.findByText(/200 newest courses/i)).toBeTruthy();
  });

  it("does not show the truncation notice normally", async () => {
    stubApi();
    renderPage();
    await screen.findByText("Bravo draft");

    expect(screen.queryByText(/200 newest courses/i)).toBeNull();
  });

  it("renders server text as plain text, never as markup", async () => {
    stubApi((url) =>
      url === "/api/v1/my/courses"
        ? jsonResponse(200, [{ id: "course-x", title: "<img src=x onerror=alert(1)>", status: "PUBLISHED" }])
        : undefined,
    );
    const { container } = renderPage();

    await screen.findByText("<img src=x onerror=alert(1)>");
    expect(container.querySelector("img")).toBeNull();
  });
});

describe("InstructorOverviewPage (administrator)", () => {
  const PLATFORM = {
    scope: "PLATFORM",
    totals: { enrollments: 40, revenueCents: 123456, completions: 12, certificates: 9 },
    truncated: false,
    generatedAt: GENERATED_AT,
  };
  const TOP = {
    items: [
      { courseId: "11111111-aaaa-bbbb-cccc-000000000001", totals: { enrollments: 30, revenueCents: 100000, completions: 9, certificates: 7 } },
      { courseId: "22222222-aaaa-bbbb-cccc-000000000002", totals: { enrollments: 10, revenueCents: 23456, completions: 3, certificates: 2 } },
    ],
    page: 0,
    size: 10,
    totalItems: 2,
    truncated: false,
    generatedAt: GENERATED_AT,
  };

  function stubAdmin() {
    stubApi((url) => {
      if (url === "/api/v1/instructor/stats/summary") {
        return jsonResponse(200, PLATFORM);
      }
      if (url.startsWith("/api/v1/instructor/stats/courses")) {
        return jsonResponse(200, TOP);
      }
      if (url === "/api/v1/courses/11111111-aaaa-bbbb-cccc-000000000001") {
        return jsonResponse(200, { id: "11111111-aaaa-bbbb-cccc-000000000001", title: "Best seller" });
      }
      return undefined;
    });
  }

  it("shows platform-wide totals", async () => {
    stubAdmin();
    renderPage("ADMINISTRATOR");

    await waitFor(() => expect(tile("Students enrolled")).toBe("40"));
    expect(tile("Revenue")).toBe("$1,234.56");
    expect(screen.getByRole("heading", { name: /platform dashboard/i })).toBeTruthy();
  });

  it("lists the top courses by revenue with titles from the public course endpoint", async () => {
    stubAdmin();
    renderPage("ADMINISTRATOR");

    expect(await screen.findByRole("link", { name: "Best seller" })).toBeTruthy();
    expect(screen.queryByText("Your courses")).toBeNull();
  });

  it("falls back to a short id when a course title cannot be resolved (404)", async () => {
    stubAdmin();
    renderPage("ADMINISTRATOR");

    expect(await screen.findByRole("link", { name: "22222222" })).toBeTruthy();
  });

  it("does not ask for the administrator's own course list", async () => {
    stubAdmin();
    renderPage("ADMINISTRATOR");
    await screen.findByRole("link", { name: "Best seller" });

    const calls = (fetch as unknown as { mock: { calls: unknown[][] } }).mock.calls.map((c) => String(c[0]));
    expect(calls).not.toContain("/api/v1/my/courses");
  });
});

describe("InstructorOverviewPage freshness", () => {
  it("shows when the figures were last updated, in UTC", async () => {
    stubApi();
    renderPage();

    expect(await screen.findByText("Last updated 12:34:56 UTC")).toBeTruthy();
  });

  it("refetches when Refresh is pressed and shows the new figures and time", async () => {
    let round = 0;
    stubApi((url) => {
      if (url === "/api/v1/instructor/stats/summary") {
        round += 1;
        return round === 1
          ? undefined
          : jsonResponse(200, {
              ...SUMMARY,
              totals: { ...SUMMARY.totals, enrollments: 7 },
              generatedAt: "2026-03-15T12:40:00Z",
            });
      }
      return undefined;
    });
    renderPage();
    await waitFor(() => expect(tile("Students enrolled")).toBe("6"));

    fireEvent.click(screen.getByRole("button", { name: "Refresh" }));

    await waitFor(() => expect(tile("Students enrolled")).toBe("7"));
    expect(screen.getByText("Last updated 12:40:00 UTC")).toBeTruthy();
  });

  it("disables Refresh while a refresh is in flight and keeps the old figures on screen", async () => {
    let release: (response: Response) => void = () => undefined;
    let summaryCalls = 0;
    stubApi((url) => {
      if (url === "/api/v1/instructor/stats/summary") {
        summaryCalls += 1;
        if (summaryCalls === 2) {
          return new Promise<Response>((resolve) => {
            release = resolve;
          }) as unknown as Response;
        }
      }
      return undefined;
    });
    renderPage();
    await waitFor(() => expect(tile("Students enrolled")).toBe("6"));

    fireEvent.click(screen.getByRole("button", { name: "Refresh" }));

    await waitFor(() => expect((screen.getByRole("button", { name: /Refresh|Refreshing/ }) as HTMLButtonElement).disabled).toBe(true));
    expect(tile("Students enrolled")).toBe("6");
    release(jsonResponse(200, SUMMARY));
    await waitFor(() => expect((screen.getByRole("button", { name: "Refresh" }) as HTMLButtonElement).disabled).toBe(false));
  });

  it("does not poll on its own", async () => {
    stubApi();
    renderPage();
    await screen.findByText("Bravo draft");
    const fetchMock = fetch as unknown as { mock: { calls: unknown[][] } };
    const callsAfterLoad = fetchMock.mock.calls.length;

    await new Promise((resolve) => setTimeout(resolve, 300));

    expect(fetchMock.mock.calls.length).toBe(callsAfterLoad);
  });

  it("shows no numbers when a refresh fails with 503", async () => {
    let summaryCalls = 0;
    stubApi((url) => {
      if (url === "/api/v1/instructor/stats/summary") {
        summaryCalls += 1;
        if (summaryCalls > 1) {
          return jsonResponse(503, { title: "Service Unavailable", status: 503, correlationId: "corr-5030-wxyz" });
        }
      }
      return undefined;
    });
    renderPage();
    await waitFor(() => expect(tile("Students enrolled")).toBe("6"));

    fireEvent.click(screen.getByRole("button", { name: "Refresh" }));

    const alert = await screen.findByRole("alert");
    expect(alert.textContent).toMatch(/temporarily unavailable/i);
    expect(alert.textContent).toContain("corr-5030-wxyz");
    expect(screen.queryByLabelText("Totals")).toBeNull();
    expect(screen.queryByRole("table")).toBeNull();
  });

  it("recovers on Refresh once the service is back", async () => {
    let summaryCalls = 0;
    stubApi((url) => {
      if (url === "/api/v1/instructor/stats/summary") {
        summaryCalls += 1;
        if (summaryCalls === 1) {
          return jsonResponse(503, { title: "Service Unavailable", status: 503, correlationId: "corr-5030-down" });
        }
      }
      return undefined;
    });
    renderPage();
    await screen.findByRole("alert");

    fireEvent.click(screen.getByRole("button", { name: "Refresh" }));

    await waitFor(() => expect(tile("Students enrolled")).toBe("6"));
    expect(screen.queryByRole("alert")).toBeNull();
  });
});
