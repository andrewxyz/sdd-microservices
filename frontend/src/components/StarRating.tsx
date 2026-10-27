/** Read-only star display: average and count, or "No reviews yet" when nothing has been reviewed. */
export default function StarRating({ average, count }: { average: number | null | undefined; count: number }) {
  if (count === 0 || average === null || average === undefined) {
    return <p className="text-xs text-ink-muted">No reviews yet</p>;
  }
  const label = `Rated ${average.toFixed(1)} out of 5 from ${count} ${count === 1 ? "review" : "reviews"}`;
  const filled = Math.round(average);
  return (
    <p className="flex items-center gap-1.5 text-sm" aria-label={label}>
      <span aria-hidden="true" className="text-star tracking-tight">
        {"★".repeat(filled)}
        <span className="text-ink-faint">{"★".repeat(5 - filled)}</span>
      </span>
      <span aria-hidden="true" className="font-semibold text-ink-body">
        {average.toFixed(1)}
      </span>
      <span aria-hidden="true" className="text-ink-muted">
        ({count})
      </span>
    </p>
  );
}
