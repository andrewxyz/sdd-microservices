import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import ReviewList from "./ReviewList";

const reviews = [
  { id: "r-1", userId: "u-1", rating: 5, comment: "Loved it", createdAt: "2026-10-01T10:00:00Z" },
  { id: "r-2", userId: "u-2", rating: 3, comment: null, createdAt: "2026-09-01T10:00:00Z" },
];

describe("ReviewList", () => {
  it("shows each review's rating and comment", () => {
    render(<ReviewList reviews={reviews} hasMore={false} onShowMore={() => {}} />);

    expect(screen.getByText("Loved it")).not.toBeNull();
    expect(screen.getByLabelText("Rated 5 out of 5")).not.toBeNull();
    expect(screen.getByLabelText("Rated 3 out of 5")).not.toBeNull();
    expect(screen.queryByRole("button", { name: /show more/i })).toBeNull();
  });

  it("says so when there are no reviews", () => {
    render(<ReviewList reviews={[]} hasMore={false} onShowMore={() => {}} />);

    expect(screen.getByText(/no reviews yet/i)).not.toBeNull();
  });

  it("offers 'Show more' when another page exists and calls back on click", () => {
    const onShowMore = vi.fn();
    render(<ReviewList reviews={reviews} hasMore onShowMore={onShowMore} />);

    fireEvent.click(screen.getByRole("button", { name: /show more/i }));

    expect(onShowMore).toHaveBeenCalledTimes(1);
  });

  it("renders a comment containing markup as plain text", () => {
    const hostile = [{ id: "r-3", userId: "u-3", rating: 4, comment: "<b>bold</b>", createdAt: "2026-10-01T10:00:00Z" }];
    const { container } = render(<ReviewList reviews={hostile} hasMore={false} onShowMore={() => {}} />);

    expect(screen.getByText("<b>bold</b>")).not.toBeNull();
    expect(container.querySelector("b")).toBeNull();
  });
});
