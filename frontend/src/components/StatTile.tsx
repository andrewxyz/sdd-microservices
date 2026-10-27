/** One headline figure: a label above a large value. Rendered inside a <dl> by the dashboard pages. */
export default function StatTile({ label, value }: { label: string; value: string }) {
  return (
    <div className="bg-white border border-slate-200 rounded-xl px-4 py-3 shadow-sm">
      <dt className="text-sm text-slate-500">{label}</dt>
      <dd className="text-2xl font-semibold text-slate-900 mt-1">{value}</dd>
    </div>
  );
}
