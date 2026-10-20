import type { ReactNode } from "react";

/** A friendly empty result, never rendered as an error (FR-007). */
export default function EmptyState({ message, action }: { message: string; action?: ReactNode }) {
  return (
    <div className="empty-state">
      <p>{message}</p>
      {action}
    </div>
  );
}
