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
export default function EnrollPanel({ course }: { course: CoursePublicView }) {
  const isFree = !course.priceCents;
  const { phase, payment, error, choice, setChoice, submit } = useCheckout(course.id ?? "");

  const busy = phase === "submitting" || phase === "confirming";

  if (phase === "enrolled") {
    return (
      <div className="mt-6 p-5 bg-emerald-50 border border-emerald-200 rounded-xl flex items-center gap-4">
        <span className="text-3xl">🎉</span>
        <div>
          <p className="font-semibold text-emerald-800">You're enrolled!</p>
          <Link
            to={`/learn/${course.id}`}
            className="text-emerald-700 underline underline-offset-2 hover:text-emerald-900 text-sm font-medium"
          >
            Start learning →
          </Link>
        </div>
      </div>
    );
  }

  if (phase === "pending") {
    return (
      <div className="mt-6 p-5 bg-amber-50 border border-amber-200 rounded-xl">
        <p className="text-amber-800">Your payment succeeded. Enrollment is still being confirmed — check back shortly.</p>
      </div>
    );
  }

  if (phase === "blocked" && error) {
    return (
      <div className="mt-6 p-5 bg-red-50 border border-red-200 rounded-xl">
        <p role="alert" className="text-red-700">
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
    <div className="mt-6 p-5 bg-gradient-to-br from-indigo-50 to-purple-50 border border-indigo-200 rounded-xl">
      {!isFree && (
        <fieldset className="border-none p-0 m-0 mb-4">
          <legend className="text-sm font-semibold text-slate-700 mb-3">Choose a test outcome</legend>
          <div className="flex flex-col gap-2">
            {CHOICES.map((option) => (
              <label
                key={option.value}
                className="flex items-center gap-3 p-3 bg-white rounded-lg border border-slate-200 cursor-pointer hover:border-indigo-400 transition-colors"
              >
                <input
                  type="radio"
                  name="payment-choice"
                  value={option.value}
                  checked={choice === option.value}
                  onChange={() => setChoice(option.value)}
                  className="accent-indigo-600"
                />
                <span className="text-sm text-slate-700">{option.label}</span>
              </label>
            ))}
          </div>
        </fieldset>
      )}

      {phase === "declined" && payment && (
        <p role="alert" className="text-sm text-red-600 mb-3">
          Payment declined: {payment.failureReason ?? "please try a different outcome."}
        </p>
      )}
      {phase === "failed" && (
        <p role="alert" className="text-sm text-red-600 mb-3">
          {error?.message ?? "Something went wrong. Please try again."}
        </p>
      )}

      <button
        type="button"
        onClick={onSubmit}
        disabled={busy || (!isFree && !choice)}
        className="w-full bg-indigo-600 hover:bg-indigo-700 disabled:opacity-60 disabled:cursor-not-allowed text-white font-semibold py-3 rounded-lg transition-colors cursor-pointer"
      >
        {busy ? "Processing…" : isFree ? "Enroll for free" : "Submit test payment"}
      </button>
    </div>
  );
}
