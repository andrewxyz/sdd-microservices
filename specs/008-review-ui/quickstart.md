# Quickstart: Course Ratings and Reviews UI

## Automated checks (no running stack needed)
```bash
cd frontend && npm run gen:api && npm run typecheck && npm test && npm run build
cd backend && mvn -pl review-service -am verify      # ITs need Docker (WSL)
```

## Manual check against the stack (`scripts/up.sh`, `scripts/seed-demo.sh`)
1. Batch endpoint: `curl "http://localhost:9080/api/v1/reviews/summaries?courseIds=$ID1,$ID2"` returns 200 with one item per id; an unreviewed id shows `reviewCount: 0`, `averageRating: null`.
2. Over the limit: 51 ids returns 400 problem details with a `correlationId`.
3. Open `http://localhost:9100`: each course card shows a rating and count or "No reviews yet".
4. Open a course signed out: summary and reviews are shown, no form.
5. Sign in as an enrolled student: submit, edit and delete a review; the summary updates each time.
6. Sign in as a non-enrolled student: submitting shows a clear error with its reference code.
