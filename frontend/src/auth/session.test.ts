import { beforeEach, describe, expect, it } from "vitest";
import { clear, getAccessToken, getRefreshToken, setTokens } from "./session";

describe("session", () => {
  beforeEach(() => {
    clear();
  });

  it("holds the access token in memory only, never in any storage", () => {
    setTokens("access-1", "refresh-1");

    expect(getAccessToken()).toBe("access-1");
    expect(sessionStorage.getItem("access-1")).toBeNull();
    expect(JSON.stringify(sessionStorage)).not.toContain("access-1");
    expect(JSON.stringify(localStorage)).not.toContain("access-1");
  });

  it("writes the refresh token to sessionStorage under learnhub.refreshToken", () => {
    setTokens("access-1", "refresh-1");

    expect(sessionStorage.getItem("learnhub.refreshToken")).toBe("refresh-1");
    expect(getRefreshToken()).toBe("refresh-1");
  });

  it("never writes anything to localStorage", () => {
    setTokens("access-1", "refresh-1");

    expect(localStorage.length).toBe(0);
  });

  it("clear() removes both the in-memory access token and the persisted refresh token", () => {
    setTokens("access-1", "refresh-1");

    clear();

    expect(getAccessToken()).toBeNull();
    expect(getRefreshToken()).toBeNull();
  });
});
