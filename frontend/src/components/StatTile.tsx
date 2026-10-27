/** One headline figure: a label above a large value. Rendered inside a <dl> by the dashboard pages. */
export default function StatTile({ label, value }: { label: string; value: string }) {
  return (
    <div className="bg-surface shadow-card rounded-2xl px-4 py-3">
      <dt className="text-sm text-ink-muted">{label}</dt>
      <dd className="text-2xl font-semibold text-ink mt-1">{value}</dd>
    </div>
  );
}
