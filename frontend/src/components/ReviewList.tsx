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
    return <p className="text-sm text-ink-muted">No reviews yet</p>;
  }
  return (
    <div>
      <ul className="divide-y divide-line">
        {reviews.map((review) => (
          <li key={review.id} className="py-4">
            <div className="flex items-center gap-3">
              <span
                role="img"
                aria-label={`Rated ${review.rating ?? 0} out of 5`}
                className="text-star tracking-tight"
              >
                {"★".repeat(review.rating ?? 0)}
                <span className="text-ink-faint">{"★".repeat(5 - (review.rating ?? 0))}</span>
              </span>
              {review.createdAt && (
                <span className="text-xs text-ink-muted">{new Date(review.createdAt).toLocaleDateString("en-US")}</span>
              )}
            </div>
            {review.comment && <p className="text-sm text-ink-body mt-1 whitespace-pre-wrap">{review.comment}</p>}
          </li>
        ))}
      </ul>
      {hasMore && (
        <button
          type="button"
          onClick={onShowMore}
          className="mt-3 text-sm font-medium text-brand-ink hover:text-brand-ink cursor-pointer"
        >
          Show more
        </button>
      )}
    </div>
  );
}
