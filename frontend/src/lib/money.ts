const USD = new Intl.NumberFormat("en-US", { style: "currency", currency: "USD" });

/** Formats a whole number of cents as US dollars, e.g. 1999 -> "$19.99". Sums stay in cents until here. */
export function formatUsd(cents: number): string {
  return USD.format(cents / 100);
}
