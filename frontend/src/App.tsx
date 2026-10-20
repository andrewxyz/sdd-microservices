import { Link, Route, Routes, useNavigate } from "react-router";
import AuthProvider from "./auth/AuthProvider";
import { useAuth } from "./auth/useAuth";
import CatalogPage from "./pages/CatalogPage";
import CourseDetailPage from "./pages/CourseDetailPage";
import LoginPage from "./pages/LoginPage";
import NotFoundPage from "./pages/NotFoundPage";
import RegisterPage from "./pages/RegisterPage";

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
        <Route path="*" element={<NotFoundPage />} />
      </Routes>
    </AuthProvider>
  );
}

export default function App() {
  return <AppRoutes />;
}
