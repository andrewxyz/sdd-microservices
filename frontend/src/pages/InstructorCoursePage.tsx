import { useCallback, useEffect, useState } from "react";
import { Link, useParams } from "react-router";
import * as endpoints from "../api/endpoints";
import type { InstructorCourseDetail } from "../api/endpoints";
import { ApiError } from "../api/problem";
import { useAuth } from "../auth/useAuth";
import DailyBarChart from "../components/DailyBarChart";
import EmptyState from "../components/EmptyState";
import ErrorNotice from "../components/ErrorNotice";
import FunnelBars from "../components/FunnelBars";
import RefreshBar from "../components/RefreshBar";
import Loading from "../components/Loading";
import StatTile from "../components/StatTile";
import { formatUsd } from "../lib/money";

type Days = 7 | 30 | 90;
const RANGES: Days[] = [7, 30, 90];

type ViewState =
  | { kind: "loading" }
  | { kind: "error"; error: ApiError }
  | { kind: "ready"; detail: InstructorCourseDetail };

function toApiError(error: unknown): ApiError {
  return error instanceof ApiError ? error : ApiError.fromNetworkFailure(error);
}

/** The same error with a message that says what happened, keeping its reference code. */
function withMessage(error: ApiError, message: string): ApiError {
  return new ApiError({
    status: error.status,
    title: error.title,
    detail: error.detail,
    correlationId: error.correlationId,
    retryAfterSeconds: error.retryAfterSeconds,
    message,
  });
}

/**
 * One course's totals, funnel and daily enrollments (spec 009 US2). The range toggle refetches; the
 * chart and its table come from the same response. Another instructor's course and a missing course
 * both arrive as 404 and are shown the same way.
 */
export default function InstructorCoursePage() {
  const { courseId = "" } = useParams<{ courseId: string }>();
  const { user } = useAuth();
  const isAdmin = user?.role === "ADMINISTRATOR";
  const [days, setDays] = useState<Days>(30);
  const [state, setState] = useState<ViewState>({ kind: "loading" });
  const [title, setTitle] = useState<string | null>(null);
  const [refreshing, setRefreshing] = useState(false);

  const load = useCallback(async () => {
    // Keep the page (and the range buttons' focus) while a new range loads; only the first load shows the placeholder.
    setState((previous) => (previous.kind === "ready" ? previous : { kind: "loading" }));
    setRefreshing(true);
    try {
      setState({ kind: "ready", detail: await endpoints.getInstructorCourse(courseId, days) });
    } catch (error) {
      // No numbers survive a failed load or refresh (spec FR-016).
      setState({ kind: "error", error: toApiError(error) });
    } finally {
      setRefreshing(false);
    }
  }, [courseId, days]);

  useEffect(() => {
    void load();
  }, [load]);

  // The title is a nicety: an instructor's own list has it (drafts included), an administrator uses the
  // public course. If neither can say, the heading falls back to a short id.
  useEffect(() => {
    let cancelled = false;
    const lookup = isAdmin
      ? endpoints.getCourse(courseId).then((course) => course.title ?? null)
      : endpoints.listMyCourses().then((mine) => mine.find((course) => course.id === courseId)?.title ?? null);
    lookup
      .then((found) => {
        if (!cancelled) {
          setTitle(found);
        }
      })
      .catch(() => undefined);
    return () => {
      cancelled = true;
    };
  }, [courseId, isAdmin]);

  const heading = title ?? `Course ${courseId.slice(0, 8)}`;
  const error = state.kind === "error" ? state.error : null;

  return (
    <main className="max-w-6xl mx-auto px-4 py-8">
      <Link to="/instructor" className="text-indigo-700 hover:underline text-sm">
        ← Back to dashboard
      </Link>
      <h1 className="text-3xl font-bold text-slate-900 mt-2 mb-6">{heading}</h1>

      {state.kind !== "loading" && (
        <RefreshBar
          generatedAt={state.kind === "ready" ? state.detail.generatedAt : null}
          onRefresh={() => void load()}
          busy={refreshing}
        />
      )}

      {state.kind === "loading" && <Loading label="Loading course figures…" />}

      {error && error.status === 404 && <EmptyState message="Course not found." />}
      {error && error.status === 403 && (
        <ErrorNotice error={withMessage(error, "You are not allowed to view this dashboard.")} />
      )}
      {error && error.status !== 404 && error.status !== 403 && <ErrorNotice error={error} />}

      {state.kind === "ready" && (
        <>
          <section aria-label="Totals" className="mb-8">
            <dl className="grid grid-cols-2 md:grid-cols-4 gap-4">
              <StatTile label="Students enrolled" value={String(state.detail.totals.enrollments)} />
              <StatTile label="Revenue" value={formatUsd(state.detail.totals.revenueCents)} />
              <StatTile label="Completions" value={String(state.detail.totals.completions)} />
              <StatTile label="Certificates issued" value={String(state.detail.totals.certificates)} />
            </dl>
          </section>

          {state.detail.totals.enrollments === 0 &&
            state.detail.totals.revenueCents === 0 &&
            state.detail.totals.completions === 0 &&
            state.detail.totals.certificates === 0 && (
              <p className="text-slate-500 mb-6">No activity yet for this course.</p>
            )}

          <section aria-label="Funnel" className="mb-8">
            <h2 className="text-xl font-semibold text-slate-900 mb-3">Funnel</h2>
            <FunnelBars
              enrolled={state.detail.funnel.enrolled}
              completed={state.detail.funnel.completed}
              certificateIssued={state.detail.funnel.certificateIssued}
            />
          </section>

          <section aria-label="Enrollments per day">
            <div className="flex flex-wrap items-center justify-between gap-2 mb-3">
              <h2 className="text-xl font-semibold text-slate-900">Enrollments per day</h2>
              <div className="flex gap-2">
                {RANGES.map((range) => (
                  <button
                    key={range}
                    type="button"
                    aria-pressed={days === range}
                    onClick={() => setDays(range)}
                    className={`px-3 py-1 rounded-full text-sm border cursor-pointer ${
                      days === range ? "bg-indigo-600 text-white border-indigo-600" : "bg-white text-slate-700 border-slate-300"
                    }`}
                  >
                    {range} days
                  </button>
                ))}
              </div>
            </div>
            <DailyBarChart series={state.detail.series} />
          </section>
        </>
      )}
    </main>
  );
}
