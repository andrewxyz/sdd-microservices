import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router";
import { describe, expect, it, vi } from "vitest";
import { AuthContext, type AuthContextValue } from "../auth/useAuth";
import InstructorCoursePage from "./InstructorCoursePage";

function jsonResponse(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
}

function series(days: number, enrollmentsOnLastDay = 0) {
  return Array.from({ length: days }, (_, index) => ({
    day: `2026-03-${String(index + 1).padStart(2, "0")}`,
    enrollments: index === days - 1 ? enrollmentsOnLastDay : 0,
  }));
}

function detail(days: number, totals = { enrollments: 4, revenueCents: 7996, completions: 2, certificates: 1 }) {
  return {
    courseId: "course-a",
    days,
    totals,
    funnel: { enrolled: totals.enrollments, completed: totals.completions, certificateIssued: totals.certificates },
    series: series(days, totals.enrollments),
    generatedAt: "2026-03-15T12:34:56Z",
  };
}

type Handler = (url: string) => Response | undefined;

function stubApi(extra: Handler = () => undefined) {
  const fetchMock = vi.fn(async (input: RequestInfo | URL) => {
    const url = String(input);
    const custom = extra(url);
    if (custom) {
      return custom;
    }
    if (url === "/api/v1/my/courses") {
      return jsonResponse(200, [{ id: "course-a", title: "Alpha course", status: "PUBLISHED" }]);
    }
    const match = url.match(/^\/api\/v1\/instructor\/stats\/courses\/course-a\?days=(\d+)$/);
    if (match) {
      return jsonResponse(200, detail(Number(match[1])));
    }
    return jsonResponse(404, { title: "Not Found", status: 404 });
  });
  vi.stubGlobal("fetch", fetchMock);
  return fetchMock;
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
    <MemoryRouter initialEntries={["/instructor/courses/course-a"]}>
      <AuthContext.Provider value={auth}>
        <Routes>
          <Route path="/instructor/courses/:courseId" element={<InstructorCoursePage />} />
          <Route path="/instructor" element={<p>Overview page</p>} />
        </Routes>
      </AuthContext.Provider>
    </MemoryRouter>,
  );
}

function tile(label: string): string {
  return within(screen.getByLabelText("Totals")).getByText(label).nextElementSibling?.textContent ?? "";
}

describe("InstructorCoursePage", () => {
  it("shows a loading state first", () => {
    stubApi();
    renderPage();

    expect(screen.getByRole("status")).toBeTruthy();
  });

  it("shows the totals, the funnel and the daily chart for the default 30 days", async () => {
    const fetchMock = stubApi();
    const { container } = renderPage();

    await waitFor(() => expect(tile("Students enrolled")).toBe("4"));
    expect(tile("Revenue")).toBe("$79.96");
    expect(tile("Completions")).toBe("2");
    expect(tile("Certificates issued")).toBe("1");
    expect(screen.getByText("Certificate issued").closest("li")?.textContent).toContain("1");
    expect(container.querySelectorAll("svg rect")).toHaveLength(30);
    expect(fetchMock).toHaveBeenCalledWith("/api/v1/instructor/stats/courses/course-a?days=30", expect.anything());
    expect(screen.getByRole("button", { name: "30 days" }).getAttribute("aria-pressed")).toBe("true");
  });

  it("shows the course title when the instructor owns it", async () => {
    stubApi();
    renderPage();

    expect(await screen.findByRole("heading", { name: /Alpha course/ })).toBeTruthy();
  });

  it("refetches when another range is chosen and marks it pressed", async () => {
    const fetchMock = stubApi();
    const { container } = renderPage();
    await waitFor(() => expect(tile("Students enrolled")).toBe("4"));

    fireEvent.click(screen.getByRole("button", { name: "7 days" }));

    await waitFor(() => expect(container.querySelectorAll("svg rect")).toHaveLength(7));
    expect(fetchMock).toHaveBeenCalledWith("/api/v1/instructor/stats/courses/course-a?days=7", expect.anything());
    expect(screen.getByRole("button", { name: "7 days" }).getAttribute("aria-pressed")).toBe("true");
    expect(screen.getByRole("button", { name: "30 days" }).getAttribute("aria-pressed")).toBe("false");

    fireEvent.click(screen.getByRole("button", { name: "90 days" }));
    await waitFor(() => expect(container.querySelectorAll("svg rect")).toHaveLength(90));
  });

  it("explains an empty course instead of showing a blank chart", async () => {
    stubApi((url) =>
      url.includes("/stats/courses/course-a")
        ? jsonResponse(200, detail(30, { enrollments: 0, revenueCents: 0, completions: 0, certificates: 0 }))
        : undefined,
    );
    renderPage();

    expect(await screen.findByText(/no activity yet/i)).toBeTruthy();
    expect(tile("Revenue")).toBe("$0.00");
  });

  it("says the course was not found on a 404, with no figures", async () => {
    stubApi((url) =>
      url.includes("/stats/courses/course-a")
        ? jsonResponse(404, { title: "Not Found", status: 404, correlationId: "corr-4040-abcd" })
        : undefined,
    );
    renderPage();

    expect(await screen.findByText(/course not found/i)).toBeTruthy();
    expect(screen.queryByLabelText("Totals")).toBeNull();
  });

  it("says the caller is not allowed on a 403, with the reference code", async () => {
    stubApi((url) =>
      url.includes("/stats/courses/course-a")
        ? jsonResponse(403, { title: "Forbidden", status: 403, correlationId: "corr-4030-abcd" })
        : undefined,
    );
    renderPage();

    const alert = await screen.findByRole("alert");
    expect(alert.textContent).toMatch(/not allowed/i);
    expect(alert.textContent).toContain("corr-4030-abcd");
    expect(screen.queryByLabelText("Totals")).toBeNull();
  });

  it("says the data is temporarily unavailable on a 503, with the reference code and no numbers", async () => {
    stubApi((url) =>
      url.includes("/stats/courses/course-a")
        ? jsonResponse(503, { title: "Service Unavailable", status: 503, correlationId: "corr-5030-abcd" })
        : undefined,
    );
    renderPage();

    const alert = await screen.findByRole("alert");
    expect(alert.textContent).toMatch(/temporarily unavailable/i);
    expect(alert.textContent).toContain("corr-5030-abcd");
    expect(screen.queryByLabelText("Totals")).toBeNull();
    expect(document.querySelector("svg")).toBeNull();
  });

  it("links back to the overview", async () => {
    stubApi();
    renderPage();

    fireEvent.click(await screen.findByRole("link", { name: /back to dashboard/i }));

    expect(await screen.findByText("Overview page")).toBeTruthy();
  });
});

describe("InstructorCoursePage freshness", () => {
  it("shows the last updated time in UTC and refetches on Refresh", async () => {
    let calls = 0;
    const fetchMock = stubApi((url) => {
      if (url.includes("/stats/courses/course-a")) {
        calls += 1;
        return jsonResponse(200, { ...detail(30), generatedAt: calls === 1 ? "2026-03-15T12:34:56Z" : "2026-03-15T12:41:00Z" });
      }
      return undefined;
    });
    renderPage();

    expect(await screen.findByText("Last updated 12:34:56 UTC")).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: "Refresh" }));

    expect(await screen.findByText("Last updated 12:41:00 UTC")).toBeTruthy();
    expect(fetchMock).toHaveBeenCalledTimes(3); // my courses, the first load, the refresh
  });

  it("keeps the chart visible while a new range loads, then shows the new range", async () => {
    stubApi();
    const { container } = renderPage();
    await waitFor(() => expect(container.querySelectorAll("svg rect")).toHaveLength(30));

    fireEvent.click(screen.getByRole("button", { name: "90 days" }));

    expect(container.querySelectorAll("svg rect").length).toBeGreaterThan(0);
    await waitFor(() => expect(container.querySelectorAll("svg rect")).toHaveLength(90));
  });
});
