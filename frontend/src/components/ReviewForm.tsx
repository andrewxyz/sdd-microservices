import { useEffect, useState, type FormEvent } from "react";
import { Link } from "react-router";
import * as endpoints from "../api/endpoints";
import type { ReviewView } from "../api/endpoints";
import { ApiError } from "../api/problem";
import { useAuth } from "../auth/useAuth";
import ErrorNotice from "./ErrorNotice";
import Field from "./Field";

const MAX_COMMENT = 500;

// The generic friendly message hides why a review was refused; for these statuses the service's own
// explanation (detail) is the useful text, and the reference code stays attached (FR-007).
function withServiceMessage(err: ApiError): ApiError {
  if ((err.status === 400 || err.status === 403) && err.detail) {
    return new ApiError({ ...err, message: err.detail });
  }
  return err;
}

/**
 * Submit, edit or delete the caller's own review. Signed-out visitors get a sign-in prompt instead of the
 * form (FR-006). Enrollment is not pre-checked; the backend's refusal is shown as is (research.md R5).
 */
export default function ReviewForm({
  courseId,
  existing,
  onChanged,
  clearErrorKey,
}: {
  courseId: string;
  existing?: ReviewView;
  onChanged: () => void;
  /** Changing this value clears a previous error, e.g. a "not enrolled" refusal once the student enrolls. */
  clearErrorKey?: number;
}) {
  const { status } = useAuth();
  const [rating, setRating] = useState(String(existing?.rating ?? 5));
  const [comment, setComment] = useState(existing?.comment ?? "");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);

  useEffect(() => {
    setError(null);
  }, [clearErrorKey]);

  if (status === "restoring") {
    return null;
  }
  if (status !== "authenticated") {
    return (
      <p className="text-sm text-ink-muted">
        <Link
          to={`/login?returnTo=${encodeURIComponent(`/courses/${courseId}`)}`}
          className="font-medium text-brand-ink hover:text-brand-ink"
        >
          Sign in
        </Link>{" "}
        to review this course.
      </p>
    );
  }

  async function run(action: () => Promise<unknown>) {
    setBusy(true);
    setError(null);
    try {
      await action();
      onChanged();
    } catch (err: unknown) {
      setError(err instanceof ApiError ? withServiceMessage(err) : null);
    } finally {
      setBusy(false);
    }
  }

  function onSubmit(e: FormEvent) {
    e.preventDefault();
    const body = { rating: Number(rating), comment: comment.trim() === "" ? null : comment };
    void run(() => (existing?.id ? endpoints.updateReview(existing.id, body) : endpoints.createReview(courseId, body)));
  }

  return (
    <form onSubmit={onSubmit} className="flex flex-col">
      <Field
        label="Rating (1-5)"
        name="rating"
        type="number"
        min={1}
        max={5}
        step={1}
        required
        value={rating}
        onChange={(e) => setRating(e.target.value)}
      />
      <div className="flex flex-col gap-1 mb-4">
        <label htmlFor="comment" className="text-sm font-medium text-ink-body">
          Comment (optional)
        </label>
        <textarea
          id="comment"
          name="comment"
          rows={3}
          maxLength={MAX_COMMENT}
          value={comment}
          onChange={(e) => setComment(e.target.value)}
          className="border border-line-strong shadow-inset rounded-xl px-3 py-2.5 text-sm focus:outline-none focus:ring-2 focus:ring-focus focus:border-transparent transition-shadow"
        />
        <span className="text-xs text-ink-muted">
          {comment.length}/{MAX_COMMENT}
        </span>
      </div>
      {error && <ErrorNotice error={error} />}
      <div className="flex gap-3">
        <button
          type="submit"
          disabled={busy}
          className="bg-brand hover:bg-brand-strong shadow-card active:shadow-inset disabled:opacity-60 text-on-brand px-5 py-2 rounded-xl text-sm font-medium transition-colors cursor-pointer"
        >
          {existing ? "Save changes" : "Submit review"}
        </button>
        {existing?.id && (
          <button
            type="button"
            disabled={busy}
            onClick={() => {
              if (window.confirm("Delete your review? This cannot be undone.")) {
                void run(() => endpoints.deleteReview(existing.id!));
              }
            }}
            className="border border-danger-line text-danger-ink hover:bg-danger-soft disabled:opacity-60 px-5 py-2 rounded-xl text-sm font-medium transition-colors cursor-pointer"
          >
            Delete my review
          </button>
        )}
      </div>
    </form>
  );
}
