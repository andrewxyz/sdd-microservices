import { useState, type FormEvent } from "react";
import { Link, useLocation, useNavigate } from "react-router";
import Field from "../components/Field";
import { ApiError } from "../api/problem";
import { useAuth } from "../auth/useAuth";

export default function LoginPage() {
  const { login } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [formMessage, setFormMessage] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  const params = new URLSearchParams(location.search);
  const returnTo = params.get("returnTo") ?? "/";
  const accountCreated = params.get("accountCreated") === "1";

  async function onSubmit(event: FormEvent) {
    event.preventDefault();
    if (email.trim() === "" || password === "") {
      setFormMessage("Enter your email and password.");
      return;
    }
    setSubmitting(true);
    setFormMessage(null);
    try {
      await login(email, password);
      navigate(returnTo, { replace: true });
    } catch (error) {
      if (error instanceof ApiError) {
        if (error.status === 401) {
          setFormMessage("Wrong email or password.");
        } else if (error.status === 429) {
          setFormMessage(error.message);
        } else {
          setFormMessage(error.message);
        }
      } else {
        setFormMessage("Something went wrong. Please try again.");
      }
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div className="min-h-screen bg-canvas flex items-center justify-center px-4 py-12">
      <div className="bg-surface rounded-2xl shadow-card p-8 w-full max-w-md">
        <div className="text-center mb-8">
          <h1 className="text-2xl font-bold text-ink">Welcome back</h1>
          <p className="text-ink-muted text-sm mt-1">Sign in to your LearnHub account</p>
        </div>

        {accountCreated && (
          <div className="bg-success-soft border border-success-line rounded-xl px-4 py-3 mb-6 text-success-ink text-sm text-center">
            Your account was created — sign in to continue.
          </div>
        )}

        <form onSubmit={onSubmit} noValidate>
          <Field
            label="Email"
            name="email"
            type="email"
            autoComplete="email"
            required
            value={email}
            onChange={(e) => setEmail(e.target.value)}
          />
          <Field
            label="Password"
            name="password"
            type="password"
            autoComplete="current-password"
            required
            value={password}
            onChange={(e) => setPassword(e.target.value)}
          />
          {formMessage && (
            <p role="alert" className="border border-danger-line bg-danger-soft text-danger-ink rounded-xl px-4 py-2.5 text-sm mb-4">
              {formMessage}
            </p>
          )}
          <button
            type="submit"
            disabled={submitting}
            className="w-full bg-brand hover:bg-brand-strong shadow-card active:shadow-inset disabled:opacity-60 disabled:cursor-not-allowed text-on-brand font-semibold py-2.5 rounded-xl transition-colors cursor-pointer"
          >
            {submitting ? "Signing in…" : "Sign in"}
          </button>
        </form>

        <p className="text-center text-sm text-ink-muted mt-6">
          Don't have an account?{" "}
          <Link to="/register" className="text-brand-ink font-medium hover:text-brand-ink transition-colors">
            Create one
          </Link>
        </p>
      </div>
    </div>
  );
}
