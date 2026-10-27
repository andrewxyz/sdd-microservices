import { useCallback, useEffect, useMemo, useState } from "react";
import { Link } from "react-router";
import * as endpoints from "../api/endpoints";
import type { InstructorSummary } from "../api/endpoints";
import { ApiError } from "../api/problem";
import { useAuth } from "../auth/useAuth";
import EmptyState from "../components/EmptyState";
import ErrorNotice from "../components/ErrorNotice";
import RefreshBar from "../components/RefreshBar";
import Loading from "../components/Loading";
import StatTile from "../components/StatTile";
import { formatUsd } from "../lib/money";
import { mergeCourses, sortRows, type CourseRow, type SortDirection, type SortKey } from "../lib/stats";

interface TopCourse {
  courseId: string;
  title: string | null;
  enrollments: number;
  revenueCents: number;
}

type ViewState =
  | { kind: "loading" }
  | { kind: "error"; error: ApiError }
  | { kind: "ready"; summary: InstructorSummary; rows: CourseRow[]; top: TopCourse[] };

const TOP_COURSE_COUNT = 10;

const COLUMNS: { key: SortKey; label: string }[] = [
  { key: "enrollments", label: "Enrollments" },
  { key: "revenueCents", label: "Revenue" },
];

function percent(rate: number): string {
  return `${Math.round(rate * 100)}%`;
}

function shortId(id: string): string {
  return id.slice(0, 8);
}

function toApiError(error: unknown): ApiError {
  return error instanceof ApiError ? error : ApiError.fromNetworkFailure(error);
}

/**
 * The dashboard overview (spec 009 US1, US3). Instructors see totals and one row per owned course,
 * merged client-side from their course list and the activity figures; administrators see platform totals
 * and the top courses by revenue. The backend decides who may see what; this page only presents it.
 */
export default function InstructorOverviewPage() {
  const { user } = useAuth();
  const isAdmin = user?.role === "ADMINISTRATOR";
  const [state, setState] = useState<ViewState>({ kind: "loading" });
  const [sort, setSort] = useState<{ key: SortKey; direction: SortDirection } | null>(null);
  const [refreshing, setRefreshing] = useState(false);

  const load = useCallback(async (): Promise<void> => {
    // The figures on screen stay while a refresh is in flight; only the first load (or a retry after an
    // error) shows the placeholder.
    setState((previous) => (previous.kind === "ready" ? previous : { kind: "loading" }));
    setRefreshing(true);
    try {
      if (isAdmin) {
        const [summary, list] = await Promise.all([
          endpoints.getInstructorSummary(),
          endpoints.listInstructorCourses(0, TOP_COURSE_COUNT),
        ]);
        const top: TopCourse[] = list.items.map((item) => ({
          courseId: item.courseId,
          title: null,
          enrollments: item.totals.enrollments,
          revenueCents: item.totals.revenueCents,
        }));
        setState({ kind: "ready", summary, rows: [], top });
        // Titles come from the public course endpoint; one that cannot be found falls back to a short id.
        top.forEach((course) => {
          endpoints
            .getCourse(course.courseId)
            .then((found) => found.title ?? shortId(course.courseId))
            .catch(() => shortId(course.courseId))
            .then((title) =>
              setState((previous) =>
                previous.kind === "ready"
                  ? { ...previous, top: previous.top.map((t) => (t.courseId === course.courseId ? { ...t, title } : t)) }
                  : previous,
              ),
            );
        });
        return;
      }
      const [summary, list, mine] = await Promise.all([
        endpoints.getInstructorSummary(),
        endpoints.listInstructorCourses(0, 200),
        endpoints.listMyCourses(),
      ]);
      setState({ kind: "ready", summary, rows: mergeCourses(mine, list.items), top: [] });
    } catch (error) {
      // No numbers survive a failed load or refresh: nothing stale is shown for a caller whose data cannot be confirmed.
      setState({ kind: "error", error: toApiError(error) });
    } finally {
      setRefreshing(false);
    }
  }, [isAdmin]);

  useEffect(() => {
    void load();
  }, [load]);

  const sortedRows = useMemo(() => {
    if (state.kind !== "ready") {
      return [];
    }
    return sort ? sortRows(state.rows, sort.key, sort.direction) : state.rows;
  }, [state, sort]);

  function toggleSort(key: SortKey) {
    setSort((current) =>
      current && current.key === key
        ? { key, direction: current.direction === "desc" ? "asc" : "desc" }
        : { key, direction: "desc" },
    );
  }

  function ariaSort(key: SortKey): "ascending" | "descending" | undefined {
    if (!sort || sort.key !== key) {
      return undefined;
    }
    return sort.direction === "asc" ? "ascending" : "descending";
  }

  function indicator(key: SortKey): string {
    const current = ariaSort(key);
    return current === "ascending" ? " ▲" : current === "descending" ? " ▼" : "";
  }

  return (
    <main className="max-w-6xl mx-auto px-4 py-8">
      <h1 className="text-3xl font-bold text-slate-900 mb-6">{isAdmin ? "Platform dashboard" : "Instructor dashboard"}</h1>

      {state.kind !== "loading" && (
        <RefreshBar
          generatedAt={state.kind === "ready" ? state.summary.generatedAt : null}
          onRefresh={() => void load()}
          busy={refreshing}
        />
      )}

      {state.kind === "loading" && <Loading label="Loading your dashboard…" />}
      {state.kind === "error" && <ErrorNotice error={state.error} />}

      {state.kind === "ready" && (
        <>
          {state.summary.truncated && (
            <p role="note" className="border border-amber-300 bg-amber-50 text-amber-800 rounded-lg px-4 py-3 mb-4">
              Showing your 200 newest courses; totals cover only these.
            </p>
          )}

          <section aria-label="Totals" className="mb-8">
            <dl className="grid grid-cols-2 md:grid-cols-4 gap-4">
              <StatTile label="Students enrolled" value={String(state.summary.totals.enrollments)} />
              <StatTile label="Revenue" value={formatUsd(state.summary.totals.revenueCents)} />
              <StatTile label="Completions" value={String(state.summary.totals.completions)} />
              <StatTile label="Certificates issued" value={String(state.summary.totals.certificates)} />
            </dl>
          </section>

          {isAdmin ? (
            <section aria-label="Top courses by revenue">
              <h2 className="text-xl font-semibold text-slate-900 mb-3">Top courses by revenue</h2>
              {state.top.length === 0 ? (
                <EmptyState message="No course activity yet — nothing to show yet." />
              ) : (
                <div className="overflow-x-auto">
                  <table className="w-full text-left border border-slate-200 bg-white rounded-lg">
                    <thead className="bg-slate-50 text-slate-600 text-sm">
                      <tr>
                        <th scope="col" className="px-3 py-2">Course</th>
                        <th scope="col" className="px-3 py-2">Enrollments</th>
                        <th scope="col" className="px-3 py-2">Revenue</th>
                      </tr>
                    </thead>
                    <tbody>
                      {state.top.map((course) => (
                        <tr key={course.courseId} className="border-t border-slate-100">
                          <td className="px-3 py-2">
                            <Link
                              to={`/instructor/courses/${encodeURIComponent(course.courseId)}`}
                              className="text-indigo-700 hover:underline"
                            >
                              {course.title ?? shortId(course.courseId)}
                            </Link>
                          </td>
                          <td className="px-3 py-2">{course.enrollments}</td>
                          <td className="px-3 py-2">{formatUsd(course.revenueCents)}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}
            </section>
          ) : state.rows.length === 0 ? (
            <EmptyState message="You have no courses yet, so there is nothing to show yet." />
          ) : (
            <section aria-label="Your courses">
              <h2 className="text-xl font-semibold text-slate-900 mb-3">Your courses</h2>
              <div className="overflow-x-auto">
                <table className="w-full text-left border border-slate-200 bg-white rounded-lg">
                  <thead className="bg-slate-50 text-slate-600 text-sm">
                    <tr>
                      <th scope="col" className="px-3 py-2">Course</th>
                      <th scope="col" className="px-3 py-2">Status</th>
                      {COLUMNS.slice(0, 2).map((column) => (
                        <th key={column.key} scope="col" aria-sort={ariaSort(column.key)} className="px-3 py-2">
                          <button type="button" onClick={() => toggleSort(column.key)} className="font-semibold cursor-pointer">
                            {column.label}
                            {indicator(column.key)}
                          </button>
                        </th>
                      ))}
                      <th scope="col" className="px-3 py-2">Completions</th>
                      <th scope="col" className="px-3 py-2">Certificates</th>
                      <th scope="col" aria-sort={ariaSort("completionRate")} className="px-3 py-2">
                        <button type="button" onClick={() => toggleSort("completionRate")} className="font-semibold cursor-pointer">
                          Completion rate
                          {indicator("completionRate")}
                        </button>
                      </th>
                    </tr>
                  </thead>
                  <tbody>
                    {sortedRows.map((row) => (
                      <tr key={row.courseId} className="border-t border-slate-100">
                        <td className="px-3 py-2">
                          <Link
                            to={`/instructor/courses/${encodeURIComponent(row.courseId)}`}
                            className="text-indigo-700 hover:underline"
                          >
                            {row.title}
                          </Link>
                        </td>
                        <td className="px-3 py-2">{row.status}</td>
                        <td className="px-3 py-2">{row.enrollments}</td>
                        <td className="px-3 py-2">{formatUsd(row.revenueCents)}</td>
                        <td className="px-3 py-2">{row.completions}</td>
                        <td className="px-3 py-2">{row.certificates}</td>
                        <td className="px-3 py-2">{percent(row.completionRate)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </section>
          )}
        </>
      )}
    </main>
  );
}
