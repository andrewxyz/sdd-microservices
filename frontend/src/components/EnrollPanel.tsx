import { useEffect } from "react";
import { Link } from "react-router";
import { useCheckout, type PaymentChoice } from "../hooks/useCheckout";
import type { CoursePublicView } from "../api/endpoints";

const CHOICES: { value: Exclude<PaymentChoice, null>; label: string }[] = [
  { value: "sim-success", label: "Test card: succeeds" },
  { value: "sim-decline", label: "Test card: declined" },
  { value: "sim-unavailable", label: "Test card: provider unavailable" },
];

/**
 * A single confirm for a free course; for a paid course, exactly three outcomes labelled as test
 * cards — never a field asking for real payment credentials (FR-019, FR-020). The submit control
 * is disabled while a request is in flight (FR-024).
 */
export default function EnrollPanel({
  course,
  onEnrolled,
  alreadyEnrolled = false,
}: {
  course: CoursePublicView;
  onEnrolled?: () => void;
  /** The student owned this course before the page loaded, so show the enrolled state, not the checkout. */
  alreadyEnrolled?: boolean;
}) {
  const isFree = !course.priceCents;
  const { phase, payment, error, choice, setChoice, submit } = useCheckout(course.id ?? "");

  const busy = phase === "submitting" || phase === "confirming";

  useEffect(() => {
    if (phase === "enrolled") {
      onEnrolled?.();
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [phase]);

  if (phase === "enrolled" || alreadyEnrolled) {
    return (
      <div className="mt-6 p-5 bg-success-soft border border-success-line rounded-2xl flex items-center gap-4">
        <span className="text-3xl">🎉</span>
        <div>
          <p className="font-semibold text-success-ink">You're enrolled!</p>
          <Link
            to={`/learn/${course.id}`}
            className="text-success-ink underline underline-offset-2 hover:text-success-ink text-sm font-medium"
          >
            Start learning →
          </Link>
        </div>
      </div>
    );
  }

  if (phase === "pending") {
    return (
      <div className="mt-6 p-5 bg-warning-soft border border-warning-line rounded-2xl">
        <p className="text-warning-ink">Your payment succeeded. Enrollment is still being confirmed — check back shortly.</p>
      </div>
    );
  }

  if (phase === "blocked" && error) {
    return (
      <div className="mt-6 p-5 bg-danger-soft border border-danger-line rounded-2xl">
        <p role="alert" className="text-danger-ink">
          {error.detail ?? error.message}
        </p>
      </div>
    );
  }

  async function onSubmit() {
    if (!isFree && !choice) {
      return;
    }
    await submit();
  }

  return (
    <div className="mt-6 p-5 bg-surface-muted border border-brand-soft-strong rounded-2xl">
      {!isFree && (
        <fieldset className="border-none p-0 m-0 mb-4">
          <legend className="text-sm font-semibold text-ink-body mb-3">Choose a test outcome</legend>
          <div className="flex flex-col gap-2">
            {CHOICES.map((option) => (
              <label
                key={option.value}
                className="flex items-center gap-3 p-3 bg-surface rounded-2xl shadow-card cursor-pointer hover:border-brand transition-colors"
              >
                <input
                  type="radio"
                  name="payment-choice"
                  value={option.value}
                  checked={choice === option.value}
                  onChange={() => setChoice(option.value)}
                  className="accent-brand"
                />
                <span className="text-sm text-ink-body">{option.label}</span>
              </label>
            ))}
          </div>
        </fieldset>
      )}

      {phase === "declined" && payment && (
        <p role="alert" className="text-sm text-danger-ink mb-3">
          Payment declined: {payment.failureReason ?? "please try a different outcome."}
        </p>
      )}
      {phase === "failed" && (
        <p role="alert" className="text-sm text-danger-ink mb-3">
          {error?.message ?? "Something went wrong. Please try again."}
        </p>
      )}

      <button
        type="button"
        onClick={onSubmit}
        disabled={busy || (!isFree && !choice)}
        className="w-full bg-brand hover:bg-brand-strong shadow-card active:shadow-inset disabled:opacity-60 disabled:cursor-not-allowed text-on-brand font-semibold py-3 rounded-xl transition-colors cursor-pointer"
      >
        {busy ? "Processing…" : isFree ? "Enroll for free" : "Submit test payment"}
      </button>
    </div>
  );
}
