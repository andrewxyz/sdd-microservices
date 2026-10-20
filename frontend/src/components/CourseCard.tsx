import { Link } from "react-router";
import type { CoursePublicView } from "../api/endpoints";

const LEVEL_BADGE: Record<string, string> = {
  BEGINNER: "bg-emerald-100 text-emerald-800",
  INTERMEDIATE: "bg-amber-100 text-amber-800",
  ADVANCED: "bg-red-100 text-red-800",
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
}: {
  course: CoursePublicView;
  categoryName: string | undefined;
}) {
  const isFree = !course.priceCents;
  const levelBadge = LEVEL_BADGE[course.level ?? ""] ?? "bg-slate-100 text-slate-600";

  return (
    <article className="bg-white border border-slate-200 rounded-xl p-5 hover:shadow-lg hover:-translate-y-0.5 transition-all duration-200 flex flex-col gap-3">
      <h3 className="text-base font-semibold leading-snug">
        <Link to={`/courses/${course.id}`} className="text-slate-800 hover:text-indigo-600 transition-colors">
          {course.title}
        </Link>
      </h3>
      <div className="flex items-center gap-2 flex-wrap">
        {course.level && (
          <span className={`text-xs font-medium px-2.5 py-0.5 rounded-full ${levelBadge}`}>{course.level}</span>
        )}
        {categoryName && <span className="text-xs text-slate-500">{categoryName}</span>}
      </div>
      <p className={`text-lg font-bold mt-auto ${isFree ? "text-emerald-600" : "text-slate-800"}`}>
        {formatPrice(course)}
      </p>
    </article>
  );
}
