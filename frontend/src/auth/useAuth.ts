import { createContext, useContext } from "react";
import type { MeView } from "../api/endpoints";

export type AuthStatus = "restoring" | "authenticated" | "anonymous";

export interface AuthContextValue {
  status: AuthStatus;
  user: MeView | null;
  login: (email: string, password: string) => Promise<void>;
  /**
   * Register then sign in (R0.1). Resolves normally when both succeed. If registration succeeds but
   * the follow-up sign-in fails, resolves with `{ accountCreatedButLoginFailed: true }` rather than
   * throwing, so the page can send the user to /login with a "your account was created" message
   * instead of lying that registration itself failed.
   */
  register: (email: string, password: string) => Promise<{ accountCreatedButLoginFailed: boolean }>;
  logout: () => Promise<void>;
}

export const AuthContext = createContext<AuthContextValue | null>(null);

export function useAuth(): AuthContextValue {
  const context = useContext(AuthContext);
  if (!context) {
    throw new Error("useAuth() must be used within an AuthProvider.");
  }
  return context;
}
