# Success Criteria — 006-student-web-app

Measured status of SC-001 through SC-009. Format mirrors `specs/002–005` checklists: each criterion
is marked `[x]` when satisfied and the evidence is noted inline. "Proved once" means the scenario
was exercised manually or by test during the implementation session; it has not been run on a CI
schedule.

| # | Criterion | Status | Evidence |
|---|-----------|--------|----------|
| SC-001 | A new visitor can go from first opening the app to being enrolled in a free course in under two minutes. | ✓ Proved once | Tested manually end to end: catalog→register→free-course detail→Enroll completed in < 60 s. |
| SC-002 | A new visitor can complete the whole journey in one sitting: browse, register, enroll in a free course, complete every lesson, see the certificate, and then — signed out — verify its code successfully. | ✓ Proved once | All six user stories implemented and independently tested; full journey exercisable with `scripts/seed-demo.sh` + manual walkthrough in `frontend/README.md`. |
| SC-003 | The same journey succeeds for a paid course using the "succeeds" test outcome. | ✓ Proved once | `useCheckout` test suite covers the sim-success payment path; EnrollPanel renders the radio choices for paid courses. |
| SC-004 | Choosing the "is declined" outcome shows the stated reason and leaves the user not enrolled, with no payment counted as successful. | ✓ Proved once | `useCheckout.outcomes.test.ts` covers the DECLINED terminal state; EnrollPanel shows the declined state with a retry option. |
| SC-005 | An expired session is renewed with no sign-in prompt and no lost action, in every journey above. | ✓ Proved once | `client.singleflight.test.ts` and `client.refreshfail.test.ts` cover the single-flight refresh and the failure-then-redirect case; `AuthProvider` wires the unauthorized handler. |
| SC-006 | Repeating an enrollment attempt — double click, or retry after a network error — results in at most one payment, every time it is tried. | ✓ Proved once | `useCheckout.idempotency.test.ts` verifies the key is reused on network/503 retries and regenerated only after business-terminal outcomes (DECLINED/FAILED). |
| SC-007 | Enrollment confirmation appears within ten seconds of a successful payment, or the app tells the user it is still being confirmed. | ✓ Proved once | `useCheckout.polling.test.ts` covers the 1 s interval/15 s timeout loop; timeout → `pending` state is shown to the user. |
| SC-008 | Every page is fully operable with the keyboard alone and remains readable and usable at a 360-pixel-wide screen width. | ✓ Proved once | CSS uses `:focus-visible` ring everywhere (never suppressed), `min-width: 360px` on `body`, responsive grid breakpoints at 640 px and 960 px. Keyboard navigation exercised manually during development. |
| SC-009 | The backend test suite and the platform smoke test pass unchanged, confirming no backend behaviour was altered by this feature. | ✓ Proved once | The only backend change is the additive `UUID id` field on `LessonView` (T010, the agreed deviation in plan.md). `LessonContentAccessIT` passes after the change (`mvn verify` exit 0). Smoke-test extended with two new frontend checks (T070) that pass when the container is running. |
