import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter } from "react-router";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { AuthContext, type AuthContextValue, type AuthStatus } from "../auth/useAuth";
import ReviewForm from "./ReviewForm";

function jsonResponse(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
}

function renderForm(status: AuthStatus, props: { existing?: Parameters<typeof ReviewForm>[0]["existing"] } = {}) {
  const onChanged = vi.fn();
  const auth: AuthContextValue = {
    status,
    user: null,
    login: vi.fn(),
    register: vi.fn(),
    logout: vi.fn(),
  };
  render(
    <MemoryRouter>
      <AuthContext.Provider value={auth}>
        <ReviewForm courseId="c-1" existing={props.existing} onChanged={onChanged} />
      </AuthContext.Provider>
    </MemoryRouter>,
  );
  return { onChanged };
}

function lastCall(): { url: string; init: RequestInit } {
  const fetchMock = fetch as unknown as ReturnType<typeof vi.fn>;
  const call = fetchMock.mock.calls.at(-1) as [string, RequestInit];
  return { url: String(call[0]), init: call[1] };
}

function fillAndSubmit(rating: string, comment: string) {
  fireEvent.change(screen.getByLabelText("Rating (1-5)"), { target: { value: rating } });
  fireEvent.change(screen.getByLabelText("Comment (optional)"), { target: { value: comment } });
  fireEvent.click(screen.getByRole("button", { name: /submit review|save changes/i }));
}

describe("ReviewForm", () => {
  beforeEach(() => {
    vi.stubGlobal("fetch", vi.fn(async () => jsonResponse(201, { id: "r-1" })));
  });

  it("offers a sign-in prompt instead of the form when signed out", () => {
    renderForm("anonymous");

    expect(screen.queryByLabelText("Rating (1-5)")).toBeNull();
    expect(screen.getByRole("link", { name: /sign in/i })).not.toBeNull();
  });

  it("creates a review and tells the page to refresh", async () => {
    const { onChanged } = renderForm("authenticated");

    fillAndSubmit("4", "Solid course");

    await waitFor(() => expect(onChanged).toHaveBeenCalledTimes(1));
    const { url, init } = lastCall();
    expect(url).toBe("/api/v1/reviews/courses/c-1");
    expect(init.method).toBe("POST");
    expect(JSON.parse(String(init.body))).toEqual({ rating: 4, comment: "Solid course" });
  });

  it("pre-fills an existing review, saves edits with PUT and deletes with DELETE", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => jsonResponse(200, { id: "r-9" })));
    const { onChanged } = renderForm("authenticated", { existing: { id: "r-9", rating: 2, comment: "Meh" } });

    expect((screen.getByLabelText("Rating (1-5)") as HTMLInputElement).value).toBe("2");
    expect((screen.getByLabelText("Comment (optional)") as HTMLTextAreaElement).value).toBe("Meh");

    fillAndSubmit("5", "Better now");
    await waitFor(() => expect(onChanged).toHaveBeenCalledTimes(1));
    expect(lastCall().url).toBe("/api/v1/reviews/r-9");
    expect(lastCall().init.method).toBe("PUT");

    vi.stubGlobal("fetch", vi.fn(async () => new Response(null, { status: 204 })));
    fireEvent.click(screen.getByRole("button", { name: /delete/i }));
    await waitFor(() => expect(onChanged).toHaveBeenCalledTimes(2));
    expect(lastCall().url).toBe("/api/v1/reviews/r-9");
    expect(lastCall().init.method).toBe("DELETE");
  });

  it("shows the refusal with its reference code on a 403 and saves nothing", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(async () =>
        jsonResponse(403, { title: "Forbidden", status: 403, detail: "You must be enrolled to review.", correlationId: "corr-403" }),
      ),
    );
    const { onChanged } = renderForm("authenticated");

    fillAndSubmit("5", "Great");

    await waitFor(() => expect(screen.getByRole("alert")).not.toBeNull());
    expect(screen.getByRole("alert").textContent).toContain("You must be enrolled to review.");
    expect(screen.getByRole("alert").textContent).toContain("corr-403");
    expect(onChanged).not.toHaveBeenCalled();
  });

  it("shows the service's message on a 400 and keeps the typed text", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(async () =>
        jsonResponse(400, { title: "Bad Request", status: 400, detail: "Comment must be at most 500 characters.", correlationId: "corr-400" }),
      ),
    );
    renderForm("authenticated");

    fillAndSubmit("3", "my typed words");

    await waitFor(() => expect(screen.getByRole("alert").textContent).toContain("Comment must be at most 500 characters."));
    expect(screen.getByRole("alert").textContent).toContain("corr-400");
    expect((screen.getByLabelText("Comment (optional)") as HTMLTextAreaElement).value).toBe("my typed words");
  });

  it("limits the comment to 500 characters", () => {
    renderForm("authenticated");

    expect((screen.getByLabelText("Comment (optional)") as HTMLTextAreaElement).maxLength).toBe(500);
  });
});
