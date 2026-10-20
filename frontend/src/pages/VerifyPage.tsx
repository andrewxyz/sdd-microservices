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
    <main className="page">
      <h1>Verify certificate</h1>
      <form onSubmit={onSubmit}>
        <div className="field">
          <label htmlFor="verify-code">Verification code</label>
          <input
            id="verify-code"
            type="text"
            value={inputCode}
            onChange={(e) => setInputCode(e.target.value)}
            disabled={verifyState === "loading"}
          />
        </div>
        <button type="submit" disabled={verifyState === "loading"}>
          Verify
        </button>
      </form>

      {verifyState === "loading" && <Loading />}

      {verifyState === "valid" && result && (
        <div className="verify-result verify-result--valid">
          <p>This certificate is valid.</p>
          <p>
            <strong>{result.courseTitle}</strong>
          </p>
          <p>Issued: {result.issuedAt}</p>
        </div>
      )}

      {verifyState === "invalid" && (
        <p className="verify-result verify-result--invalid">This certificate code is not valid.</p>
      )}

      {verifyState === "error" && apiError && <ErrorNotice error={apiError} />}
    </main>
  );
}
