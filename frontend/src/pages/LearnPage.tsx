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
    return (
      <div className="max-w-6xl mx-auto px-4 py-8">
        <Loading label="Loading course…" />
      </div>
    );
  }

  if (pageState === "not-enrolled") {
    return (
      <div className="max-w-6xl mx-auto px-4 py-8">
        <EmptyState
          message="You are not enrolled in this course."
          action={
            <Link
              to={`/courses/${courseId}`}
              className="inline-block bg-brand hover:bg-brand-strong shadow-card active:shadow-inset text-on-brand font-semibold px-5 py-2 rounded-xl text-sm transition-colors"
            >
              View the course
            </Link>
          }
        />
      </div>
    );
  }

  if (pageState === "not-found") {
    return (
      <div className="max-w-6xl mx-auto px-4 py-8">
        <EmptyState message="This course is no longer available." />
      </div>
    );
  }

  if (pageState === "unavailable") {
    return (
      <div className="max-w-6xl mx-auto px-4 py-8 text-center">
        <p role="alert" className="text-ink-muted mb-4">
          We could not confirm your enrollment right now. Please try again.
        </p>
        <button
          type="button"
          onClick={() => void load()}
          className="bg-brand hover:bg-brand-strong shadow-card active:shadow-inset text-on-brand font-semibold px-5 py-2 rounded-xl transition-colors cursor-pointer"
        >
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
    <div className="min-h-screen bg-canvas">
      {/* Course header */}
      <div className="bg-hero text-on-hero py-8 px-4">
        <div className="max-w-6xl mx-auto">
          <h1 className="text-2xl font-bold mb-3">{content.title}</h1>
          {progress && (
            <div className="max-w-md">
              <ProgressBar progress={progress} />
            </div>
          )}
        </div>
      </div>

      <div className="max-w-6xl mx-auto px-4 py-6 flex flex-col sm:flex-row gap-6">
        {/* Lesson sidebar */}
        <aside className="sm:w-64 flex-shrink-0">
          <div className="bg-surface rounded-2xl shadow-card overflow-hidden sticky top-20">
            <div className="px-4 py-3 bg-surface-muted border-b border-line">
              <h2 className="text-xs font-semibold text-ink-muted uppercase tracking-wide">Lessons</h2>
            </div>
            <ol className="list-none p-0 m-0 max-h-[60vh] overflow-y-auto">
              {lessons.map((lesson, index) => {
                const isSelected = lesson.id === selectedLesson?.id;
                const isComplete = lesson.id ? completedLessonIds.has(lesson.id) : false;
                return (
                  <li key={lesson.id} className="border-b border-line last:border-0">
                    <button
                      type="button"
                      onClick={() => setSelectedLessonId(lesson.id ?? null)}
                      aria-current={isSelected ? "true" : undefined}
                      className={`w-full text-left px-4 py-3 text-sm transition-colors flex items-center gap-2 cursor-pointer ${
                        isSelected
                          ? "bg-brand-soft text-brand-ink font-medium border-l-4 border-l-brand"
                          : "hover:bg-surface-muted text-ink-body"
                      }`}
                    >
                      <span
                        className={`w-5 h-5 rounded-full text-xs flex items-center justify-center flex-shrink-0 font-bold ${
                          isComplete
                            ? "bg-success-solid text-on-brand"
                            : isSelected
                              ? "bg-brand-soft-strong text-brand-ink"
                              : "bg-surface-sunken text-ink-muted"
                        }`}
                      >
                        {isComplete ? "✓" : index + 1}
                      </span>
                      <span className="truncate">{lesson.title}</span>
                    </button>
                  </li>
                );
              })}
            </ol>
          </div>
        </aside>

        {/* Lesson content */}
        {selectedLesson && (
          <main className="flex-1 min-w-0">
            <div className="bg-surface rounded-2xl shadow-card p-6">
              <h2 className="text-xl font-bold text-ink mb-4">{selectedLesson.title}</h2>
              <div className="prose prose-slate max-w-none">
                <p className="text-ink-body leading-relaxed whitespace-pre-wrap">{selectedLesson.content}</p>
              </div>
              <button
                type="button"
                onClick={() => selectedLesson.id && void toggleComplete(selectedLesson.id)}
                disabled={toggleBusy || !selectedLesson.id}
                className={`mt-6 px-5 py-2.5 rounded-xl text-sm font-semibold transition-colors cursor-pointer disabled:opacity-60 disabled:cursor-not-allowed ${
                  selectedLesson.id && completedLessonIds.has(selectedLesson.id)
                    ? "bg-surface-muted hover:bg-surface-sunken text-ink-body"
                    : "bg-success-solid hover:bg-success-solid-strong text-on-brand"
                }`}
              >
                {selectedLesson.id && completedLessonIds.has(selectedLesson.id)
                  ? "✓ Mark incomplete"
                  : "Mark complete"}
              </button>
            </div>
          </main>
        )}
      </div>
    </div>
  );
}
