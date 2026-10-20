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
    <header className="site-header">
      <Link to="/">LearnHub</Link>
      <nav>
        {status === "authenticated" && user ? (
          <>
            <Link to="/learn">My courses</Link>
            <Link to="/account">Account</Link>
            <span>{user.email}</span>
            <button type="button" onClick={onSignOut}>
              Sign out
            </button>
          </>
        ) : (
          <>
            <Link to="/login">Sign in</Link>
            <Link to="/register">Create account</Link>
          </>
        )}
      </nav>
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
