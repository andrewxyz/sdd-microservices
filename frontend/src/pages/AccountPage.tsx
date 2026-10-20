import { useEffect, useState } from "react";
import { listMyCertificates, type CertificateView } from "../api/endpoints";
import { ApiError } from "../api/problem";
import ErrorNotice from "../components/ErrorNotice";
import Loading from "../components/Loading";

export default function AccountPage() {
  const [certs, setCerts] = useState<CertificateView[] | null>(null);
  const [certsError, setCertsError] = useState<ApiError | null>(null);

  useEffect(() => {
    listMyCertificates()
      .then((page) => setCerts(page.items ?? []))
      .catch((err: unknown) => {
        if (err instanceof ApiError) setCertsError(err);
        else setCertsError(ApiError.fromNetworkFailure(err));
      });
  }, []);

  return (
    <main className="page">
      <h1>My account</h1>

      <section>
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
