import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import FunnelBars from "./FunnelBars";

function widthOf(label: string): string {
  const item = screen.getByText(label).closest("li") as HTMLElement;
  return (item.querySelector("[data-bar]") as HTMLElement).style.width;
}

describe("FunnelBars", () => {
  it("shows the three stages with their counts", () => {
    render(<FunnelBars enrolled={10} completed={5} certificateIssued={2} />);

    expect(screen.getByText("Enrolled").closest("li")?.textContent).toContain("10");
    expect(screen.getByText("Completed").closest("li")?.textContent).toContain("5");
    expect(screen.getByText("Certificate issued").closest("li")?.textContent).toContain("2");
  });

  it("draws bar widths in proportion to the enrolled count", () => {
    render(<FunnelBars enrolled={10} completed={5} certificateIssued={2} />);

    expect(widthOf("Enrolled")).toBe("100%");
    expect(widthOf("Completed")).toBe("50%");
    expect(widthOf("Certificate issued")).toBe("20%");
  });

  it("draws empty bars, not broken ones, when nothing has happened", () => {
    render(<FunnelBars enrolled={0} completed={0} certificateIssued={0} />);

    expect(widthOf("Enrolled")).toBe("0%");
    expect(widthOf("Completed")).toBe("0%");
  });

  it("never draws a bar wider than the full width", () => {
    render(<FunnelBars enrolled={2} completed={5} certificateIssued={0} />);

    expect(widthOf("Completed")).toBe("100%");
  });
});
