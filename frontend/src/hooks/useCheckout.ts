import { useCallback, useRef, useState } from "react";
import * as endpoints from "../api/endpoints";
import type { PaymentView } from "../api/endpoints";
import { ApiError } from "../api/problem";

// The checkout state machine (data-model.md, research.md R7, R8). This is the single most
// carefully specified piece of state in the app because it is the one that can cost money.

export type PaymentChoice = "sim-success" | "sim-decline" | "sim-unavailable" | null;

export type CheckoutPhase =
  | "idle"
  | "submitting"
  | "confirming"
  | "enrolled"
  | "pending"
  | "declined"
  | "failed"
  | "blocked";

export interface UseCheckoutResult {
  phase: CheckoutPhase;
  payment: PaymentView | null;
  error: ApiError | null;
  choice: PaymentChoice;
  /** Changing the choice regenerates the idempotency key (R7) and resets to idle. */
  setChoice: (choice: PaymentChoice) => void;
  submit: () => Promise<void>;
}

const POLL_INTERVAL_MS = 1000;
const POLL_TIMEOUT_MS = 15000;

function delay(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

export function useCheckout(courseId: string): UseCheckoutResult {
  const [choice, setChoiceState] = useState<PaymentChoice>(null);
  const [phase, setPhase] = useState<CheckoutPhase>("idle");
  const [payment, setPayment] = useState<PaymentView | null>(null);
  const [error, setError] = useState<ApiError | null>(null);

  const keyRef = useRef<string | null>(null);
  // True once the current key has produced a *completed* outcome (a business DECLINED/FAILED
  // result) that the user is now retrying — a genuinely new attempt, so the next submit must mint
  // a fresh key. A network failure or a transient 503 never sets this: those retries replay the
  // same, possibly-never-processed attempt, so the key is reused (R7).
  const needsFreshKeyRef = useRef(false);

  const setChoice = useCallback((next: PaymentChoice) => {
    setChoiceState(next);
    keyRef.current = null;
    needsFreshKeyRef.current = false;
    setPhase("idle");
    setError(null);
    setPayment(null);
  }, []);

  function currentKey(): string {
    if (!keyRef.current || needsFreshKeyRef.current) {
      keyRef.current = crypto.randomUUID();
      needsFreshKeyRef.current = false;
    }
    return keyRef.current;
  }

  async function pollForEnrollment(): Promise<void> {
    setPhase("confirming");
    const deadline = Date.now() + POLL_TIMEOUT_MS;
    for (;;) {
      try {
        await endpoints.getEnrollmentProgress(courseId);
        setPhase("enrolled");
        return;
      } catch (err) {
        const keepWaiting = err instanceof ApiError && (err.status === 403 || err.status === 503);
        if (!keepWaiting) {
          setError(err instanceof ApiError ? err : null);
          setPhase("failed");
          return;
        }
      }
      if (Date.now() + POLL_INTERVAL_MS > deadline) {
        setPhase("pending");
        return;
      }
      await delay(POLL_INTERVAL_MS);
    }
  }

  async function submit(): Promise<void> {
    setPhase("submitting");
    setError(null);
    const idempotencyKey = currentKey();

    try {
      const result = await endpoints.checkout({
        courseId,
        idempotencyKey,
        paymentToken: choice ?? undefined,
      });
      setPayment(result);

      if (result.status === "SUCCEEDED") {
        await pollForEnrollment();
      } else if (result.status === "DECLINED") {
        needsFreshKeyRef.current = true;
        setPhase("declined");
      } else {
        // FAILED: the provider itself resolved the attempt unsuccessfully — terminal.
        needsFreshKeyRef.current = true;
        setPhase("failed");
      }
    } catch (err) {
      if (err instanceof ApiError) {
        setError(err);
        if (err.status === 403 || err.status === 404 || err.status === 409 || err.status === 422) {
          setPhase("blocked");
          return;
        }
        // Network failure (status 0) or a transient 503: nothing was confirmed either way, so the
        // same key is kept for a safe retry (R7).
        setPhase("failed");
        return;
      }
      setPhase("failed");
    }
  }

  return { phase, payment, error, choice, setChoice, submit };
}
