import type { ApiError } from "../api/problem";

/** Renders an ApiError's friendly message plus its correlationId as the user's reference code (FR-040). */
export default function ErrorNotice({ error }: { error: ApiError }) {
  return (
    <div role="alert" className="border border-danger-line bg-danger-soft text-danger-ink rounded-xl px-4 py-3 my-2">
      <p className="font-medium">{error.message}</p>
      {error.correlationId && (
        <p className="text-sm opacity-80 mt-1">
          Reference code: <code className="font-mono bg-danger-soft-strong px-1 rounded-xl">{error.correlationId}</code>
        </p>
      )}
    </div>
  );
}
