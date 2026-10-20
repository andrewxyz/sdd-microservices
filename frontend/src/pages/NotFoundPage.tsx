import { Link } from "react-router";

export default function NotFoundPage() {
  return (
    <div className="min-h-screen bg-gradient-to-br from-slate-50 to-indigo-50 flex items-center justify-center px-4">
      <div className="text-center">
        <p className="text-8xl font-black text-indigo-200 select-none">404</p>
        <h1 className="text-2xl font-bold text-slate-800 mt-2">Page not found</h1>
        <p className="text-slate-500 mt-2 mb-6">The page you're looking for doesn't exist.</p>
        <Link
          to="/"
          className="inline-block bg-indigo-600 hover:bg-indigo-700 text-white font-semibold px-6 py-2.5 rounded-lg transition-colors"
        >
          Back to the catalog
        </Link>
      </div>
    </div>
  );
}
