import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import Field from "./Field";

describe("Field", () => {
  it("links the error message to its input so assistive technology reads it", () => {
    render(<Field label="Email" name="email" message="Enter your email." />);

    const input = screen.getByLabelText("Email");
    const message = screen.getByText("Enter your email.");
    expect(input.getAttribute("aria-invalid")).toBe("true");
    expect(input.getAttribute("aria-describedby")).toBe(message.id);
    expect(message.id).not.toBe("");
    expect(message.getAttribute("role")).toBe("alert");
  });

  it("has no description when there is no message", () => {
    render(<Field label="Email" name="email" />);

    expect(screen.getByLabelText("Email").getAttribute("aria-describedby")).toBeNull();
  });
});
