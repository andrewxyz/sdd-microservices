import { useEffect, useMemo, useState, type ReactNode } from "react";
import * as endpoints from "../api/endpoints";
import { setUnauthorizedHandler } from "../api/client";
import { clear, getRefreshToken, setTokens } from "./session";
import { AuthContext, type AuthStatus } from "./useAuth";
import type { MeView } from "../api/endpoints";

/**
 * Owns the restoring | authenticated | anonymous state machine (data-model.md). On mount, if a
 * refresh token survived the reload it is exchanged for a fresh pair before anything guarded
 * renders (research.md R4) — guarded routes show a loading state during `restoring` rather than a
 * flash of /login.
 */
export default function AuthProvider({
  onUnauthorized,
  children,
}: {
  onUnauthorized?: (returnTo: string) => void;
  children: ReactNode;
}) {
  const [status, setStatus] = useState<AuthStatus>("restoring");
  const [user, setUser] = useState<MeView | null>(null);

  useEffect(() => {
    let cancelled = false;

    async function restore() {
      const refreshToken = getRefreshToken();
      if (!refreshToken) {
        if (!cancelled) {
          setStatus("anonymous");
        }
        return;
      }
      try {
        const pair = await endpoints.refresh(refreshToken);
        if (!pair.accessToken || !pair.refreshToken) {
          throw new Error("refresh returned no tokens");
        }
        setTokens(pair.accessToken, pair.refreshToken);
        const profile = await endpoints.me();
        if (!cancelled) {
          setUser(profile);
          setStatus("authenticated");
        }
      } catch {
        clear();
        if (!cancelled) {
          setUser(null);
          setStatus("anonymous");
        }
      }
    }

    void restore();
    return () => {
      cancelled = true;
    };
  }, []);

  useEffect(() => {
    setUnauthorizedHandler((returnTo) => {
      setUser(null);
      setStatus("anonymous");
      onUnauthorized?.(returnTo);
    });
    return () => setUnauthorizedHandler(null);
  }, [onUnauthorized]);

  const value = useMemo(
    () => ({
      status,
      user,
      async login(email: string, password: string) {
        const pair = await endpoints.login(email, password);
        if (!pair.accessToken || !pair.refreshToken) {
          throw new Error("login returned no tokens");
        }
        setTokens(pair.accessToken, pair.refreshToken);
        const profile = await endpoints.me();
        setUser(profile);
        setStatus("authenticated");
      },
      async register(email: string, password: string) {
        await endpoints.register(email, password);
        try {
          const pair = await endpoints.login(email, password);
          if (!pair.accessToken || !pair.refreshToken) {
            throw new Error("login returned no tokens");
          }
          setTokens(pair.accessToken, pair.refreshToken);
          const profile = await endpoints.me();
          setUser(profile);
          setStatus("authenticated");
          return { accountCreatedButLoginFailed: false };
        } catch {
          return { accountCreatedButLoginFailed: true };
        }
      },
      async logout() {
        const refreshToken = getRefreshToken();
        try {
          if (refreshToken) {
            await endpoints.logout(refreshToken);
          }
        } catch {
          // Sign-out proceeds locally regardless of the server's response.
        } finally {
          clear();
          setUser(null);
          setStatus("anonymous");
        }
      },
    }),
    [status, user],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}
