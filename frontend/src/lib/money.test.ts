import { describe, expect, it } from "vitest";
import { formatUsd } from "./money";

describe("formatUsd", () => {
  it("shows zero cents as $0.00", () => {
    expect(formatUsd(0)).toBe("$0.00");
  });

  it("turns whole cents into dollars and cents", () => {
    expect(formatUsd(1999)).toBe("$19.99");
    expect(formatUsd(5)).toBe("$0.05");
  });

  it("groups thousands", () => {
    expect(formatUsd(100000000)).toBe("$1,000,000.00");
  });

  it("does not drift when many amounts are added as cents first", () => {
    const cents = 10 * 1999; // ten sales of $19.99
    expect(formatUsd(cents)).toBe("$199.90");
  });
});
