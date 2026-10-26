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
    <div className="min-h-screen bg-gradient-to-br from-slate-50 to-indigo-50 flex items-center justify-center px-4 py-12">
      <div className="bg-white rounded-2xl shadow-xl p-8 w-full max-w-md">
        <div className="text-center mb-8">
          <h1 className="text-2xl font-bold text-slate-800">Welcome back</h1>
          <p className="text-slate-500 text-sm mt-1">Sign in to your LearnHub account</p>
        </div>

        {accountCreated && (
          <div className="bg-emerald-50 border border-emerald-200 rounded-lg px-4 py-3 mb-6 text-emerald-800 text-sm text-center">
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
            <p role="alert" className="border border-red-300 bg-red-50 text-red-700 rounded-lg px-4 py-2.5 text-sm mb-4">
              {formMessage}
            </p>
          )}
          <button
            type="submit"
            disabled={submitting}
            className="w-full bg-indigo-600 hover:bg-indigo-700 disabled:opacity-60 disabled:cursor-not-allowed text-white font-semibold py-2.5 rounded-lg transition-colors cursor-pointer"
          >
            {submitting ? "Signing in…" : "Sign in"}
          </button>
        </form>

        <p className="text-center text-sm text-slate-500 mt-6">
          Don't have an account?{" "}
          <Link to="/register" className="text-indigo-600 font-medium hover:text-indigo-800 transition-colors">
            Create one
          </Link>
        </p>
      </div>
    </div>
  );
}
