import { useState, type FormEvent } from "react";
import { useLocation, useNavigate } from "react-router";
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
    <div className="page">
      <h1>Sign in</h1>
      {accountCreated && <p>Your account was created — sign in to continue.</p>}
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
          <p role="alert" className="error-notice">
            {formMessage}
          </p>
        )}
        <button type="submit" disabled={submitting}>
          {submitting ? "Signing in…" : "Sign in"}
        </button>
      </form>
    </div>
  );
}
