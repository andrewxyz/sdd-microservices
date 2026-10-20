import type { ApiError } from "../api/problem";

/** Renders an ApiError's friendly message plus its correlationId as the user's reference code (FR-040). */
export default function ErrorNotice({ error }: { error: ApiError }) {
  return (
    <div role="alert" className="error-notice">
      <p>{error.message}</p>
      {error.correlationId && (
        <p className="error-notice__reference">
          Reference code: <code>{error.correlationId}</code>
        </p>
      )}
    </div>
  );
}
