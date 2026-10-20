import { Link, Route, Routes, useNavigate } from "react-router";
import AuthProvider from "./auth/AuthProvider";
import { useAuth } from "./auth/useAuth";
import RequireAuth from "./auth/RequireAuth";
import AccountPage from "./pages/AccountPage";
import CatalogPage from "./pages/CatalogPage";
import CourseDetailPage from "./pages/CourseDetailPage";
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
    <header className="bg-gradient-to-r from-indigo-700 via-purple-700 to-indigo-800 shadow-lg sticky top-0 z-50">
      <div className="max-w-6xl mx-auto px-4 py-3 flex items-center justify-between gap-4 flex-wrap">
        <Link
          to="/"
          className="text-2xl font-bold text-white tracking-tight hover:text-indigo-200 transition-colors"
        >
          LearnHub
        </Link>
        <nav className="flex gap-4 flex-wrap items-center">
          {status === "authenticated" && user ? (
            <>
              <Link to="/learn" className="text-indigo-200 hover:text-white transition-colors font-medium text-sm">
                My courses
              </Link>
              <Link to="/account" className="text-indigo-200 hover:text-white transition-colors font-medium text-sm">
                Account
              </Link>
              <span className="text-indigo-300 text-xs hidden sm:inline">{user.email}</span>
              <button
                type="button"
                onClick={onSignOut}
                className="bg-white/20 hover:bg-white/30 text-white px-4 py-1.5 rounded-full text-sm font-medium transition-colors cursor-pointer"
              >
                Sign out
              </button>
            </>
          ) : (
            <>
              <Link to="/login" className="text-indigo-200 hover:text-white transition-colors font-medium text-sm">
                Sign in
              </Link>
              <Link
                to="/register"
                className="bg-white text-indigo-700 px-4 py-1.5 rounded-full text-sm font-semibold hover:bg-indigo-50 transition-colors"
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
        <Route path="*" element={<NotFoundPage />} />
      </Routes>
    </AuthProvider>
  );
}

export default function App() {
  return <AppRoutes />;
}
