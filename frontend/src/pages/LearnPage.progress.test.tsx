import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router";
import { beforeEach, describe, expect, it, vi } from "vitest";
import AuthProvider from "../auth/AuthProvider";
import { clear } from "../auth/session";
import LearnPage from "./LearnPage";

function jsonResponse(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
}

function renderPage() {
  return render(
    <MemoryRouter initialEntries={["/learn/course-1"]}>
      <AuthProvider>
        <Routes>
          <Route path="/learn/:courseId" element={<LearnPage />} />
        </Routes>
      </AuthProvider>
    </MemoryRouter>,
  );
}

describe("LearnPage — marking progress", () => {
  beforeEach(() => {
    clear();
  });

  it("updates the bar from the PUT response with no refetch", async () => {
    let progressFetches = 0;
    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      const method = init?.method ?? "GET";
      if (url.includes("/content")) {
        return jsonResponse(200, {
          courseId: "course-1",
          title: "Course",
          lessons: [{ id: "lesson-1", title: "Lesson 1", position: 0, durationMinutes: 5, content: "Hello" }],
        });
      }
      if (url.includes("/lessons/lesson-1/complete") && method === "PUT") {
        return jsonResponse(200, { courseId: "course-1", completedLessons: 1, totalLessons: 1, percentComplete: 100 });
      }
      if (url.includes("/lessons/lesson-1/complete") && method === "DELETE") {
        return jsonResponse(200, { courseId: "course-1", completedLessons: 0, totalLessons: 1, percentComplete: 0 });
      }
      if (url.includes("/progress")) {
        progressFetches += 1;
        return jsonResponse(200, { courseId: "course-1", completedLessons: 0, totalLessons: 1, percentComplete: 0 });
      }
      return jsonResponse(404, { title: "Not Found", status: 404 });
    });
    vi.stubGlobal("fetch", fetchMock);

    renderPage();
    await waitFor(() => expect(screen.getByRole("heading", { level: 2, name: "Lesson 1" })).not.toBeNull());
    expect(progressFetches).toBe(1);

    fireEvent.click(screen.getByRole("button", { name: /mark complete/i }));
    await waitFor(() => expect(screen.getByText(/100%/)).not.toBeNull());
    // No additional GET .../progress call was made to learn the new state.
    expect(progressFetches).toBe(1);

    fireEvent.click(screen.getByRole("button", { name: /mark incomplete/i }));
    await waitFor(() => expect(screen.getByText(/0%/)).not.toBeNull());
    expect(progressFetches).toBe(1);
  });
});
