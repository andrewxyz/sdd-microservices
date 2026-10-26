import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router";
import { beforeEach, describe, expect, it, vi } from "vitest";
import AuthProvider from "../auth/AuthProvider";
import { clear } from "../auth/session";
import { AuthContext, type AuthContextValue } from "../auth/useAuth";
import CourseDetailPage from "./CourseDetailPage";

function jsonResponse(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
}

const COURSE = {
  id: "course-1",
  title: "Intro to Testing",
  description: "Learn the basics.",
  level: "BEGINNER",
  priceCents: 0,
  lessons: [
    { title: "Second lesson", position: 1, durationMinutes: 10 },
    { title: "First lesson", position: 0, durationMinutes: 5 },
  ],
};

type Handler = (url: string, init?: RequestInit) => Response | undefined;

/** One fetch mock that answers by URL; `extra` handlers run first, then the defaults below. */
function stubApi(extra: Handler = () => undefined) {
  vi.stubGlobal(
    "fetch",
    vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      const custom = extra(url, init);
      if (custom) {
        return custom;
      }
      if (url === "/api/v1/courses/course-1") {
        return jsonResponse(200, COURSE);
      }
      if (url === "/api/v1/reviews/courses/course-1/summary") {
        return jsonResponse(200, { courseId: "course-1", averageRating: 4, reviewCount: 2 });
      }
      if (url.startsWith("/api/v1/reviews/courses/course-1?")) {
        return jsonResponse(200, { items: [], page: 0, size: 20, totalItems: 0 });
      }
      return jsonResponse(404, { title: "Not Found", status: 404 });
    }),
  );
}

function renderAt(courseId: string) {
  return render(
    <MemoryRouter initialEntries={[`/courses/${courseId}`]}>
      <AuthProvider>
        <Routes>
          <Route path="/courses/:courseId" element={<CourseDetailPage />} />
          <Route path="/login" element={<p>Login page</p>} />
        </Routes>
      </AuthProvider>
    </MemoryRouter>,
  );
}

function renderSignedIn() {
  const auth: AuthContextValue = {
    status: "authenticated",
    user: { id: "u-1", email: "me@example.com", role: "STUDENT", createdAt: "2026-01-01T00:00:00Z" },
    login: vi.fn(),
    register: vi.fn(),
    logout: vi.fn(),
  };
  return render(
    <MemoryRouter initialEntries={["/courses/course-1"]}>
      <AuthContext.Provider value={auth}>
        <Routes>
          <Route path="/courses/:courseId" element={<CourseDetailPage />} />
        </Routes>
      </AuthContext.Provider>
    </MemoryRouter>,
  );
}

function review(id: string, userId: string, rating: number, comment: string) {
  return { id, courseId: "course-1", userId, rating, comment, createdAt: "2026-10-01T10:00:00Z" };
}

describe("CourseDetailPage", () => {
  beforeEach(() => {
    clear();
  });

  it("renders details and the lesson outline ordered by position, with no content field anywhere", async () => {
    stubApi();

    renderAt("course-1");

    await waitFor(() => expect(screen.getByText("Intro to Testing")).not.toBeNull());
    const items = screen.getAllByRole("listitem");
    expect(items.at(0)?.textContent).toContain("First lesson");
    expect(items.at(1)?.textContent).toContain("Second lesson");
    expect(document.body.textContent).not.toContain("\"content\"");
  });

  it("renders 'no longer available' on a 404", async () => {
    stubApi();

    renderAt("missing-course");

    await waitFor(() => expect(screen.getByText(/no longer available/i)).not.toBeNull());
  });

  it("shows a retryable error, not 'no longer available', on a gateway timeout", async () => {
    let attempts = 0;
    stubApi((url) => {
      if (url === "/api/v1/courses/course-1") {
        attempts += 1;
        if (attempts === 1) {
          return jsonResponse(504, { title: "Gateway Timeout", status: 504, correlationId: "corr-504" });
        }
      }
      return undefined;
    });

    renderAt("course-1");

    await waitFor(() => expect(screen.getByRole("alert").textContent).toContain("corr-504"));
    expect(screen.queryByText(/no longer available/i)).toBeNull();

    fireEvent.click(screen.getByRole("button", { name: /try again/i }));

    await waitFor(() => expect(screen.getByText("Intro to Testing")).not.toBeNull());
  });
});

describe("CourseDetailPage — enrollment state", () => {
  beforeEach(() => {
    clear();
  });

  it("shows 'You're enrolled' instead of the enroll button when the student is already enrolled", async () => {
    stubApi((url) =>
      url === "/api/v1/enrollments/course-1/progress"
        ? jsonResponse(200, { courseId: "course-1", completedLessons: 0, totalLessons: 2, percent: 0 })
        : undefined,
    );

    renderSignedIn();

    await waitFor(() => expect(screen.getByText(/you're enrolled/i)).not.toBeNull());
    expect(screen.queryByRole("button", { name: /enroll for free|submit test payment/i })).toBeNull();
    expect(screen.getByRole("link", { name: /start learning/i })).not.toBeNull();
  });

  it("still offers enrollment when the student is not enrolled (progress answers 403)", async () => {
    stubApi((url) =>
      url === "/api/v1/enrollments/course-1/progress"
        ? jsonResponse(403, { title: "Forbidden", status: 403, correlationId: "c-403" })
        : undefined,
    );

    renderSignedIn();

    await waitFor(() => expect(screen.getByText("Intro to Testing")).not.toBeNull());
    expect(screen.queryByText(/you're enrolled/i)).toBeNull();
  });
});

describe("CourseDetailPage — reviews", () => {
  beforeEach(() => {
    clear();
  });

  it("shows the summary and the reviews to a signed-out visitor, with a sign-in prompt and no form", async () => {
    stubApi((url) =>
      url.startsWith("/api/v1/reviews/courses/course-1?")
        ? jsonResponse(200, {
            items: [review("r-1", "u-2", 5, "<b>Brilliant</b>"), review("r-2", "u-3", 3, "Okay")],
            page: 0,
            size: 20,
            totalItems: 2,
          })
        : undefined,
    );

    renderAt("course-1");

    await waitFor(() => expect(screen.getByLabelText("Rated 4.0 out of 5 from 2 reviews")).not.toBeNull());
    expect(screen.getByText("<b>Brilliant</b>")).not.toBeNull();
    expect(screen.getByText("Okay")).not.toBeNull();
    await waitFor(() => expect(screen.getByRole("link", { name: "Sign in" })).not.toBeNull());
    expect(screen.queryByLabelText("Rating (1-5)")).toBeNull();
  });

  it("loads the next page when asked to show more", async () => {
    const full = Array.from({ length: 20 }, (_, i) => review(`r-${i}`, `u-${i + 10}`, 4, `Comment ${i}`));
    stubApi((url) => {
      if (url === "/api/v1/reviews/courses/course-1?page=0") {
        return jsonResponse(200, { items: full, page: 0, size: 20, totalItems: 21 });
      }
      if (url === "/api/v1/reviews/courses/course-1?page=1") {
        return jsonResponse(200, { items: [review("r-last", "u-99", 2, "Last one")], page: 1, size: 20, totalItems: 21 });
      }
      return undefined;
    });

    renderAt("course-1");

    fireEvent.click(await screen.findByRole("button", { name: /show more/i }));

    await waitFor(() => expect(screen.getByText("Last one")).not.toBeNull());
    expect(screen.getByText("Comment 0")).not.toBeNull();
    expect(screen.queryByRole("button", { name: /show more/i })).toBeNull();
  });

  it("still shows the course when the reviews cannot be loaded", async () => {
    stubApi((url) => (url.startsWith("/api/v1/reviews/") ? jsonResponse(503, { title: "Unavailable", status: 503 }) : undefined));

    renderAt("course-1");

    await waitFor(() => expect(screen.getByText("Intro to Testing")).not.toBeNull());
    await waitFor(() => expect(screen.getByText(/reviews are unavailable/i)).not.toBeNull());
  });

  it("pre-fills the signed-in student's own review and refreshes after deleting it", async () => {
    let deleted = false;
    stubApi((url, init) => {
      if (url === "/api/v1/reviews/r-own" && init?.method === "DELETE") {
        deleted = true;
        return new Response(null, { status: 204 });
      }
      if (url.startsWith("/api/v1/reviews/courses/course-1?")) {
        return jsonResponse(200, {
          items: deleted ? [] : [review("r-own", "u-1", 2, "Mine")],
          page: 0,
          size: 20,
          totalItems: deleted ? 0 : 1,
        });
      }
      return undefined;
    });

    vi.spyOn(window, "confirm").mockReturnValue(true);
    renderSignedIn();

    await waitFor(() => expect((screen.getByLabelText("Rating (1-5)") as HTMLInputElement).value).toBe("2"));
    expect((screen.getByLabelText("Comment (optional)") as HTMLTextAreaElement).value).toBe("Mine");

    fireEvent.click(screen.getByRole("button", { name: /delete my review/i }));

    await waitFor(() => expect(screen.getByRole("button", { name: /submit review/i })).not.toBeNull());
    expect((screen.getByLabelText("Rating (1-5)") as HTMLInputElement).value).toBe("5");
  });

  it("submits a new review and shows it after the refresh", async () => {
    let created = false;
    stubApi((url, init) => {
      if (url === "/api/v1/reviews/courses/course-1" && init?.method === "POST") {
        created = true;
        return jsonResponse(201, review("r-new", "u-1", 4, "Nice"));
      }
      if (url.startsWith("/api/v1/reviews/courses/course-1?")) {
        const items = created ? [review("r-new", "u-1", 4, "Nice")] : [];
        return jsonResponse(200, { items, page: 0, size: 20, totalItems: items.length });
      }
      return undefined;
    });

    renderSignedIn();

    await screen.findByRole("button", { name: /submit review/i });
    fireEvent.change(screen.getByLabelText("Rating (1-5)"), { target: { value: "4" } });
    fireEvent.change(screen.getByLabelText("Comment (optional)"), { target: { value: "Nice" } });
    fireEvent.click(screen.getByRole("button", { name: /submit review/i }));

    await waitFor(() => expect(screen.getByRole("button", { name: /save changes/i })).not.toBeNull());
    expect(screen.getAllByText("Nice").length).toBeGreaterThan(0);
  });
});
