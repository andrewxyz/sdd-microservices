import type { InputHTMLAttributes } from "react";

/** Label, input, and the slot where a field-level message appears (FR-016). */
export default function Field({
  label,
  message,
  ...inputProps
}: { label: string; message?: string } & InputHTMLAttributes<HTMLInputElement>) {
  const id = inputProps.id ?? inputProps.name;
  return (
    <div className="field">
      <label htmlFor={id}>{label}</label>
      <input id={id} {...inputProps} aria-invalid={message ? true : undefined} />
      {message && <span className="field__message">{message}</span>}
    </div>
  );
}
