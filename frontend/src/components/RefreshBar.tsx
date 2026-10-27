/** "HH:MM:SS UTC" for an ISO timestamp, so the same instant reads the same for every viewer. */
function utcTime(iso: string): string {
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) {
    return "unknown time";
  }
  return `${date.toISOString().slice(11, 19)} UTC`;
}

/** When the figures were last fetched, and a button to fetch them again (spec 009 FR-013). No polling. */
export default function RefreshBar({
  generatedAt,
  onRefresh,
  busy,
}: {
  generatedAt: string | null;
  onRefresh: () => void;
  busy: boolean;
}) {
  return (
    <div className="flex items-center gap-3 text-sm text-ink-muted mb-4">
      {generatedAt && <span>Last updated {utcTime(generatedAt)}</span>}
      <button
        type="button"
        onClick={onRefresh}
        disabled={busy}
        className="px-3 py-1 rounded-full shadow-card bg-surface text-ink-body disabled:opacity-50 cursor-pointer"
      >
        {busy ? "Refreshing…" : "Refresh"}
      </button>
    </div>
  );
}
