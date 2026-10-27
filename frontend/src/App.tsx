import { Link, Route, Routes, useNavigate } from "react-router";
import AuthProvider from "./auth/AuthProvider";
import { useAuth } from "./auth/useAuth";
import RequireAuth from "./auth/RequireAuth";
import RequireRole, { DASHBOARD_ROLES } from "./auth/RequireRole";
import AccountPage from "./pages/AccountPage";
import CatalogPage from "./pages/CatalogPage";
import CourseDetailPage from "./pages/CourseDetailPage";
import InstructorCoursePage from "./pages/InstructorCoursePage";
import InstructorOverviewPage from "./pages/InstructorOverviewPage";
import LearnPage from "./pages/LearnPage";
import LoginPage from "./pages/LoginPage";
import MyCoursesPage from "./pages/MyCoursesPage";
import NotFoundPage from "./pages/NotFoundPage";
import RegisterPage from "./pages/RegisterPage";
import VerifyPage from "./pages/VerifyPage";

function SiteHeader() {
  const { status, user, logout } = useAuth();
  const navigate = useNavigate();

  async function onSignOut() {
    await logout();
    navigate("/");
  }

  return (
    <header className="bg-hero shadow-card sticky top-0 z-50">
      <div className="max-w-6xl mx-auto px-4 py-3 flex items-center justify-between gap-4 flex-wrap">
        <Link
          to="/"
          className="text-2xl font-bold text-on-hero tracking-tight hover:text-on-hero-muted transition-colors"
        >
          LearnHub
        </Link>
        <nav className="flex gap-4 flex-wrap items-center">
          {status === "authenticated" && user ? (
            <>
              <Link to="/learn" className="text-on-hero-muted hover:text-on-hero transition-colors font-medium text-sm">
                My courses
              </Link>
              <Link to="/account" className="text-on-hero-muted hover:text-on-hero transition-colors font-medium text-sm">
                Account
              </Link>
              {DASHBOARD_ROLES.includes(user.role) && (
                <Link to="/instructor" className="text-on-hero-muted hover:text-on-hero transition-colors font-medium text-sm">
                  Dashboard
                </Link>
              )}
              <span className="text-on-hero-muted text-xs hidden sm:inline">{user.email}</span>
              <button
                type="button"
                onClick={onSignOut}
                className="shadow-card active:shadow-inset text-on-hero rounded-full px-4 py-1.5 text-sm font-medium transition-colors cursor-pointer"
              >
                Sign out
              </button>
            </>
          ) : (
            <>
              <Link to="/login" className="text-on-hero-muted hover:text-on-hero transition-colors font-medium text-sm">
                Sign in
              </Link>
              <Link
                to="/register"
                className="bg-brand text-on-brand rounded-full px-4 py-1.5 text-sm font-semibold hover:bg-brand-strong shadow-card active:shadow-inset transition-colors"
              >
                Create account
              </Link>
            </>
          )}
        </nav>
      </div>
    </header>
  );
}

function AppRoutes() {
  const navigate = useNavigate();

  return (
    <AuthProvider onUnauthorized={(returnTo) => navigate(`/login?returnTo=${encodeURIComponent(returnTo)}`)}>
      <SiteHeader />
      <Routes>
        <Route path="/" element={<CatalogPage />} />
        <Route path="/courses/:courseId" element={<CourseDetailPage />} />
        <Route path="/login" element={<LoginPage />} />
        <Route path="/register" element={<RegisterPage />} />
        <Route
          path="/learn"
          element={
            <RequireAuth>
              <MyCoursesPage />
            </RequireAuth>
          }
        />
        <Route
          path="/learn/:courseId"
          element={
            <RequireAuth>
              <LearnPage />
            </RequireAuth>
          }
        />
        <Route path="/verify" element={<VerifyPage />} />
        <Route path="/verify/:code" element={<VerifyPage />} />
        <Route
          path="/account"
          element={
            <RequireAuth>
              <AccountPage />
            </RequireAuth>
          }
        />
        <Route
          path="/instructor"
          element={
            <RequireRole roles={DASHBOARD_ROLES}>
              <InstructorOverviewPage />
            </RequireRole>
          }
        />
        <Route
          path="/instructor/courses/:courseId"
          element={
            <RequireRole roles={DASHBOARD_ROLES}>
              <InstructorCoursePage />
            </RequireRole>
          }
        />
        <Route path="*" element={<NotFoundPage />} />
      </Routes>
    </AuthProvider>
  );
}

export default function App() {
  return <AppRoutes />;
}
