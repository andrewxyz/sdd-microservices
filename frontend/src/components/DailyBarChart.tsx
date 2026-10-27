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
        className="w-full h-40 bg-slate-50 rounded-lg"
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
              className="fill-indigo-500"
            />
          );
        })}
      </svg>
      {series.length > 0 && (
        <div className="flex justify-between text-xs text-slate-500 mt-1" aria-hidden="true">
          <span>{series[0]!.day}</span>
          <span>{series[series.length - 1]!.day}</span>
        </div>
      )}

      <button
        type="button"
        aria-expanded={showTable}
        onClick={() => setShowTable((current) => !current)}
        className="mt-3 text-sm text-indigo-700 hover:underline cursor-pointer"
      >
        {showTable ? "Hide data table" : "Show data table"}
      </button>
      <div className={showTable ? "mt-2 overflow-x-auto" : "sr-only"}>
        <table className="w-full text-left text-sm border border-slate-200 bg-white">
          <caption className="text-left font-medium text-slate-700 py-1">Enrollments per day</caption>
          <thead className="bg-slate-50 text-slate-600">
            <tr>
              <th scope="col" className="px-3 py-1">Day (UTC)</th>
              <th scope="col" className="px-3 py-1">Enrollments</th>
            </tr>
          </thead>
          <tbody>
            {series.map((point) => (
              <tr key={point.day} className="border-t border-slate-100">
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
