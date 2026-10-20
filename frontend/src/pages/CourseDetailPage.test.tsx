import { render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router";
import { beforeEach, describe, expect, it, vi } from "vitest";
import AuthProvider from "../auth/AuthProvider";
import { clear } from "../auth/session";
import CourseDetailPage from "./CourseDetailPage";

function jsonResponse(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
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

describe("CourseDetailPage", () => {
  beforeEach(() => {
    clear();
  });

  it("renders details and the lesson outline ordered by position, with no content field anywhere", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(async () =>
        jsonResponse(200, {
          id: "course-1",
          title: "Intro to Testing",
          description: "Learn the basics.",
          level: "BEGINNER",
          priceCents: 0,
          lessons: [
            { title: "Second lesson", position: 1, durationMinutes: 10 },
            { title: "First lesson", position: 0, durationMinutes: 5 },
          ],
        }),
      ),
    );

    renderAt("course-1");

    await waitFor(() => expect(screen.getByText("Intro to Testing")).not.toBeNull());
    const items = screen.getAllByRole("listitem");
    expect(items.at(0)?.textContent).toContain("First lesson");
    expect(items.at(1)?.textContent).toContain("Second lesson");
    expect(document.body.textContent).not.toContain("\"content\"");
  });

  it("renders 'no longer available' on a 404", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => jsonResponse(404, { title: "Not Found", status: 404 })));

    renderAt("missing-course");

    await waitFor(() => expect(screen.getByText(/no longer available/i)).not.toBeNull());
  });
});
