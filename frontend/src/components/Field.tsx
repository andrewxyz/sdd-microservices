import type { InputHTMLAttributes } from "react";

/** Label, input, and the slot where a field-level message appears (FR-016). */
export default function Field({
  label,
  message,
  ...inputProps
}: { label: string; message?: string } & InputHTMLAttributes<HTMLInputElement>) {
  const id = inputProps.id ?? inputProps.name;
  return (
    <div className="flex flex-col gap-1 mb-4">
      <label htmlFor={id} className="text-sm font-medium text-slate-700">
        {label}
      </label>
      <input
        id={id}
        {...inputProps}
        aria-invalid={message ? true : undefined}
        className="border border-slate-300 rounded-lg px-3 py-2.5 text-sm focus:outline-none focus:ring-2 focus:ring-indigo-500 focus:border-transparent transition-shadow"
      />
      {message && <span className="text-sm text-red-600">{message}</span>}
    </div>
  );
}
