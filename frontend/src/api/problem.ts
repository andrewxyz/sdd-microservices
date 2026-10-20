// Every failed call produces one ApiError, parsed from the shared problem+json shape
// (specs/001-platform-foundation/contracts/problem-details.schema.json). The backend never emits
// per-field validation errors (research.md R5) — fieldErrors is populated by callers that know
// which status is unambiguous for their form, not derived here.

export type FieldErrors = Record<string, string>;

export interface ApiErrorInit {
  status: number;
  title: string;
  detail?: string;
  correlationId?: string;
  retryAfterSeconds?: number;
  fieldErrors?: FieldErrors;
  message: string;
}

export class ApiError extends Error {
  readonly status: number;
  readonly title: string;
  readonly detail?: string;
  readonly correlationId?: string;
  readonly retryAfterSeconds?: number;
  readonly fieldErrors: FieldErrors;

  constructor(init: ApiErrorInit) {
    super(init.message);
    this.name = "ApiError";
    this.status = init.status;
    this.title = init.title;
    this.detail = init.detail;
    this.correlationId = init.correlationId;
    this.retryAfterSeconds = init.retryAfterSeconds;
    this.fieldErrors = init.fieldErrors ?? {};
  }

  /** A `fetch` that never reached the network at all — no correlation id exists for it. */
  static fromNetworkFailure(_cause: unknown): ApiError {
    return new ApiError({
      status: 0,
      title: "Network error",
      message: "We could not reach LearnHub — check your connection and try again.",
    });
  }

  /** Returns a copy with fieldErrors attached, for the handful of forms that can map one. */
  withFieldErrors(fieldErrors: FieldErrors): ApiError {
    return new ApiError({
      status: this.status,
      title: this.title,
      detail: this.detail,
      correlationId: this.correlationId,
      retryAfterSeconds: this.retryAfterSeconds,
      fieldErrors,
      message: this.message,
    });
  }
}

function friendlyMessage(status: number, retryAfterSeconds: number | undefined): string {
  if (status === 429) {
    return retryAfterSeconds != null
      ? `Too many attempts — try again in ${retryAfterSeconds} seconds.`
      : "Too many attempts — please wait a moment and try again.";
  }
  if (status === 503) {
    return "LearnHub is temporarily unavailable — please try again shortly.";
  }
  if (status === 0) {
    return "We could not reach LearnHub — check your connection and try again.";
  }
  return "Something went wrong. Please try again.";
}

function readRetryAfterSeconds(response: Response): number | undefined {
  const header = response.headers.get("Retry-After");
  if (!header) {
    return undefined;
  }
  const seconds = Number(header);
  return Number.isFinite(seconds) ? seconds : undefined;
}

/** Parses a failed Response (any status outside 2xx) into an ApiError. Tolerates a non-JSON body. */
export async function parseProblemResponse(response: Response): Promise<ApiError> {
  const retryAfterSeconds = readRetryAfterSeconds(response);

  let title = response.statusText || "Error";
  let detail: string | undefined;
  let correlationId: string | undefined;

  try {
    const body: unknown = await response.json();
    if (body && typeof body === "object") {
      const problem = body as Record<string, unknown>;
      if (typeof problem.title === "string") {
        title = problem.title;
      }
      if (typeof problem.detail === "string") {
        detail = problem.detail;
      }
      if (typeof problem.correlationId === "string") {
        correlationId = problem.correlationId;
      }
    }
  } catch {
    // Non-JSON or empty body — fall back to the status line and no detail/correlationId.
  }

  return new ApiError({
    status: response.status,
    title,
    detail,
    correlationId,
    retryAfterSeconds,
    message: friendlyMessage(response.status, retryAfterSeconds),
  });
}
