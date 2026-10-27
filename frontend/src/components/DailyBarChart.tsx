import { useState } from "react";

export interface DailyPoint {
  day: string;
  enrollments: number;
}

const BAR_WIDTH = 10;
const BAR_GAP = 2;
const CHART_HEIGHT = 100;

/**
 * Enrollments per day as an inline SVG bar chart (no chart library), with the same numbers as a table.
 * The table is always in the page for keyboard and screen-reader users and is only hidden from sight
 * (Tailwind's `sr-only`) until the "Show data table" button reveals it.
 */
export default function DailyBarChart({ series }: { series: DailyPoint[] }) {
  const [showTable, setShowTable] = useState(false);
  const max = Math.max(1, ...series.map((point) => point.enrollments));
  const width = Math.max(1, series.length) * (BAR_WIDTH + BAR_GAP);

  return (
    <div>
      <svg
        role="img"
        aria-label={`Enrollments per day for the last ${series.length} days`}
        viewBox={`0 0 ${width} ${CHART_HEIGHT}`}
        preserveAspectRatio="none"
        className="w-full h-40 bg-surface-muted shadow-inset rounded-xl"
      >
        {series.map((point, index) => {
          const height = (point.enrollments / max) * (CHART_HEIGHT - 4);
          return (
            <rect
              key={point.day}
              x={index * (BAR_WIDTH + BAR_GAP) + BAR_GAP / 2}
              y={CHART_HEIGHT - height}
              width={BAR_WIDTH}
              height={height}
              className="fill-brand-graphic"
            />
          );
        })}
      </svg>
      {series.length > 0 && (
        <div className="flex justify-between text-xs text-ink-muted mt-1" aria-hidden="true">
          <span>{series[0]!.day}</span>
          <span>{series[series.length - 1]!.day}</span>
        </div>
      )}

      <button
        type="button"
        aria-expanded={showTable}
        onClick={() => setShowTable((current) => !current)}
        className="mt-3 text-sm text-brand-ink hover:underline cursor-pointer"
      >
        {showTable ? "Hide data table" : "Show data table"}
      </button>
      <div
        className={showTable ? "mt-2 overflow-x-auto rounded-2xl shadow-card" : "sr-only"}
        {...(showTable ? { role: "region", "aria-label": "Enrollments per day table", tabIndex: 0 } : {})}
      >
        <table className="w-full text-left text-sm bg-surface">
          <caption className="text-left font-medium text-ink-body py-1">Enrollments per day</caption>
          <thead className="bg-surface-muted text-ink-muted">
            <tr>
              <th scope="col" className="px-3 py-1">Day (UTC)</th>
              <th scope="col" className="px-3 py-1">Enrollments</th>
            </tr>
          </thead>
          <tbody>
            {series.map((point) => (
              <tr key={point.day} className="border-t border-line">
                <td className="px-3 py-1">{point.day}</td>
                <td className="px-3 py-1">{point.enrollments}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}
