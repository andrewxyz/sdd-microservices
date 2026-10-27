import { fireEvent, render, screen } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router";
import { describe, expect, it } from "vitest";
import NotAllowedPage from "./NotAllowedPage";

describe("NotAllowedPage", () => {
  it("says plainly that the page is not available to the signed-in user", () => {
    render(
      <MemoryRouter>
        <NotAllowedPage />
      </MemoryRouter>,
    );

    expect(screen.getByRole("heading", { name: /not allowed/i })).toBeTruthy();
    expect(screen.getByText(/instructors and administrators/i)).toBeTruthy();
  });

  it("offers a way back to the catalog", () => {
    render(
      <MemoryRouter initialEntries={["/instructor"]}>
        <Routes>
          <Route path="/instructor" element={<NotAllowedPage />} />
          <Route path="/" element={<p>Catalog page</p>} />
        </Routes>
      </MemoryRouter>,
    );

    fireEvent.click(screen.getByRole("link", { name: /back to the catalog/i }));

    expect(screen.getByText("Catalog page")).toBeTruthy();
  });
});
