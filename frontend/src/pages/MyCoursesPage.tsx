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
    return <Loading label="Loading your courses…" />;
  }

  if (cards.length === 0) {
    return (
      <div className="page">
        <EmptyState
          message="You haven't enrolled in any course yet."
          action={<Link to="/">Browse the catalog</Link>}
        />
      </div>
    );
  }

  return (
    <div className="page">
      <h1>My courses</h1>
      <div className="course-grid">
        {cards.map((card) => (
          <article key={card.enrollment.id} className="course-card">
            <h3>
              {card.title ? <Link to={`/learn/${card.enrollment.courseId}`}>{card.title}</Link> : "Loading…"}
            </h3>
            {card.progress ? <ProgressBar progress={card.progress} /> : <Loading label="Loading progress…" />}
          </article>
        ))}
      </div>
    </div>
  );
}
