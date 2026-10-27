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

const STATUS_COLORS: Record<string, string> = {
  SUCCEEDED: "bg-success-soft-strong text-success-ink",
  PENDING: "bg-warning-soft-strong text-warning-ink",
  DECLINED: "bg-danger-soft-strong text-danger-ink",
  FAILED: "bg-danger-soft-strong text-danger-ink",
};

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
    <div className="min-h-screen bg-canvas">
      {/* Header */}
      <div className="bg-hero text-on-hero py-10 px-4">
        <div className="max-w-3xl mx-auto">
          <h1 className="text-3xl font-bold">My account</h1>
          <p className="text-on-hero-muted mt-1 text-sm">Profile, payments &amp; certificates</p>
        </div>
      </div>

      <div className="max-w-3xl mx-auto px-4 py-8 flex flex-col gap-6">
        {/* Profile */}
        <section className="bg-surface rounded-2xl shadow-card overflow-hidden">
          <div className="px-6 py-4 bg-surface-muted border-b border-line flex items-center justify-between">
            <h2 className="text-lg font-semibold text-ink">Profile</h2>
          </div>
          <div className="px-6 py-5">
            {!profile && !profileError && <Loading />}
            {profileError && <ErrorNotice error={profileError} />}
            {profile && (
              <dl className="grid grid-cols-[auto_1fr] gap-x-6 gap-y-3 text-sm">
                <dt className="font-medium text-ink-muted">Email</dt>
                <dd className="text-ink">{profile.email}</dd>
                <dt className="font-medium text-ink-muted">Role</dt>
                <dd>
                  <span className="bg-brand-soft-strong text-brand-ink text-xs font-semibold px-2.5 py-0.5 rounded-full">
                    {profile.role}
                  </span>
                </dd>
              </dl>
            )}
            <button
              type="button"
              onClick={onSignOut}
              className="mt-5 bg-surface-muted hover:bg-danger-soft hover:text-danger-ink text-ink-body px-4 py-2 rounded-xl text-sm font-medium transition-colors cursor-pointer"
            >
              Sign out
            </button>
          </div>
        </section>

        {/* Payment history */}
        <section className="bg-surface rounded-2xl shadow-card overflow-hidden">
          <div className="px-6 py-4 bg-surface-muted border-b border-line">
            <h2 className="text-lg font-semibold text-ink">Payment history</h2>
          </div>
          <div className="px-6 py-5">
            {!payments && !paymentsError && <Loading />}
            {paymentsError && <ErrorNotice error={paymentsError} />}
            {payments && payments.length === 0 && (
              <p className="text-ink-muted text-sm">No payments yet.</p>
            )}
            {payments && payments.length > 0 && (
              <div className="overflow-x-auto -mx-6">
                <table className="w-full text-sm">
                  <thead>
                    <tr className="text-left text-ink-muted border-b border-line">
                      <th className="px-6 py-2 font-medium">Course</th>
                      <th className="px-6 py-2 font-medium">Amount</th>
                      <th className="px-6 py-2 font-medium">Status</th>
                      <th className="px-6 py-2 font-medium">Date</th>
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-line">
                    {payments.map((p, i) => (
                      <tr key={p.id ?? i} className="hover:bg-surface-muted transition-colors">
                        <td className="px-6 py-3 text-ink-body font-mono text-xs">{p.courseId}</td>
                        <td className="px-6 py-3 text-ink font-semibold">
                          {p.amountCents != null && p.currency ? formatAmount(p.amountCents, p.currency) : "—"}
                        </td>
                        <td className="px-6 py-3">
                          <span
                            className={`text-xs font-semibold px-2.5 py-0.5 rounded-full ${STATUS_COLORS[p.status ?? ""] ?? "bg-surface-muted text-ink-muted"}`}
                          >
                            {p.status}
                          </span>
                        </td>
                        <td className="px-6 py-3 text-ink-muted text-xs">{p.createdAt}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </div>
        </section>

        {/* Certificates */}
        <section className="bg-surface rounded-2xl shadow-card overflow-hidden">
          <div className="px-6 py-4 bg-surface-muted border-b border-line">
            <h2 className="text-lg font-semibold text-ink">Certificates</h2>
          </div>
          <div className="px-6 py-5">
            {!certs && !certsError && <Loading />}
            {certsError && <ErrorNotice error={certsError} />}
            {certs && certs.length === 0 && (
              <p className="text-ink-muted text-sm">
                Your certificate is on its way — it usually arrives within a few minutes of completing a course.
              </p>
            )}
            {certs && certs.length > 0 && (
              <ul className="flex flex-col gap-4">
                {certs.map((cert, i) => (
                  <li
                    key={cert.verificationCode ?? i}
                    className="shadow-card rounded-2xl p-4 flex flex-col sm:flex-row sm:items-center gap-3"
                  >
                    <div className="flex-1 min-w-0">
                      <p className="font-semibold text-ink truncate">{cert.courseTitle}</p>
                      <p className="text-xs text-ink-muted mt-0.5">Issued: {cert.issuedAt}</p>
                      <p className="text-xs text-ink-muted mt-0.5">
                        Code:{" "}
                        <code className="font-mono bg-surface-muted px-1.5 py-0.5 rounded-full text-brand-ink">
                          {cert.verificationCode}
                        </code>
                      </p>
                    </div>
                    <button
                      type="button"
                      onClick={() => {
                        if (cert.verificationCode) {
                          void navigator.clipboard.writeText(cert.verificationCode);
                        }
                      }}
                      className="flex-shrink-0 bg-brand-soft hover:bg-brand-soft-strong text-brand-ink text-sm font-medium px-3 py-1.5 rounded-xl transition-colors cursor-pointer"
                    >
                      Copy code
                    </button>
                  </li>
                ))}
              </ul>
            )}
          </div>
        </section>
      </div>
    </div>
  );
}
