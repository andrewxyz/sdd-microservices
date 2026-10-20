import type { ReactNode } from "react";
import { Navigate, useLocation } from "react-router";
import Loading from "../components/Loading";
import { useAuth } from "./useAuth";

/** Redirects a signed-out visitor to /login?returnTo=…; renders Loading while restoring rather
 * than flashing the login page (FR-017, data-model.md). */
export default function RequireAuth({ children }: { children: ReactNode }) {
  const { status } = useAuth();
  const location = useLocation();

  if (status === "restoring") {
    return <Loading label="Checking your session…" />;
  }

  if (status === "anonymous") {
    const returnTo = encodeURIComponent(location.pathname + location.search);
    return <Navigate to={`/login?returnTo=${returnTo}`} replace />;
  }

  return <>{children}</>;
}
