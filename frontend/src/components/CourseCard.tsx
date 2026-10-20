import { Link } from "react-router";
import type { CoursePublicView } from "../api/endpoints";

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
  return (
    <article className="course-card">
      <h3>
        <Link to={`/courses/${course.id}`}>{course.title}</Link>
      </h3>
      <p className="course-card__meta">
        {course.level} {categoryName ? `· ${categoryName}` : ""}
      </p>
      <p className="course-card__price">{formatPrice(course)}</p>
    </article>
  );
}
