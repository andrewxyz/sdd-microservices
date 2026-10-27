import type { ProgressView } from "../api/endpoints";

/** The bar plus completedLessons/totalLessons and the percentage (FR-025, FR-028). */
export default function ProgressBar({ progress }: { progress: ProgressView }) {
  const percent = Math.round(progress.percentComplete ?? 0);
  return (
    <div role="progressbar" aria-label="Course progress" aria-valuenow={percent} aria-valuemin={0} aria-valuemax={100}>
      <div className="h-2.5 bg-surface-sunken shadow-inset rounded-full overflow-hidden">
        <div
          className="h-full bg-brand-graphic rounded-xl transition-all duration-500"
          style={{ width: `${percent}%` }}
        />
      </div>
      <p className="text-xs text-ink-muted mt-1">
        {progress.completedLessons ?? 0} / {progress.totalLessons ?? 0} lessons ({percent}%)
      </p>
    </div>
  );
}
