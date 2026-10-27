import { useEffect, useMemo, useState } from "react";
import { useSearchParams } from "react-router";
import * as endpoints from "../api/endpoints";
import type { Category, CoursePublicView, CourseRatingSummary, Page } from "../api/endpoints";
import { ApiError } from "../api/problem";
import CourseCard from "../components/CourseCard";
import EmptyState from "../components/EmptyState";
import ErrorNotice from "../components/ErrorNotice";
import Loading from "../components/Loading";

// Catalog state lives in the URL, not in React state (research.md R11, FR-009) — a shareable,
// reload-surviving result set with no second copy to drift.
const SORT_OPTIONS = ["newest", "title", "price_asc", "price_desc"] as const;
type SortOption = (typeof SORT_OPTIONS)[number];
const LEVEL_OPTIONS = ["BEGINNER", "INTERMEDIATE", "ADVANCED"] as const;
type LevelOption = (typeof LEVEL_OPTIONS)[number];

function isSortOption(value: string | null): value is SortOption {
  return value !== null && (SORT_OPTIONS as readonly string[]).includes(value);
}

function isLevelOption(value: string): value is LevelOption {
  return (LEVEL_OPTIONS as readonly string[]).includes(value);
}

const selectClass =
  "border border-line-strong shadow-inset rounded-xl px-3 py-2 text-sm bg-surface focus:outline-none focus:ring-2 focus:ring-focus focus:border-transparent transition-shadow";

export default function CatalogPage() {
  const [searchParams, setSearchParams] = useSearchParams();
  const [categories, setCategories] = useState<Category[]>([]);
  const [result, setResult] = useState<Page<CoursePublicView> | null>(null);
  const [summaries, setSummaries] = useState<Map<string, CourseRatingSummary>>(new Map());
  const [error, setError] = useState<ApiError | null>(null);
  const [loading, setLoading] = useState(true);

  const q = searchParams.get("q") ?? "";
  const category = searchParams.get("category") ?? "";
  const level = searchParams.get("level") ?? "";
  const free = searchParams.get("free") === "true";
  const sort: SortOption = isSortOption(searchParams.get("sort")) ? (searchParams.get("sort") as SortOption) : "newest";
  const page = Number(searchParams.get("page") ?? "0") || 0;
  const size = Number(searchParams.get("size") ?? "20") || 20;

  useEffect(() => {
    endpoints
      .listCategories()
      .then(setCategories)
      .catch(() => setCategories([]));
  }, []);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    setError(null);
    endpoints
      .listCourses({
        q: q || undefined,
        category: category || undefined,
        level: isLevelOption(level) ? level : undefined,
        free: free ? true : undefined,
        sort,
        page,
        size,
      })
      .then((coursesPage) => {
        if (!cancelled) {
          setResult(coursesPage);
        }
      })
      .catch((err: unknown) => {
        if (!cancelled) {
          setError(err instanceof ApiError ? err : null);
        }
      })
      .finally(() => {
        if (!cancelled) {
          setLoading(false);
        }
      });
    return () => {
      cancelled = true;
    };
  }, [q, category, level, free, sort, page, size]);

  // Ratings are best-effort (FR-004): one request per 50 visible ids; a failure just leaves cards unrated.
  useEffect(() => {
    setSummaries(new Map());
    const ids = (result?.items ?? []).map((c) => c.id).filter((id): id is string => !!id);
    if (ids.length === 0) {
      return;
    }
    let cancelled = false;
    const chunks: string[][] = [];
    for (let i = 0; i < ids.length; i += endpoints.MAX_SUMMARY_IDS) {
      chunks.push(ids.slice(i, i + endpoints.MAX_SUMMARY_IDS));
    }
    Promise.all(chunks.map((chunk) => endpoints.getReviewSummaries(chunk)))
      .then((responses) => {
        if (cancelled) {
          return;
        }
        const next = new Map<string, CourseRatingSummary>();
        responses.forEach((r) => r.items?.forEach((s) => s.courseId && next.set(s.courseId, s)));
        setSummaries(next);
      })
      .catch(() => {});
    return () => {
      cancelled = true;
    };
  }, [result]);

  const categoryNameById = useMemo(() => {
    const map = new Map<string, string>();
    categories.forEach((c) => {
      if (c.id && c.name) {
        map.set(c.id, c.name);
      }
    });
    return map;
  }, [categories]);

  function updateParam(name: string, value: string | null) {
    const next = new URLSearchParams(searchParams);
    if (value === null || value === "") {
      next.delete(name);
    } else {
      next.set(name, value);
    }
    if (name !== "page") {
      next.delete("page");
    }
    setSearchParams(next);
  }

  function clearFilters() {
    setSearchParams(new URLSearchParams());
  }

  return (
    <div className="min-h-screen bg-canvas">
      {/* Hero */}
      <div className="bg-hero text-on-hero py-14 px-4">
        <div className="max-w-6xl mx-auto">
          <h1 className="text-4xl font-bold mb-2 tracking-tight">Discover Your Next Skill</h1>
          <p className="text-on-hero-muted text-lg">Explore our growing library of courses</p>
        </div>
      </div>

      <div className="max-w-6xl mx-auto px-4 py-8">
        {/* Filters */}
        <form
          className="bg-surface rounded-2xl shadow-card p-4 mb-8 flex flex-wrap gap-3 items-center"
          onSubmit={(e) => e.preventDefault()}
        >
          <input
            aria-label="Search"
            placeholder="Search courses…"
            value={q}
            onChange={(e) => updateParam("q", e.target.value)}
            className="flex-1 min-w-[180px] border border-line-strong shadow-inset rounded-xl px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-focus focus:border-transparent transition-shadow"
          />
          <select
            aria-label="Category"
            value={category}
            onChange={(e) => updateParam("category", e.target.value)}
            className={selectClass}
          >
            <option value="">All categories</option>
            {categories.map((c) => (
              <option key={c.id} value={c.id}>
                {c.name}
              </option>
            ))}
          </select>
          <select
            aria-label="Level"
            value={level}
            onChange={(e) => updateParam("level", e.target.value)}
            className={selectClass}
          >
            <option value="">Any level</option>
            <option value="BEGINNER">Beginner</option>
            <option value="INTERMEDIATE">Intermediate</option>
            <option value="ADVANCED">Advanced</option>
          </select>
          <label className="flex items-center gap-2 text-sm text-ink-body cursor-pointer">
            <input
              type="checkbox"
              checked={free}
              onChange={(e) => updateParam("free", e.target.checked ? "true" : null)}
              className="accent-brand w-4 h-4"
            />
            Free only
          </label>
          <select
            aria-label="Sort"
            value={sort}
            onChange={(e) => updateParam("sort", e.target.value)}
            className={selectClass}
          >
            <option value="newest">Newest</option>
            <option value="title">Title</option>
            <option value="price_asc">Price: low to high</option>
            <option value="price_desc">Price: high to low</option>
          </select>
        </form>

        {loading && <Loading label="Loading courses…" />}
        {error && <ErrorNotice error={error} />}
        {!loading && !error && result && result.items.length === 0 && (
          <EmptyState
            message="No courses match these filters."
            action={
              <button
                type="button"
                onClick={clearFilters}
                className="bg-brand hover:bg-brand-strong shadow-card active:shadow-inset text-on-brand px-5 py-2 rounded-xl text-sm font-medium transition-colors cursor-pointer"
              >
                Clear filters
              </button>
            }
          />
        )}
        {!loading && !error && result && result.items.length > 0 && (
          <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-6">
            {result.items.map((course) => (
              <CourseCard
                key={course.id}
                course={course}
                categoryName={course.categoryId ? categoryNameById.get(course.categoryId) : undefined}
                summary={course.id ? summaries.get(course.id) : undefined}
              />
            ))}
          </div>
        )}
      </div>
    </div>
  );
}
