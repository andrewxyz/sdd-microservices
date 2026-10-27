/** A minimal loading placeholder used while a request is in flight (FR-043). */
export default function Loading({ label = "Loading…" }: { label?: string }) {
  return (
    <p role="status" className="text-ink-muted py-4 animate-pulse text-center">
      {label}
    </p>
  );
}
