import { useEffect, useState } from "react";
import { Link, useParams } from "react-router";
import * as endpoints from "../api/endpoints";
import type { CoursePublicView } from "../api/endpoints";
import EmptyState from "../components/EmptyState";
import EnrollPanel from "../components/EnrollPanel";
import Loading from "../components/Loading";
import { useAuth } from "../auth/useAuth";

type PageState = "loading" | "ready" | "not-found";

const LEVEL_BADGE: Record<string, string> = {
  BEGINNER: "bg-emerald-100 text-emerald-800",
  INTERMEDIATE: "bg-amber-100 text-amber-800",
  ADVANCED: "bg-red-100 text-red-800",
};

/** Public detail plus the ordered outline from CoursePublicView.lessons — no lesson content ever
 * appears here (FR-006, FR-008). */
export default function CourseDetailPage() {
  const { courseId } = useParams<{ courseId: string }>();
  const { status } = useAuth();
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
    return (
      <div className="max-w-4xl mx-auto px-4 py-8">
        <Loading label="Loading course…" />
      </div>
    );
  }

  if (pageState === "not-found" || !course) {
    return (
      <div className="max-w-4xl mx-auto px-4 py-8">
        <EmptyState message="This course is no longer available." />
      </div>
    );
  }

  const lessons = [...(course.lessons ?? [])].sort((a, b) => (a.position ?? 0) - (b.position ?? 0));
  const levelBadge = LEVEL_BADGE[course.level ?? ""] ?? "bg-slate-100 text-slate-600";

  return (
    <div className="min-h-screen bg-slate-50">
      {/* Course hero */}
      <div className="bg-gradient-to-br from-indigo-700 via-purple-700 to-indigo-800 text-white py-10 px-4">
        <div className="max-w-4xl mx-auto">
          <Link to="/" className="text-indigo-200 text-sm hover:text-white transition-colors">
            ← Back to catalog
          </Link>
          <h1 className="text-3xl font-bold mt-3 mb-3">{course.title}</h1>
          {course.level && (
            <span className={`text-xs font-semibold px-3 py-1 rounded-full ${levelBadge}`}>{course.level}</span>
          )}
        </div>
      </div>

      <div className="max-w-4xl mx-auto px-4 py-8">
        <div className="bg-white rounded-xl border border-slate-200 p-6 mb-6">
          <p className="text-slate-700 text-base leading-relaxed">{course.description}</p>
        </div>

        {lessons.length > 0 && (
          <div className="bg-white rounded-xl border border-slate-200 overflow-hidden mb-6">
            <div className="px-6 py-4 bg-slate-50 border-b border-slate-200">
              <h2 className="text-lg font-semibold text-slate-800">Course outline</h2>
              <p className="text-sm text-slate-500">{lessons.length} lessons</p>
            </div>
            <ol className="divide-y divide-slate-100">
              {lessons.map((lesson, index) => (
                <li key={index} className="flex items-center gap-4 px-6 py-3">
                  <span className="w-7 h-7 rounded-full bg-indigo-100 text-indigo-700 text-xs font-bold flex items-center justify-center flex-shrink-0">
                    {index + 1}
                  </span>
                  <span className="text-slate-700 text-sm flex-1">{lesson.title}</span>
                  {lesson.durationMinutes && (
                    <span className="text-xs text-slate-400 flex-shrink-0">{lesson.durationMinutes} min</span>
                  )}
                </li>
              ))}
            </ol>
          </div>
        )}

        {status === "authenticated" ? (
          <EnrollPanel course={course} />
        ) : status === "anonymous" ? (
          <div className="bg-indigo-50 border border-indigo-200 rounded-xl p-6 text-center">
            <p className="text-indigo-800 font-medium mb-3">Ready to start learning?</p>
            <Link
              to={`/login?returnTo=${encodeURIComponent(`/courses/${courseId}`)}`}
              className="inline-block bg-indigo-600 hover:bg-indigo-700 text-white font-semibold px-6 py-2.5 rounded-lg transition-colors"
            >
              Sign in to enroll
            </Link>
          </div>
        ) : null}
      </div>
    </div>
  );
}
