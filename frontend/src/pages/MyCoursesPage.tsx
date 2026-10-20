import { useEffect, useState } from "react";
import { Link } from "react-router";
import * as endpoints from "../api/endpoints";
import type { EnrollmentView, ProgressView } from "../api/endpoints";
import EmptyState from "../components/EmptyState";
import Loading from "../components/Loading";
import ProgressBar from "../components/ProgressBar";

interface CourseCardData {
  enrollment: EnrollmentView;
  title: string | null;
  progress: ProgressView | null;
}

/**
 * Assembled from three endpoints because no aggregate exists (research.md R12): the enrollment
 * list, then per enrollment the public course title and the progress, both fetched in parallel so
 * a slow course never blocks the rest.
 */
export default function MyCoursesPage() {
  const [cards, setCards] = useState<CourseCardData[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    endpoints
      .listEnrollments()
      .then((page) => {
        if (cancelled) {
          return;
        }
        const initial = page.items.map((enrollment) => ({ enrollment, title: null, progress: null }));
        setCards(initial);
        setLoading(false);

        initial.forEach((card, index) => {
          const courseId = card.enrollment.courseId;
          if (!courseId) {
            return;
          }
          endpoints
            .getCourse(courseId)
            .then((course) => {
              if (!cancelled) {
                setCards((prev) => prev.map((c, i) => (i === index ? { ...c, title: course.title ?? "" } : c)));
              }
            })
            .catch(() => {
              /* the card simply keeps showing "Loading…" for its title */
            });
          endpoints
            .getEnrollmentProgress(courseId)
            .then((progress) => {
              if (!cancelled) {
                setCards((prev) => prev.map((c, i) => (i === index ? { ...c, progress } : c)));
              }
            })
            .catch(() => {
              /* the card simply shows no progress bar */
            });
        });
      })
      .catch(() => {
        if (!cancelled) {
          setLoading(false);
        }
      });
    return () => {
      cancelled = true;
    };
  }, []);

  if (loading) {
    return (
      <div className="max-w-6xl mx-auto px-4 py-8">
        <Loading label="Loading your courses…" />
      </div>
    );
  }

  if (cards.length === 0) {
    return (
      <div className="max-w-6xl mx-auto px-4 py-8">
        <EmptyState
          message="You haven't enrolled in any course yet."
          action={
            <Link
              to="/"
              className="inline-block bg-indigo-600 hover:bg-indigo-700 text-white font-semibold px-5 py-2 rounded-lg text-sm transition-colors"
            >
              Browse the catalog
            </Link>
          }
        />
      </div>
    );
  }

  return (
    <div className="min-h-screen bg-slate-50">
      <div className="bg-gradient-to-br from-indigo-700 via-purple-700 to-indigo-800 text-white py-10 px-4">
        <div className="max-w-6xl mx-auto">
          <h1 className="text-3xl font-bold">My courses</h1>
          <p className="text-indigo-200 mt-1">{cards.length} enrolled course{cards.length !== 1 ? "s" : ""}</p>
        </div>
      </div>

      <div className="max-w-6xl mx-auto px-4 py-8">
        <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-6">
          {cards.map((card) => (
            <article
              key={card.enrollment.id}
              className="bg-white border border-slate-200 rounded-xl p-5 hover:shadow-lg hover:-translate-y-0.5 transition-all duration-200 flex flex-col gap-4"
            >
              <h3 className="text-base font-semibold leading-snug">
                {card.title ? (
                  <Link
                    to={`/learn/${card.enrollment.courseId}`}
                    className="text-slate-800 hover:text-indigo-600 transition-colors"
                  >
                    {card.title}
                  </Link>
                ) : (
                  <span className="text-slate-400 animate-pulse">Loading…</span>
                )}
              </h3>
              {card.progress ? (
                <ProgressBar progress={card.progress} />
              ) : (
                <Loading label="Loading progress…" />
              )}
              {card.title && (
                <Link
                  to={`/learn/${card.enrollment.courseId}`}
                  className="inline-flex items-center justify-center gap-1 bg-indigo-600 hover:bg-indigo-700 text-white text-sm font-medium px-4 py-2 rounded-lg transition-colors mt-auto"
                >
                  Continue learning →
                </Link>
              )}
            </article>
          ))}
        </div>
      </div>
    </div>
  );
}
