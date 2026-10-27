import type { InputHTMLAttributes } from "react";

/** Label, input, and the slot where a field-level message appears (FR-016). */
export default function Field({
  label,
  message,
  ...inputProps
}: { label: string; message?: string } & InputHTMLAttributes<HTMLInputElement>) {
  const id = inputProps.id ?? inputProps.name;
  const messageId = message && id ? `${id}-message` : undefined;
  return (
    <div className="flex flex-col gap-1 mb-4">
      <label htmlFor={id} className="text-sm font-medium text-ink-body">
        {label}
      </label>
      <input
        id={id}
        {...inputProps}
        aria-invalid={message ? true : undefined}
        aria-describedby={messageId}
        className="border border-line-strong shadow-inset rounded-xl px-3 py-2.5 text-sm focus:outline-none focus:ring-2 focus:ring-focus focus:border-transparent transition-shadow"
      />
      {message && (
        <span id={messageId} role="alert" className="text-sm text-danger-ink">
          {message}
        </span>
      )}
    </div>
  );
}
