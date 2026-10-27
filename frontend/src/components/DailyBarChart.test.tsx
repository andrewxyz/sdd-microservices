import { fireEvent, render, screen, within } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import DailyBarChart from "./DailyBarChart";

const SERIES = [
  { day: "2026-03-13", enrollments: 0 },
  { day: "2026-03-14", enrollments: 3 },
  { day: "2026-03-15", enrollments: 1 },
];

describe("DailyBarChart", () => {
  it("draws one bar per day, including days with no enrollments", () => {
    const { container } = render(<DailyBarChart series={SERIES} />);

    expect(container.querySelectorAll("svg rect")).toHaveLength(3);
  });

  it("describes the chart to assistive technology", () => {
    render(<DailyBarChart series={SERIES} />);

    const chart = screen.getByRole("img");
    expect(chart.getAttribute("aria-label")).toMatch(/enrollments per day/i);
    expect(chart.getAttribute("aria-label")).toContain("3 days");
  });

  it("always has the same numbers as a table, hidden from sight until it is toggled on", () => {
    render(<DailyBarChart series={SERIES} />);

    const table = screen.getByRole("table");
    const rows = within(table).getAllByRole("row").slice(1);
    expect(rows.map((row) => within(row).getAllByRole("cell").map((c) => c.textContent))).toEqual([
      ["2026-03-13", "0"],
      ["2026-03-14", "3"],
      ["2026-03-15", "1"],
    ]);
    const wrapper = table.parentElement as HTMLElement;
    expect(wrapper.className).toContain("sr-only");

    const toggle = screen.getByRole("button", { name: /show data table/i });
    expect(toggle.getAttribute("aria-expanded")).toBe("false");
    fireEvent.click(toggle);

    expect(wrapper.className).not.toContain("sr-only");
    expect(screen.getByRole("button", { name: /hide data table/i }).getAttribute("aria-expanded")).toBe("true");
  });

  it("gives the table a caption so it is announced with a name", () => {
    render(<DailyBarChart series={SERIES} />);

    expect(screen.getByRole("table").querySelector("caption")?.textContent).toMatch(/enrollments per day/i);
  });

  it("copes with a series where every day is zero", () => {
    const { container } = render(
      <DailyBarChart series={SERIES.map((entry) => ({ ...entry, enrollments: 0 }))} />,
    );

    expect(container.querySelectorAll("svg rect")).toHaveLength(3);
  });
});
