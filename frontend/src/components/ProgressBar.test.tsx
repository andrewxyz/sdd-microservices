import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import ProgressBar from "./ProgressBar";

describe("ProgressBar", () => {
  it("has an accessible name and exposes the percentage", () => {
    render(<ProgressBar progress={{ completedLessons: 1, totalLessons: 4, percentComplete: 25 }} />);

    const bar = screen.getByRole("progressbar", { name: /course progress/i });
    expect(bar.getAttribute("aria-valuenow")).toBe("25");
  });
});
