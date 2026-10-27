import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import StarRating from "./StarRating";

describe("StarRating", () => {
  it("shows the average and the review count with an accessible label", () => {
    render(<StarRating average={4.5} count={12} />);

    expect(screen.getByLabelText("Rated 4.5 out of 5 from 12 reviews")).not.toBeNull();
    expect(screen.getByText("4.5")).not.toBeNull();
    expect(screen.getByText("(12)")).not.toBeNull();
  });

  it("uses the singular for one review", () => {
    render(<StarRating average={5} count={1} />);

    expect(screen.getByLabelText("Rated 5.0 out of 5 from 1 review")).not.toBeNull();
  });

  it("says 'No reviews yet' when the count is 0", () => {
    render(<StarRating average={null} count={0} />);

    expect(screen.getByText("No reviews yet")).not.toBeNull();
    expect(screen.queryByLabelText(/out of 5/)).toBeNull();
  });
});

describe("StarRating contrast", () => {
  it("shows the empty message in a colour dark enough to read", () => {
    render(<StarRating average={null} count={0} />);

    expect(screen.getByText("No reviews yet").className).toContain("text-ink-muted");
  });
});
