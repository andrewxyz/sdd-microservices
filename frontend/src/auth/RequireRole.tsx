import type { ReactNode } from "react";
import NotAllowedPage from "../pages/NotAllowedPage";
import RequireAuth from "./RequireAuth";
import { useAuth } from "./useAuth";

export type Role = "STUDENT" | "INSTRUCTOR" | "ADMINISTRATOR";

/** The roles that may open the instructor dashboard (also the roles the gateway lets through). */
export const DASHBOARD_ROLES: Role[] = ["INSTRUCTOR", "ADMINISTRATOR"];

/**
 * Signed-out visitors go to sign in (RequireAuth), and a signed-in user whose role is not listed sees the
 * not-allowed page. This is a convenience for the person using the app, not security: the gateway and the
 * service enforce the roles, so hiding a page here never protects any data (constitution XII, XIII).
 */
export default function RequireRole({ roles, children }: { roles: Role[]; children: ReactNode }) {
  const { user } = useAuth();
  return <RequireAuth>{user && roles.includes(user.role) ? children : <NotAllowedPage />}</RequireAuth>;
}
