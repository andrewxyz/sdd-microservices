import type { ApiError } from "../api/problem";

/** Renders an ApiError's friendly message plus its correlationId as the user's reference code (FR-040). */
export default function ErrorNotice({ error }: { error: ApiError }) {
  return (
    <div role="alert" className="border border-red-300 bg-red-50 text-red-700 rounded-lg px-4 py-3 my-2">
      <p className="font-medium">{error.message}</p>
      {error.correlationId && (
        <p className="text-sm opacity-80 mt-1">
          Reference code: <code className="font-mono bg-red-100 px-1 rounded">{error.correlationId}</code>
        </p>
      )}
    </div>
  );
}
