interface FunnelProps {
  enrolled: number;
  completed: number;
  certificateIssued: number;
}

/** A bar's width as a share of the enrolled count, clamped to 0-100%; 0% when nobody has enrolled. */
function share(count: number, enrolled: number): string {
  if (enrolled <= 0) {
    return "0%";
  }
  return `${Math.min(100, Math.max(0, (count / enrolled) * 100))}%`;
}

/** The enrolled -> completed -> certificate funnel, drawn with plain CSS bars (no chart library). */
export default function FunnelBars({ enrolled, completed, certificateIssued }: FunnelProps) {
  const stages = [
    { label: "Enrolled", count: enrolled, color: "bg-brand-graphic" },
    { label: "Completed", count: completed, color: "bg-brand-accent" },
    { label: "Certificate issued", count: certificateIssued, color: "bg-success-solid" },
  ];
  return (
    <ul className="space-y-3">
      {stages.map((stage) => (
        <li key={stage.label}>
          <div className="flex justify-between text-sm text-ink-body mb-1">
            <span>{stage.label}</span>
            <span className="font-semibold">{stage.count}</span>
          </div>
          <div className="h-3 bg-surface-sunken shadow-inset rounded-full overflow-hidden">
            <div data-bar className={`h-3 rounded-full ${stage.color}`} style={{ width: share(stage.count, enrolled) }} />
          </div>
        </li>
      ))}
    </ul>
  );
}
