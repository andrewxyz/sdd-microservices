import { useEffect, useState } from "react";
import { Link, useParams } from "react-router";
import * as endpoints from "../api/endpoints";
import type { CoursePublicView, CourseRatingSummary, ReviewView } from "../api/endpoints";
import EmptyState from "../components/EmptyState";
import ErrorNotice from "../components/ErrorNotice";
import { ApiError } from "../api/problem";
import EnrollPanel from "../components/EnrollPanel";
import Loading from "../components/Loading";
import ReviewForm from "../components/ReviewForm";
import ReviewList from "../components/ReviewList";
import StarRating from "../components/StarRating";
import { useAuth } from "../auth/useAuth";

type PageState = "loading" | "ready" | "not-found" | "error";

const LEVEL_BADGE: Record<string, string> = {
  BEGINNER: "bg-emerald-100 text-emerald-800",
  INTERMEDIATE: "bg-amber-100 text-amber-800",
  ADVANCED: "bg-red-100 text-red-800",
};

/** Public detail plus the ordered outline from CoursePublicView.lessons — no lesson content ever
 * appears here (FR-006, FR-008). */
export default function CourseDetailPage() {
  const { courseId } = useParams<{ courseId: string }>();
  const { status, user } = useAuth();
  const [course, setCourse] = useState<CoursePublicView | null>(null);
  const [pageState, setPageState] = useState<PageState>("loading");
  const [summary, setSummary] = useState<CourseRatingSummary | null>(null);
  const [reviews, setReviews] = useState<ReviewView[]>([]);
  const [reviewPage, setReviewPage] = useState(0);
  const [totalReviews, setTotalReviews] = useState(0);
  const [reviewsFailed, setReviewsFailed] = useState(false);
  const [reloadKey, setReloadKey] = useState(0);
  const [courseKey, setCourseKey] = useState(0);
  const [loadError, setLoadError] = useState<ApiError | null>(null);
  const [enrolledTick, setEnrolledTick] = useState(0);
  const [alreadyEnrolled, setAlreadyEnrolled] = useState(false);

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
      .catch((err: unknown) => {
        if (cancelled) {
          return;
        }
        // Only a real 404 means the course is gone; a timeout or 5xx is temporary and can be retried.
        if (err instanceof ApiError && err.status === 404) {
          setPageState("not-found");
        } else {
          setLoadError(err instanceof ApiError ? err : null);
          setPageState("error");
        }
      });
    return () => {
      cancelled = true;
    };
  }, [courseId, courseKey]);

  // A returning student must not be offered "Enroll" for a course they already own. The progress endpoint
  // answers 200 for an enrolled caller and 403 otherwise; any other failure just leaves the normal panel.
  useEffect(() => {
    setAlreadyEnrolled(false);
    if (!courseId || status !== "authenticated") {
      return;
    }
    let cancelled = false;
    endpoints
      .getEnrollmentProgress(courseId)
      .then(() => {
        if (!cancelled) {
          setAlreadyEnrolled(true);
        }
      })
      .catch(() => undefined);
    return () => {
      cancelled = true;
    };
  }, [courseId, status]);

  // Reviews are secondary: a failure here leaves the rest of the page intact. Any change (submit, edit,
  // delete) bumps reloadKey, which refetches the summary and the first page.
  useEffect(() => {
    if (!courseId) {
      return;
    }
    let cancelled = false;
    setReviewsFailed(false);
    Promise.all([endpoints.getReviewSummary(courseId), endpoints.listReviews(courseId, 0)])
      .then(([summaryView, firstPage]) => {
        if (!cancelled) {
          setSummary(summaryView);
          setReviews(firstPage.items);
          setReviewPage(0);
          setTotalReviews(firstPage.totalItems);
        }
      })
      .catch(() => {
        if (!cancelled) {
          setReviewsFailed(true);
        }
      });
    return () => {
      cancelled = true;
    };
  }, [courseId, reloadKey]);

  function showMoreReviews() {
    if (!courseId) {
      return;
    }
    const next = reviewPage + 1;
    endpoints
      .listReviews(courseId, next)
      .then((more) => {
        setReviews((current) => [...current, ...more.items]);
        setReviewPage(next);
        setTotalReviews(more.totalItems);
      })
      .catch(() => setReviewsFailed(true));
  }

  if (pageState === "loading") {
    return (
      <div className="max-w-4xl mx-auto px-4 py-8">
        <Loading label="Loading course…" />
      </div>
    );
  }

  if (pageState === "error") {
    return (
      <div className="max-w-4xl mx-auto px-4 py-8">
        {loadError ? (
          <ErrorNotice error={loadError} />
        ) : (
          <EmptyState message="This course could not be loaded right now." />
        )}
        <button
          type="button"
          onClick={() => setCourseKey((k) => k + 1)}
          className="mt-4 bg-indigo-600 hover:bg-indigo-700 text-white px-5 py-2 rounded-lg text-sm font-medium cursor-pointer"
        >
          Try again
        </button>
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
  const ownReview = user ? reviews.find((r) => r.userId === user.id) : undefined;

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

        <div className="bg-white rounded-xl border border-slate-200 p-6 mb-6">
          <h2 className="text-lg font-semibold text-slate-800 mb-2">Reviews</h2>
          {reviewsFailed ? (
            <p className="text-sm text-slate-500">Reviews are unavailable right now.</p>
          ) : (
            <>
              {summary && <StarRating average={summary.averageRating} count={summary.reviewCount ?? 0} />}
              <div className="my-4">
                <ReviewList
                  reviews={reviews}
                  hasMore={reviews.length < totalReviews}
                  onShowMore={showMoreReviews}
                />
              </div>
              <h3 className="text-base font-semibold text-slate-800 mb-2">
                {ownReview ? "Your review" : "Write a review"}
              </h3>
              <ReviewForm
                key={ownReview?.id ?? "new"}
                courseId={course.id ?? courseId ?? ""}
                existing={ownReview}
                onChanged={() => setReloadKey((k) => k + 1)}
                clearErrorKey={enrolledTick}
              />
            </>
          )}
        </div>

        {status === "authenticated" ? (
          <EnrollPanel course={course} alreadyEnrolled={alreadyEnrolled} onEnrolled={() => setEnrolledTick((k) => k + 1)} />
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
