# Feature Specification: Checkout and Enrollment

**Feature Branch**: `004-checkout-enrollment`

**Created**: 2026-10-18

**Status**: Draft

**Input**: User description: "Add checkout and enrollment to LearnHub. This spec covers the backend only: new payment-service and enrollment-service under backend/, a change to how course-service decides who may read lesson content, and gateway routing for the new endpoints. The frontend is not touched."

## Clarifications

### Session 2026-10-18

- Q: Should an instructor or administrator be able to see which students are enrolled in a given
  course, or is enrollment information visible only to the enrolled student themselves in this
  feature? → A: No new endpoint — instructors/admins get no enrollment-list view in this spec;
  enrollment information is visible only to the enrolled student themselves.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Checkout and automatic enrollment (Priority: P1)

A logged-in user checks out a published course. If the course is free, access follows immediately.
If it costs money, a simulated payment provider processes the charge; once it succeeds, the user is
enrolled automatically and can read the course's lesson content.

**Why this priority**: This is the entire point of the feature — turning a logged-in visitor into an
enrolled learner. Nothing else in this spec matters if this path doesn't work.

**Independent Test**: Register a user, check out a free course, and confirm the user can immediately
read its lesson content. Separately, check out a paid course using the simulated "always succeeds"
payment token, and confirm the user becomes enrolled and can read its lesson content shortly after.

**Acceptance Scenarios**:

1. **Given** a published, free course and a logged-in user who is not its instructor and not already
   enrolled, **When** the user checks out the course, **Then** the checkout succeeds immediately and an
   enrollment is created with no payment step.
2. **Given** a published, paid course, **When** the user checks out using the test token that always
   succeeds, **Then** a successful payment is recorded for the course's current catalog price and the
   user is enrolled automatically.
3. **Given** a user who has just been enrolled, **When** they request the course's lesson content,
   **Then** they receive the full content, not just the public outline.
4. **Given** an instructor's own course, **When** that instructor attempts to check it out, **Then** the
   checkout is refused.
5. **Given** a user already enrolled in a course, **When** they attempt to check it out again, **Then**
   the checkout is refused and no second payment or enrollment is created.
6. **Given** a user with one or more enrollments, **When** they list their own enrollments, **Then**
   they see each course they are enrolled in.

---

### User Story 2 - A declined or failed payment never grants access (Priority: P1)

A paid checkout can fail in more than one way: the simulated provider can decline the card, or it can
simulate being temporarily unavailable. Either way, the user is told why, is not charged a successful
payment, and gains no enrollment.

**Why this priority**: Money and access are on the line here. A failure that silently granted access, or
that left the user confused about whether they were charged, would undermine trust in the whole
platform.

**Independent Test**: Check out a paid course using the test token that always declines, and confirm no
enrollment exists afterward and the response explains the decline. Separately, use the token that
simulates provider unavailability and confirm the same outcome with a different, clearly labeled reason.

**Acceptance Scenarios**:

1. **Given** a published, paid course, **When** the user checks out using the test token that always
   declines, **Then** the payment is recorded as declined, the response explains why, and no enrollment
   is created.
2. **Given** a published, paid course, **When** the user checks out using the test token that simulates
   the provider being unavailable, **Then** the payment is recorded as failed, the response explains
   that the provider was unavailable, and no enrollment is created.
3. **Given** a declined payment, **When** the user later checks out the same course again with a new
   checkout, **Then** they may try again normally (a prior decline does not permanently block them).

---

### User Story 3 - Lesson content is gated by enrollment (Priority: P1)

Course-service changes how it decides who may read a course's full lesson content: an enrolled student
may, the owning instructor and administrators still may, and anyone else may not — including when the
platform cannot currently tell whether someone is enrolled.

**Why this priority**: This is the other half of what checkout is for. Without this gate, paying for a
course would not actually protect its content, and a confirmed non-payer could read it for free.

**Independent Test**: With a course and a directly-seeded enrollment record, confirm the enrolled user
can read the full lesson content, confirm a different, non-enrolled user cannot, and confirm the owning
instructor and an administrator always can, independent of any checkout ever happening.

**Acceptance Scenarios**:

1. **Given** a user enrolled in a published course, **When** they request its lesson content, **Then**
   they receive the full content including lesson text.
2. **Given** a user who is not enrolled in a course and is neither its instructor nor an administrator,
   **When** they request its lesson content, **Then** they are refused.
3. **Given** the owning instructor or an administrator, **When** they request the course's lesson
   content, **Then** they receive it regardless of any enrollment record.
4. **Given** that the platform cannot currently confirm a requester's enrollment status (for example,
   enrollment information is temporarily unavailable), **When** that requester asks for lesson content,
   **Then** the request is refused with a clear, temporary-error response — the content is never shown
   on an unconfirmed basis.

---

### User Story 4 - Safe retries (Priority: P2)

Every checkout carries a client-supplied idempotency key so that resending the same checkout — on
purpose or by accident, including many times at once — never charges or enrolls someone twice.

**Why this priority**: Network retries and double-clicks are routine. Without this, a flaky connection
could cause a real double charge, which is a serious trust and financial problem — but it is a
refinement of User Story 1's checkout, not a separate capability on its own.

**Independent Test**: Send the same checkout request body with the same idempotency key many times in
quick succession and confirm exactly one payment and, where applicable, one enrollment result from all
of them. Separately, resend the same key with a different course or amount and confirm it is refused.

**Acceptance Scenarios**:

1. **Given** a checkout already completed under a given idempotency key, **When** the identical request
   is sent again with the same key, **Then** the original result is returned and no new payment or
   enrollment is created.
2. **Given** 20 identical checkout requests sent at the same time with the same idempotency key for the
   same course, **When** they are all processed, **Then** exactly one payment and one enrollment result.
3. **Given** an idempotency key already used for one checkout request, **When** it is reused with a
   different request (for example, a different course), **Then** the second request is refused with a
   clear explanation and is not processed.

---

### User Story 5 - Payment history (Priority: P2)

A user can look back at their own payments and see each one's status; they can never see anyone else's.

**Why this priority**: Useful for a user to confirm what happened to their money, but not required for
the core checkout-to-access journey to work.

**Independent Test**: As a user with at least one payment, list payment history and confirm it shows
exactly that user's own payments and statuses. Confirm a second user's history never includes the
first user's payments.

**Acceptance Scenarios**:

1. **Given** a user with one or more payments, **When** they list their payment history, **Then** they
   see each payment and its current status.
2. **Given** two different users, **When** either lists their own payment history, **Then** it never
   includes the other user's payments.
3. **Given** a user who has just completed a checkout, **When** they check that specific payment's
   status, **Then** they see its current status (for example, pending, succeeded, declined, or failed).

---

### User Story 6 - Reliable enrollment under failure (Priority: P3)

A successful payment is never lost. If the system component that turns a successful payment into an
enrollment is briefly unavailable, the enrollment still happens, exactly once, once things recover.
Notifications that repeatedly fail to process, or that are malformed, are set aside for inspection
rather than silently dropped or allowed to block other notifications.

**Why this priority**: This is a reliability guarantee underneath User Story 1, not a capability a user
interacts with directly — it matters for correctness under failure, which is important but secondary to
the happy-path and access-control behaviors above.

**Independent Test**: Complete a successful payment while the component responsible for creating
enrollments is stopped; confirm no enrollment exists yet; restart it and confirm exactly one enrollment
appears afterward. Separately, submit a malformed notification and confirm it is set aside without
affecting processing of other, valid notifications.

**Acceptance Scenarios**:

1. **Given** a payment that succeeds while enrollment processing is unavailable, **When** enrollment
   processing becomes available again, **Then** exactly one enrollment is created for that payment.
2. **Given** a notification that cannot be processed after a limited number of attempts, **When** that
   limit is reached, **Then** it is moved to a holding area visible to platform operators instead of
   being silently discarded or retried forever.
3. **Given** a malformed notification, **When** it is received, **Then** it is set aside in the holding
   area without crashing processing or blocking any other, unrelated notification.

---

### Edge Cases

- What happens if a course's price changes between when a user starts checkout and when payment is
  attempted? The amount charged is always the course's catalog price read at the moment of checkout,
  not any price seen earlier.
- What happens if two different checkouts for the same course by the same user race each other without
  reusing an idempotency key (e.g., two browser tabs)? At most one succeeds in creating an enrollment;
  the other is refused once the user is recognized as already enrolled, or as already having a payment
  in flight for that course.
- What happens if a user asks for the status of a payment that belongs to someone else? They are
  refused, the same as if it did not exist.
- What happens to a lesson-content request that arrives in the brief window after a successful payment
  but before enrollment has actually been recorded? It is treated as "not yet enrolled" — this is a
  normal, transient state while automatic enrollment is still in progress, not a bug.
- What happens if a user tries to check out a draft or archived course? It is refused the same way
  browsing already treats unpublished courses — it is not available for checkout.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The system MUST allow an authenticated user to initiate checkout for a published course.
- **FR-002**: The system MUST refuse checkout for a course that is not published (draft or archived).
- **FR-003**: The system MUST refuse checkout attempted by the course's own instructor.
- **FR-004**: The system MUST refuse checkout from a user who is already enrolled in the course.
- **FR-005**: For a course whose price is zero, checkout MUST succeed immediately without involving the
  payment provider.
- **FR-006**: For a course with a non-zero price, checkout MUST charge exactly the course's current
  catalog price, read at the moment of checkout, through a simulated payment provider.
- **FR-007**: The simulated payment provider's outcome MUST be driven by a caller-supplied test payment
  token, with at least three distinct behaviors available: one that always succeeds, one that always
  declines, and one that simulates the provider being temporarily unavailable.
- **FR-008**: A declined or failed payment MUST NOT create an enrollment, and the response MUST state
  the reason for the failure.
- **FR-009**: The platform MUST NOT collect, store, log, or transmit real payment-card numbers or other
  raw card data at any point in this feature.
- **FR-010**: Every checkout request MUST carry a client-supplied idempotency key.
- **FR-011**: Resending a checkout request with the same idempotency key and the same request content
  MUST return the original result without creating a second payment or a second enrollment, no matter
  how many times or how close together it is resent.
- **FR-012**: Resending the same idempotency key with different request content MUST be refused with a
  clear explanation and MUST NOT be processed as a new checkout.
- **FR-013**: A successful payment MUST automatically result in the user being enrolled in the course,
  with no further action required from the user.
- **FR-014**: Automatic enrollment following a successful payment MUST normally complete within 5
  seconds of the payment succeeding.
- **FR-015**: A user MUST never hold more than one enrollment in the same course.
- **FR-016**: The response to a successful checkout MUST indicate that enrollment will follow shortly,
  rather than asserting it is already in place.
- **FR-017**: A user MUST be able to query the current status of a payment they initiated.
- **FR-018**: A user MUST be able to list their own enrollments.
- **FR-019**: A user MUST be able to list their own payment history, including each payment's status.
- **FR-020**: The system MUST NOT allow a user to view another user's payments or payment statuses.
- **FR-021**: A user enrolled in a course MUST be able to read that course's full lesson content,
  including lesson text.
- **FR-022**: A user who is not enrolled in a course, and who is neither its owning instructor nor an
  administrator, MUST be refused access to that course's full lesson content.
- **FR-023**: The owning instructor and administrators MUST retain access to a course's full lesson
  content regardless of enrollment status.
- **FR-024**: When the system cannot currently determine whether a requester is enrolled in a course,
  lesson content MUST be refused with a clear, temporary-error response; it MUST NOT be shown on an
  unconfirmed basis.
- **FR-025**: A successfully completed payment MUST eventually result in exactly one enrollment even if
  the notification of that success, or enrollment processing itself, is temporarily unavailable at the
  moment the payment succeeds.
- **FR-026**: A notification that cannot be processed after a limited, bounded number of attempts, and a
  notification that is malformed, MUST be set aside in a holding area visible to platform operators
  rather than discarded or retried indefinitely.
- **FR-027**: A notification that cannot be processed MUST NOT block or delay the processing of other,
  unrelated notifications.
- **FR-028**: Every price and payment amount in this feature MUST be a whole amount in the smallest unit
  of its currency; the system supports exactly one currency, USD, for this feature.
- **FR-029**: Every error response in this feature MUST follow the platform's standard error structure
  and include a correlation ID.
- **FR-030**: payment-service and enrollment-service MUST each own their own data store, and MUST
  register with discovery and load their configuration the same way every other platform service does.
- **FR-031**: Every endpoint this feature adds for clients MUST be reachable only through the platform's
  single public entry point.
- **FR-032**: Enrollment information MUST be visible only to the enrolled student themselves in this
  feature; this feature does not add any way for an instructor or administrator to list which students
  are enrolled in a course.

### Key Entities

- **Payment**: One attempt to pay for one course by one user. Tracks the user, the course, the amount
  and currency charged, its status (pending, succeeded, declined, or failed), the idempotency key it was
  submitted under, and timestamps. A free checkout still produces a payment record marked as succeeded
  with a zero amount, so history and status lookups behave uniformly regardless of price.
- **Enrollment**: Evidence that a specific user has access to a specific course. Created automatically
  after a successful payment (including a zero-amount one). A user has at most one enrollment per
  course.
- **Checkout (idempotency) record**: The association between a client-supplied idempotency key and the
  checkout request it was first used for, used to detect and safely answer repeats of the same request
  and to refuse conflicting reuse of the same key.
- **Held notification**: A payment-success notification that could not be turned into an enrollment
  after repeated attempts, or that was malformed, kept visible for a platform operator to inspect.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A user can go from logging in to reading a course's full lesson content, entirely through
  the platform's single entry point, for both a free course and a paid course (using the simulated
  provider's success token), with no manual steps beyond checkout.
- **SC-002**: Across repeated attempts, a declined or provider-unavailable checkout never results in an
  enrollment.
- **SC-003**: Sending 20 simultaneous, identical checkout requests under the same idempotency key for
  the same course results in exactly one payment and exactly one enrollment.
- **SC-004**: When enrollment processing is stopped at the moment a payment succeeds, exactly one
  enrollment exists for that payment once enrollment processing resumes.
- **SC-005**: Across repeated attempts, a user who is not enrolled in a course is never able to read its
  full lesson content.
- **SC-006**: At least 95% of successful payments result in a recorded enrollment within 5 seconds under
  normal operation.
- **SC-007**: All previously verified platform behaviors — startup, discovery, configuration, rate
  limiting, authentication, and the course catalog — continue to pass unchanged.

## Assumptions

- A checkout's idempotency key only needs to be unique per user; two different users may coincidentally
  submit the same key value for their own, unrelated checkouts without conflict.
- Any authenticated account (student, instructor, or administrator) may check out a course, except for
  the course's own instructor checking out their own course; no other role restriction applies to who
  may purchase a course.
- "A limited, bounded number of attempts" before a notification is held for inspection is a small,
  fixed number of retries (on the order of a handful), not an unbounded or time-based limit; the exact
  count is a technical decision for the implementation plan.
- Payment history and enrollment listings in this feature show only the requesting user's own records;
  there is no administrator override to view another user's payments in this feature (administrators
  already have other ways to investigate, outside this spec's scope).
- A course's price and currency are read from the course catalog at checkout time via the existing
  internal, service-to-service lookup; this feature does not duplicate or cache pricing data on its own.
- Coupons, refunds, subscriptions, real payment providers, invoices/receipts, lesson progress tracking,
  certificates, notifications/emails, ratings/reviews, and unenrolling are all out of scope, as stated by
  the feature description.
