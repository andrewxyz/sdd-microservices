import { useState, type FormEvent } from "react";
import { useNavigate } from "react-router";
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
    <div className="page">
      <h1>Create your account</h1>
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
          <p role="alert" className="error-notice">
            {formMessage}
          </p>
        )}
        <button type="submit" disabled={submitting}>
          {submitting ? "Creating account…" : "Create account"}
        </button>
      </form>
    </div>
  );
}
