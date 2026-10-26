import type { ReviewView } from "../api/endpoints";

/** Reviews newest first; every server string (the comment) is rendered as text only (FR-008). */
export default function ReviewList({
  reviews,
  hasMore,
  onShowMore,
}: {
  reviews: ReviewView[];
  hasMore: boolean;
  onShowMore: () => void;
}) {
  if (reviews.length === 0) {
    return <p className="text-sm text-slate-500">No reviews yet</p>;
  }
  return (
    <div>
      <ul className="divide-y divide-slate-100">
        {reviews.map((review) => (
          <li key={review.id} className="py-4">
            <div className="flex items-center gap-3">
              <span
                role="img"
                aria-label={`Rated ${review.rating ?? 0} out of 5`}
                className="text-amber-400 tracking-tight"
              >
                {"★".repeat(review.rating ?? 0)}
                <span className="text-slate-300">{"★".repeat(5 - (review.rating ?? 0))}</span>
              </span>
              {review.createdAt && (
                <span className="text-xs text-slate-400">{new Date(review.createdAt).toLocaleDateString("en-US")}</span>
              )}
            </div>
            {review.comment && <p className="text-sm text-slate-700 mt-1 whitespace-pre-wrap">{review.comment}</p>}
          </li>
        ))}
      </ul>
      {hasMore && (
        <button
          type="button"
          onClick={onShowMore}
          className="mt-3 text-sm font-medium text-indigo-600 hover:text-indigo-800 cursor-pointer"
        >
          Show more
        </button>
      )}
    </div>
  );
}
