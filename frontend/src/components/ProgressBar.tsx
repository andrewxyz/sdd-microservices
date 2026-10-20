import type { ProgressView } from "../api/endpoints";

/** The bar plus completedLessons/totalLessons and the percentage (FR-025, FR-028). */
export default function ProgressBar({ progress }: { progress: ProgressView }) {
  const percent = Math.round(progress.percentComplete ?? 0);
  return (
    <div className="progress-bar" role="progressbar" aria-valuenow={percent} aria-valuemin={0} aria-valuemax={100}>
      <div className="progress-bar__track">
        <div className="progress-bar__fill" style={{ width: `${percent}%` }} />
      </div>
      <p className="progress-bar__label">
        {progress.completedLessons ?? 0} / {progress.totalLessons ?? 0} lessons ({percent}%)
      </p>
    </div>
  );
}
