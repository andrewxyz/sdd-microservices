import type { ReactNode } from "react";

/** A friendly empty result, never rendered as an error (FR-007). */
export default function EmptyState({ message, action }: { message: string; action?: ReactNode }) {
  return (
    <div className="text-center py-16 text-slate-500">
      <div className="text-5xl mb-4">📭</div>
      <p className="text-lg">{message}</p>
      {action && <div className="mt-4">{action}</div>}
    </div>
  );
}
