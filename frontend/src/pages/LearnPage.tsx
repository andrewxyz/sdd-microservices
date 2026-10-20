import { useCallback, useEffect, useState } from "react";
import { Link, useParams } from "react-router";
import * as endpoints from "../api/endpoints";
import type { CourseContentView, ProgressView } from "../api/endpoints";
import { ApiError } from "../api/problem";
import EmptyState from "../components/EmptyState";
import Loading from "../components/Loading";
import ProgressBar from "../components/ProgressBar";

type PageState = "loading" | "ready" | "not-enrolled" | "not-found" | "unavailable";

/**
 * Lesson list, the selected lesson's content as plain text (never dangerouslySetInnerHTML —
 * constitution XII, FR-027), the complete/undo toggle keyed by lesson id, and the progress bar.
 * 403 renders the enroll invitation; 503 renders a retry explanation and never the "not enrolled"
 * wording, because the two mean different things (research.md R9).
 */
export default function LearnPage() {
  const { courseId } = useParams<{ courseId: string }>();
  const [content, setContent] = useState<CourseContentView | null>(null);
  const [progress, setProgress] = useState<ProgressView | null>(null);
  const [selectedLessonId, setSelectedLessonId] = useState<string | null>(null);
  const [pageState, setPageState] = useState<PageState>("loading");
  const [completedLessonIds, setCompletedLessonIds] = useState<Set<string>>(new Set());
  const [toggleBusy, setToggleBusy] = useState(false);

  const load = useCallback(async () => {
    if (!courseId) {
      return;
    }
    setPageState("loading");
    try {
      const contentView = await endpoints.getCourseContent(courseId);
      const progressView = await endpoints.getEnrollmentProgress(courseId).catch(() => null);
      setContent(contentView);
      setProgress(progressView);
      const lessons = [...(contentView.lessons ?? [])].sort((a, b) => (a.position ?? 0) - (b.position ?? 0));
      setSelectedLessonId(lessons[0]?.id ?? null);
      setPageState("ready");
    } catch (err) {
      if (err instanceof ApiError && err.status === 403) {
        setPageState("not-enrolled");
      } else if (err instanceof ApiError && err.status === 404) {
        setPageState("not-found");
      } else {
        setPageState("unavailable");
      }
    }
  }, [courseId]);

  useEffect(() => {
    void load();
  }, [load]);

  async function toggleComplete(lessonId: string) {
    if (!courseId) {
      return;
    }
    setToggleBusy(true);
    try {
      const alreadyComplete = completedLessonIds.has(lessonId);
      const result = alreadyComplete
        ? await endpoints.unmarkLessonComplete(courseId, lessonId)
        : await endpoints.markLessonComplete(courseId, lessonId);
      // The response carries the resulting progress directly — no refetch needed (research.md R9).
      setProgress(result);
      setCompletedLessonIds((prev) => {
        const next = new Set(prev);
        if (alreadyComplete) {
          next.delete(lessonId);
        } else {
          next.add(lessonId);
        }
        return next;
      });
    } finally {
      setToggleBusy(false);
    }
  }

  if (pageState === "loading") {
    return <Loading label="Loading course…" />;
  }

  if (pageState === "not-enrolled") {
    return (
      <div className="page">
        <EmptyState
          message="You are not enrolled in this course."
          action={<Link to={`/courses/${courseId}`}>View the course</Link>}
        />
      </div>
    );
  }

  if (pageState === "not-found") {
    return (
      <div className="page">
        <EmptyState message="This course is no longer available." />
      </div>
    );
  }

  if (pageState === "unavailable") {
    return (
      <div className="page">
        <p role="alert">We could not confirm your enrollment right now. Please try again.</p>
        <button type="button" onClick={() => void load()}>
          Retry
        </button>
      </div>
    );
  }

  if (!content) {
    return null;
  }

  const lessons = [...(content.lessons ?? [])].sort((a, b) => (a.position ?? 0) - (b.position ?? 0));
  const selectedLesson = lessons.find((lesson) => lesson.id === selectedLessonId) ?? lessons[0];

  return (
    <div className="page learn-page">
      <h1>{content.title}</h1>
      {progress && <ProgressBar progress={progress} />}
      <div className="learn-page__layout">
        <ol className="learn-page__lessons">
          {lessons.map((lesson) => (
            <li key={lesson.id}>
              <button
                type="button"
                onClick={() => setSelectedLessonId(lesson.id ?? null)}
                aria-current={lesson.id === selectedLesson?.id}
              >
                {lesson.id && completedLessonIds.has(lesson.id) ? "✓ " : ""}
                {lesson.title}
              </button>
            </li>
          ))}
        </ol>
        {selectedLesson && (
          <div className="learn-page__content">
            <h2>{selectedLesson.title}</h2>
            <p className="lesson-content">{selectedLesson.content}</p>
            <button
              type="button"
              onClick={() => selectedLesson.id && void toggleComplete(selectedLesson.id)}
              disabled={toggleBusy || !selectedLesson.id}
            >
              {selectedLesson.id && completedLessonIds.has(selectedLesson.id) ? "Mark incomplete" : "Mark complete"}
            </button>
          </div>
        )}
      </div>
    </div>
  );
}
