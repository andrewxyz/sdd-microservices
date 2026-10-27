// Pure helpers behind the instructor dashboard tables (spec 009 FR-002..FR-004): merging an
// instructor's own course list with the activity figures, the completion rate, and sorting.

export interface TotalsView {
  enrollments: number;
  revenueCents: number;
  completions: number;
  certificates: number;
}

/** The parts of an owned course the table needs; every field may be absent in a loosely typed response. */
export interface OwnedCourse {
  id?: string;
  title?: string;
  status?: string;
}

export interface CourseActivity {
  courseId: string;
  totals: TotalsView;
}

export interface CourseRow {
  courseId: string;
  title: string;
  status: string;
  enrollments: number;
  revenueCents: number;
  completions: number;
  certificates: number;
  /** completions / enrollments, between 0 and 1. */
  completionRate: number;
}

export type SortKey = "enrollments" | "revenueCents" | "completionRate";
export type SortDirection = "asc" | "desc";

/** Completions over enrollments; 0 when there are no enrollments, never above 1. */
export function completionRate(completions: number, enrollments: number): number {
  if (enrollments <= 0) {
    return 0;
  }
  return Math.min(1, completions / enrollments);
}

/**
 * One row per owned course, in the order given, drafts and archived included. A course with no activity
 * gets zeros. Activity for a course the caller does not own is ignored.
 */
export function mergeCourses(owned: OwnedCourse[], activity: CourseActivity[]): CourseRow[] {
  const byCourse = new Map(activity.map((a) => [a.courseId, a.totals]));
  const rows: CourseRow[] = [];
  for (const course of owned) {
    if (!course.id) {
      continue;
    }
    const totals = byCourse.get(course.id);
    const enrollments = totals?.enrollments ?? 0;
    const completions = totals?.completions ?? 0;
    rows.push({
      courseId: course.id,
      title: course.title ?? "",
      status: course.status ?? "",
      enrollments,
      revenueCents: totals?.revenueCents ?? 0,
      completions,
      certificates: totals?.certificates ?? 0,
      completionRate: completionRate(completions, enrollments),
    });
  }
  return rows;
}

/** A sorted copy; ties are broken by title so the order is stable and predictable. */
export function sortRows(rows: CourseRow[], key: SortKey, direction: SortDirection): CourseRow[] {
  const sign = direction === "asc" ? 1 : -1;
  return [...rows].sort((a, b) => {
    const difference = a[key] - b[key];
    if (difference !== 0) {
      return sign * difference;
    }
    return a.title.localeCompare(b.title);
  });
}
