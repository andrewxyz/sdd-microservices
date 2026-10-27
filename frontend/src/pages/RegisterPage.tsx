import { useState, type FormEvent } from "react";
import { Link, useNavigate } from "react-router";
import Field from "../components/Field";
import { ApiError } from "../api/problem";
import { useAuth } from "../auth/useAuth";

export default function RegisterPage() {
  const { register } = useAuth();
  const navigate = useNavigate();
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [emailMessage, setEmailMessage] = useState<string | null>(null);
  const [passwordMessage, setPasswordMessage] = useState<string | null>(null);
  const [formMessage, setFormMessage] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  async function onSubmit(event: FormEvent) {
    event.preventDefault();
    setSubmitting(true);
    setEmailMessage(null);
    setPasswordMessage(null);
    setFormMessage(null);
    try {
      const result = await register(email, password);
      if (result.accountCreatedButLoginFailed) {
        navigate("/login?accountCreated=1", { replace: true });
        return;
      }
      navigate("/", { replace: true });
    } catch (error) {
      if (error instanceof ApiError && error.status === 409) {
        setEmailMessage("That email is already registered.");
      } else if (error instanceof ApiError && error.status === 422) {
        setPasswordMessage("Choose a longer password.");
      } else if (error instanceof ApiError) {
        setFormMessage(error.message);
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
          <h1 className="text-2xl font-bold text-ink">Create your account</h1>
          <p className="text-ink-muted text-sm mt-1">Join LearnHub and start learning today</p>
        </div>

        <form onSubmit={onSubmit} noValidate>
          <Field
            label="Email"
            name="email"
            type="email"
            autoComplete="email"
            required
            value={email}
            onChange={(e) => setEmail(e.target.value)}
            message={emailMessage ?? undefined}
          />
          <Field
            label="Password"
            name="password"
            type="password"
            autoComplete="new-password"
            required
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            message={passwordMessage ?? undefined}
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
            {submitting ? "Creating account…" : "Create account"}
          </button>
        </form>

        <p className="text-center text-sm text-ink-muted mt-6">
          Already have an account?{" "}
          <Link to="/login" className="text-brand-ink font-medium hover:text-brand-ink transition-colors">
            Sign in
          </Link>
        </p>
      </div>
    </div>
  );
}
