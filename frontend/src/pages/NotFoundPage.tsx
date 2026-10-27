import { Link } from "react-router";

export default function NotFoundPage() {
  return (
    <div className="min-h-screen bg-canvas flex items-center justify-center px-4">
      <div className="text-center">
        <p className="text-8xl font-black text-ink-faint select-none">404</p>
        <h1 className="text-2xl font-bold text-ink mt-2">Page not found</h1>
        <p className="text-ink-muted mt-2 mb-6">The page you're looking for doesn't exist.</p>
        <Link
          to="/"
          className="inline-block bg-brand hover:bg-brand-strong shadow-card active:shadow-inset text-on-brand font-semibold px-6 py-2.5 rounded-xl transition-colors"
        >
          Back to the catalog
        </Link>
      </div>
    </div>
  );
}
