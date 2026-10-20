import { useEffect, useState } from "react";
import { useParams } from "react-router";
import * as endpoints from "../api/endpoints";
import type { CoursePublicView } from "../api/endpoints";
import EmptyState from "../components/EmptyState";
import Loading from "../components/Loading";

type PageState = "loading" | "ready" | "not-found";

/** Public detail plus the ordered outline from CoursePublicView.lessons — no lesson content ever
 * appears here (FR-006, FR-008). */
export default function CourseDetailPage() {
  const { courseId } = useParams<{ courseId: string }>();
  const [course, setCourse] = useState<CoursePublicView | null>(null);
  const [pageState, setPageState] = useState<PageState>("loading");

  useEffect(() => {
    if (!courseId) {
      return;
    }
    let cancelled = false;
    setPageState("loading");
    endpoints
      .getCourse(courseId)
      .then((view) => {
        if (!cancelled) {
          setCourse(view);
          setPageState("ready");
        }
      })
      .catch(() => {
        if (!cancelled) {
          setPageState("not-found");
        }
      });
    return () => {
      cancelled = true;
    };
  }, [courseId]);

  if (pageState === "loading") {
    return <Loading label="Loading course…" />;
  }

  if (pageState === "not-found" || !course) {
    return (
      <div className="page">
        <EmptyState message="This course is no longer available." />
      </div>
    );
  }

  const lessons = [...(course.lessons ?? [])].sort((a, b) => (a.position ?? 0) - (b.position ?? 0));

  return (
    <div className="page">
      <h1>{course.title}</h1>
      <p>{course.description}</p>
      <p>{course.level}</p>
      <h2>Outline</h2>
      <ol>
        {lessons.map((lesson, index) => (
          <li key={index}>
            {lesson.title} — {lesson.durationMinutes} min
          </li>
        ))}
      </ol>
    </div>
  );
}
