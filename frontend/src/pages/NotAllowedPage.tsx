import { Link } from "react-router";

/** Shown to a signed-in user who opens a page their role cannot use (spec 009 FR-010). */
export default function NotAllowedPage() {
  return (
    <main className="max-w-xl mx-auto px-4 py-16 text-center">
      <h1 className="text-3xl font-bold text-slate-900 mb-3">You are not allowed to view this page</h1>
      <p className="text-slate-600 mb-6">The dashboard is only for instructors and administrators.</p>
      <Link to="/" className="text-indigo-700 hover:underline">
        Back to the catalog
      </Link>
    </main>
  );
}
