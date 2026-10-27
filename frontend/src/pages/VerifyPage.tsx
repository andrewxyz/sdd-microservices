import { useEffect, useState } from "react";
import { useParams } from "react-router";
import { verifyCertificate, type VerificationView } from "../api/endpoints";
import { ApiError } from "../api/problem";
import ErrorNotice from "../components/ErrorNotice";
import Loading from "../components/Loading";

type VerifyState = "idle" | "loading" | "valid" | "invalid" | "error";

export default function VerifyPage() {
  const { code: codeParam } = useParams<{ code: string }>();
  const [inputCode, setInputCode] = useState(codeParam ?? "");
  const [verifyState, setVerifyState] = useState<VerifyState>(codeParam ? "loading" : "idle");
  const [result, setResult] = useState<VerificationView | null>(null);
  const [apiError, setApiError] = useState<ApiError | null>(null);

  useEffect(() => {
    if (!codeParam) return;
    const code = codeParam.trim().toUpperCase();
    setVerifyState("loading");
    verifyCertificate(code)
      .then((data) => {
        setResult(data);
        setVerifyState("valid");
      })
      .catch((err: unknown) => {
        if (err instanceof ApiError && err.status === 404) {
          setVerifyState("invalid");
        } else if (err instanceof ApiError) {
          setApiError(err);
          setVerifyState("error");
        } else {
          setApiError(ApiError.fromNetworkFailure(err));
          setVerifyState("error");
        }
      });
  }, [codeParam]);

  function onSubmit(e: React.FormEvent) {
    e.preventDefault();
    const code = inputCode.trim().toUpperCase();
    if (!code) return;
    setVerifyState("loading");
    setApiError(null);
    setResult(null);
    verifyCertificate(code)
      .then((data) => {
        setResult(data);
        setVerifyState("valid");
      })
      .catch((err: unknown) => {
        if (err instanceof ApiError && err.status === 404) {
          setVerifyState("invalid");
        } else if (err instanceof ApiError) {
          setApiError(err);
          setVerifyState("error");
        } else {
          setApiError(ApiError.fromNetworkFailure(err));
          setVerifyState("error");
        }
      });
  }

  return (
    <div className="min-h-screen bg-canvas flex items-center justify-center px-4 py-12">
      <div className="bg-surface rounded-2xl shadow-card p-8 w-full max-w-md">
        <div className="text-center mb-8">
          <div className="w-14 h-14 bg-brand-soft-strong rounded-full flex items-center justify-center mx-auto mb-3">
            <span className="text-2xl">🏆</span>
          </div>
          <h1 className="text-2xl font-bold text-ink">Verify certificate</h1>
          <p className="text-ink-muted text-sm mt-1">Enter a verification code to confirm authenticity</p>
        </div>

        <form onSubmit={onSubmit}>
          <div className="flex flex-col gap-1 mb-4">
            <label htmlFor="verify-code" className="text-sm font-medium text-ink-body">
              Verification code
            </label>
            <input
              id="verify-code"
              type="text"
              value={inputCode}
              onChange={(e) => setInputCode(e.target.value)}
              disabled={verifyState === "loading"}
              placeholder="e.g. ABC123XYZ"
              className="border border-line-strong rounded-xl px-3 py-2.5 text-sm font-mono focus:outline-none focus:ring-2 focus:ring-focus focus:border-transparent transition-shadow disabled:opacity-60 uppercase"
            />
          </div>
          <button
            type="submit"
            disabled={verifyState === "loading"}
            className="w-full bg-brand hover:bg-brand-strong shadow-card active:shadow-inset disabled:opacity-60 disabled:cursor-not-allowed text-on-brand font-semibold py-2.5 rounded-xl transition-colors cursor-pointer"
          >
            {verifyState === "loading" ? "Verifying…" : "Verify"}
          </button>
        </form>

        <div className="mt-6">
          {verifyState === "loading" && <Loading />}

          {verifyState === "valid" && result && (
            <div className="bg-success-soft border border-success-line rounded-2xl p-5 text-center">
              <div className="text-3xl mb-2">✅</div>
              <p className="font-bold text-success-ink text-lg">Certificate valid</p>
              <p className="text-success-ink font-medium mt-2">{result.courseTitle}</p>
              <p className="text-success-ink text-sm mt-1">Issued: {result.issuedAt}</p>
            </div>
          )}

          {verifyState === "invalid" && (
            <div className="bg-danger-soft border border-danger-line rounded-2xl p-5 text-center">
              <div className="text-3xl mb-2">❌</div>
              <p className="font-bold text-danger-ink">Certificate not found</p>
              <p className="text-danger-ink text-sm mt-1">This code is not valid or has expired.</p>
            </div>
          )}

          {verifyState === "error" && apiError && <ErrorNotice error={apiError} />}
        </div>
      </div>
    </div>
  );
}
