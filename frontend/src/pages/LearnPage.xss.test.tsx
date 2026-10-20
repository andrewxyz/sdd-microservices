import { render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router";
import { beforeEach, describe, expect, it, vi } from "vitest";
import AuthProvider from "../auth/AuthProvider";
import { clear } from "../auth/session";
import LearnPage from "./LearnPage";

function jsonResponse(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
}

describe("LearnPage — lesson content is never interpreted as markup", () => {
  beforeEach(() => {
    clear();
  });

  it("renders a <script> tag in lesson content as visible text, nothing executes", async () => {
    const malicious = "<script>alert(1)</script>";
    vi.stubGlobal(
      "fetch",
      vi.fn(async (input: RequestInfo | URL) => {
        const url = String(input);
        if (url.includes("/content")) {
          return jsonResponse(200, {
            courseId: "course-1",
            title: "Course",
            lessons: [{ id: "lesson-1", title: "Lesson 1", position: 0, durationMinutes: 5, content: malicious }],
          });
        }
        if (url.includes("/progress")) {
          return jsonResponse(200, { courseId: "course-1", completedLessons: 0, totalLessons: 1, percentComplete: 0 });
        }
        return jsonResponse(404, { title: "Not Found", status: 404 });
      }),
    );

    render(
      <MemoryRouter initialEntries={["/learn/course-1"]}>
        <AuthProvider>
          <Routes>
            <Route path="/learn/:courseId" element={<LearnPage />} />
          </Routes>
        </AuthProvider>
      </MemoryRouter>,
    );

    await waitFor(() => expect(screen.getByText(malicious)).not.toBeNull());
    expect(document.querySelector("script[data-injected]")).toBeNull();
    expect(document.body.innerHTML).not.toContain("<script>alert(1)</script>");
  });
});
