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
      <div className="enroll-panel">
        <p>You're enrolled!</p>
        <Link to={`/learn/${course.id}`}>Start learning</Link>
      </div>
    );
  }

  if (phase === "pending") {
    return (
      <div className="enroll-panel">
        <p>Your payment succeeded. Enrollment is still being confirmed — check back shortly.</p>
      </div>
    );
  }

  if (phase === "blocked" && error) {
    return (
      <div className="enroll-panel">
        <p role="alert">{error.detail ?? error.message}</p>
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
    <div className="enroll-panel">
      {!isFree && (
        <fieldset>
          <legend>Choose a test outcome</legend>
          {CHOICES.map((option) => (
            <label key={option.value}>
              <input
                type="radio"
                name="payment-choice"
                value={option.value}
                checked={choice === option.value}
                onChange={() => setChoice(option.value)}
              />
              {option.label}
            </label>
          ))}
        </fieldset>
      )}

      {phase === "declined" && payment && (
        <p role="alert">Payment declined: {payment.failureReason ?? "please try a different outcome."}</p>
      )}
      {phase === "failed" && (
        <p role="alert">{error?.message ?? "Something went wrong. Please try again."}</p>
      )}

      <button type="button" onClick={onSubmit} disabled={busy || (!isFree && !choice)}>
        {busy ? "Processing…" : isFree ? "Enroll for free" : "Submit test payment"}
      </button>
    </div>
  );
}
