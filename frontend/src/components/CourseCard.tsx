import { Link } from "react-router";
import type { CoursePublicView, CourseRatingSummary } from "../api/endpoints";
import StarRating from "./StarRating";

const LEVEL_BADGE: Record<string, string> = {
  BEGINNER: "bg-success-soft-strong text-success-ink",
  INTERMEDIATE: "bg-warning-soft-strong text-warning-ink",
  ADVANCED: "bg-danger-soft-strong text-danger-ink",
};

function formatPrice(course: CoursePublicView): string {
  if (!course.priceCents) {
    return "Free";
  }
  return new Intl.NumberFormat("en-US", {
    style: "currency",
    currency: course.currency ?? "USD",
  }).format(course.priceCents / 100);
}

/** Title, level, category name and either the price or "Free" (FR-001). */
export default function CourseCard({
  course,
  categoryName,
  summary,
}: {
  course: CoursePublicView;
  categoryName: string | undefined;
  summary?: CourseRatingSummary;
}) {
  const isFree = !course.priceCents;
  const levelBadge = LEVEL_BADGE[course.level ?? ""] ?? "bg-surface-muted text-ink-muted";

  return (
    <article className="bg-surface shadow-card rounded-2xl p-5 hover:shadow-lift transition-shadow flex flex-col gap-3">
      <h3 className="text-base font-semibold leading-snug">
        <Link to={`/courses/${course.id}`} className="text-ink hover:text-brand-ink transition-colors">
          {course.title}
        </Link>
      </h3>
      <div className="flex items-center gap-2 flex-wrap">
        {course.level && (
          <span className={`text-xs font-medium px-2.5 py-0.5 rounded-full ${levelBadge}`}>{course.level}</span>
        )}
        {categoryName && <span className="text-xs text-ink-muted">{categoryName}</span>}
      </div>
      {summary && <StarRating average={summary.averageRating} count={summary.reviewCount ?? 0} />}
      <p className={`text-lg font-bold mt-auto ${isFree ? "text-success-ink" : "text-ink"}`}>
        {formatPrice(course)}
      </p>
    </article>
  );
}
