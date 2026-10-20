import { useEffect, useState } from "react";
import { useNavigate } from "react-router";
import {
  listMyCertificates,
  listPayments,
  me,
  type CertificateView,
  type MeView,
  type PaymentView,
} from "../api/endpoints";
import { ApiError } from "../api/problem";
import { useAuth } from "../auth/useAuth";
import ErrorNotice from "../components/ErrorNotice";
import Loading from "../components/Loading";

function formatAmount(amountCents: number, currency: string): string {
  return new Intl.NumberFormat("en-US", { style: "currency", currency }).format(amountCents / 100);
}

export default function AccountPage() {
  const { logout } = useAuth();
  const navigate = useNavigate();

  const [profile, setProfile] = useState<MeView | null>(null);
  const [profileError, setProfileError] = useState<ApiError | null>(null);

  const [payments, setPayments] = useState<PaymentView[] | null>(null);
  const [paymentsError, setPaymentsError] = useState<ApiError | null>(null);

  const [certs, setCerts] = useState<CertificateView[] | null>(null);
  const [certsError, setCertsError] = useState<ApiError | null>(null);

  useEffect(() => {
    me()
      .then(setProfile)
      .catch((err: unknown) => {
        if (err instanceof ApiError) setProfileError(err);
        else setProfileError(ApiError.fromNetworkFailure(err));
      });

    listPayments()
      .then((page) => {
        const sorted = [...(page.items ?? [])].sort((a, b) => {
          const ta = a.createdAt ? new Date(a.createdAt).getTime() : 0;
          const tb = b.createdAt ? new Date(b.createdAt).getTime() : 0;
          return tb - ta;
        });
        setPayments(sorted);
      })
      .catch((err: unknown) => {
        if (err instanceof ApiError) setPaymentsError(err);
        else setPaymentsError(ApiError.fromNetworkFailure(err));
      });

    listMyCertificates()
      .then((page) => setCerts(page.items ?? []))
      .catch((err: unknown) => {
        if (err instanceof ApiError) setCertsError(err);
        else setCertsError(ApiError.fromNetworkFailure(err));
      });
  }, []);

  async function onSignOut() {
    await logout();
    navigate("/");
  }

  return (
    <main className="page">
      <h1>My account</h1>

      <section className="account-section">
        <h2>Profile</h2>
        {!profile && !profileError && <Loading />}
        {profileError && <ErrorNotice error={profileError} />}
        {profile && (
          <dl className="account-profile">
            <dt>Email</dt>
            <dd>{profile.email}</dd>
            <dt>Role</dt>
            <dd>{profile.role}</dd>
          </dl>
        )}
        <button type="button" onClick={onSignOut}>
          Sign out
        </button>
      </section>

      <section className="account-section">
        <h2>Payment history</h2>
        {!payments && !paymentsError && <Loading />}
        {paymentsError && <ErrorNotice error={paymentsError} />}
        {payments && payments.length === 0 && (
          <p className="empty-state">No payments yet.</p>
        )}
        {payments && payments.length > 0 && (
          <table className="payment-table">
            <thead>
              <tr>
                <th>Course</th>
                <th>Amount</th>
                <th>Status</th>
                <th>Date</th>
              </tr>
            </thead>
            <tbody>
              {payments.map((p, i) => (
                <tr key={p.id ?? i}>
                  <td>{p.courseId}</td>
                  <td>
                    {p.amountCents != null && p.currency
                      ? formatAmount(p.amountCents, p.currency)
                      : "—"}
                  </td>
                  <td>{p.status}</td>
                  <td>{p.createdAt}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </section>

      <section className="account-section">
        <h2>Certificates</h2>
        {!certs && !certsError && <Loading />}
        {certsError && <ErrorNotice error={certsError} />}
        {certs && certs.length === 0 && (
          <p className="empty-state">
            Your certificate is on its way — it usually arrives within a few minutes of completing a
            course.
          </p>
        )}
        {certs && certs.length > 0 && (
          <ul className="cert-list">
            {certs.map((cert, i) => (
              <li key={cert.verificationCode ?? i} className="cert-list__item">
                <strong>{cert.courseTitle}</strong>
                <span className="cert-list__date">{cert.issuedAt}</span>
                <span className="cert-list__code">
                  Code: <code>{cert.verificationCode}</code>
                </span>
                <button
                  type="button"
                  onClick={() => {
                    if (cert.verificationCode) {
                      void navigator.clipboard.writeText(cert.verificationCode);
                    }
                  }}
                >
                  Copy code
                </button>
              </li>
            ))}
          </ul>
        )}
      </section>
    </main>
  );
}
