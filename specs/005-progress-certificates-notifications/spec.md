# Feature Specification: Lesson Progress, Certificates, and Email Notifications

**Feature Branch**: `005-progress-certificates-notifications`

**Created**: 2026-10-19

**Status**: Draft

**Input**: User description: "Add lesson progress, certificates and email notifications to LearnHub. Backend only: progress in enrollment-service, new certificate-service and notification-service under backend/, and gateway routes. The frontend is not touched."

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Track lesson progress (Priority: P1)

An enrolled student marks a lesson of their course as complete, can undo that if they marked it by
mistake, and can see their overall progress for the course: how many lessons are complete, how many
exist in total, and the resulting percentage.

**Why this priority**: This is the foundation everything else in this feature is built on — completion
and certificates only exist because progress is being tracked.

**Independent Test**: As an enrolled student, mark one of a course's lessons complete and confirm the
progress view shows it; unmark it and confirm the view reflects that too.

**Acceptance Scenarios**:

1. **Given** a student enrolled in a course with three lessons, **When** they mark one lesson complete,
   **Then** their progress for that course shows 1 completed lesson, 3 total, and roughly 33%.
2. **Given** a lesson already marked complete, **When** the student marks it complete again, **Then**
   nothing changes.
3. **Given** a completed lesson, **When** the student undoes it, **Then** it no longer counts toward
   their progress.
4. **Given** a user who is not enrolled in a course, **When** they try to mark or view progress for one
   of its lessons, **Then** they are refused.
5. **Given** a lesson id that does not belong to the course (or does not exist at all), **When** a
   student tries to mark it complete, **Then** they are refused.

---

### User Story 2 - Automatic, one-time course completion (Priority: P1)

Once every lesson in a course is marked complete, the student's enrollment becomes completed —
permanently. Later unmarking and re-marking lessons never undoes or repeats that fact.

**Why this priority**: Completion is the single event everything downstream (certificates, the
completion email) depends on; it must happen exactly once and never flip back and forth.

**Independent Test**: Mark every lesson of a course complete and confirm the enrollment becomes
completed; unmark one lesson and re-mark it, and confirm completion was not re-triggered or reset.

**Acceptance Scenarios**:

1. **Given** a student has marked every lesson of their enrolled course complete, **When** the last one
   is marked, **Then** that enrollment becomes completed, with a completion date recorded.
2. **Given** an already-completed enrollment, **When** the student unmarks a lesson and later re-marks
   it, **Then** the enrollment's completion is unaffected — it was already completed and stays that way.

---

### User Story 3 - Automatic certificate issuance (Priority: P1)

The moment a course is completed, the student automatically receives a certificate for it — exactly
one, ever, per course — carrying a verification code, the course's title, and the date it was
completed.

**Why this priority**: This is the tangible reward completion exists to produce, and the feature's
central "exactly once" guarantee that the rest of the design protects.

**Independent Test**: Complete a course and confirm exactly one certificate appears for it, normally
within about 10 seconds, carrying a verification code, the course title, and a completion date.

**Acceptance Scenarios**:

1. **Given** a course that has just been completed, **When** a few seconds pass, **Then** the student
   has exactly one certificate for that course, with a verification code, the course title, and the
   completion date.
2. **Given** a certificate already issued for a course, **When** the completion fact is somehow
   processed again, **Then** no second certificate for that course is ever created.

---

### User Story 4 - Public certificate verification (Priority: P2)

Anyone — no account needed — can check a certificate by its verification code and learn whether it is
genuine, which course it is for, and when it was completed. They learn nothing about who holds it.

**Why this priority**: This is what makes a certificate actually useful as proof to a third party (an
employer, for instance), but it depends on certificates already existing (User Story 3).

**Independent Test**: Take a real certificate's verification code and confirm an anonymous check reports
it valid with the right course title and date; try a made-up code and confirm it is reported invalid,
with no account needed either way.

**Acceptance Scenarios**:

1. **Given** a real certificate's verification code, **When** anyone checks it, **Then** they see it is
   valid, the course title, and the completion date — and nothing that identifies who holds it.
2. **Given** a verification code that does not correspond to any certificate, **When** anyone checks it,
   **Then** they see a clear "not valid" result, not an error.

---

### User Story 5 - A student's own certificate list (Priority: P2)

A student can see all of their own certificates in one place; they never see anyone else's.

**Why this priority**: A natural companion to verification (User Story 4), letting a student find their
own certificates without needing to have saved each code, but not required for certificates to exist or
be verifiable at all.

**Independent Test**: As a student with one or more certificates, list them and confirm only your own
appear; confirm a different student's list never includes them.

**Acceptance Scenarios**:

1. **Given** a student with one or more certificates, **When** they list their certificates, **Then**
   they see each one.
2. **Given** two different students, **When** either lists their own certificates, **Then** it never
   includes the other student's.

---

### User Story 6 - Enrollment and certificate emails (Priority: P2)

A student gets an email when they become enrolled in a course, and another when they earn a
certificate. Every email is caught locally for inspection and never actually delivered anywhere, and
re-sending the same underlying event never results in a duplicate email.

**Why this priority**: A real, expected touchpoint of both milestones, but the platform's core
correctness (progress, completion, certificates) does not depend on email delivery succeeding.

**Independent Test**: Enroll in a course and confirm an enrollment email appears in the local catcher;
complete the course and confirm a second, certificate email appears; replay the same underlying event
and confirm no extra email appears.

**Acceptance Scenarios**:

1. **Given** a student who has just been enrolled in a course, **When** a few seconds pass, **Then** an
   enrollment email for them is visible in the local email catcher.
2. **Given** a student who has just earned a certificate, **When** a few seconds pass, **Then** a
   certificate email for them is visible in the local email catcher.
3. **Given** an enrollment or certificate notification that is somehow processed a second time,
   **When** it is processed again, **Then** no second email is sent for it.

---

### User Story 7 - Reliability of completion, certification, and notification (Priority: P3)

A course completion is never lost on its way to becoming a certificate, even if certificate-service is
briefly unavailable at that exact moment. A notification that cannot be processed, or that is malformed,
is set aside for a developer to look at rather than blocking every notification behind it.

**Why this priority**: A non-functional guarantee layered on top of behavior already established by User
Stories 2, 3 and 6 — important for correctness under failure, but not something a user interacts with
directly.

**Independent Test**: Complete a course while certificate-service is stopped; confirm no certificate
exists yet; restart it and confirm exactly one certificate appears. Separately, send a malformed
notification and confirm a valid one sent right after it is still processed promptly.

**Acceptance Scenarios**:

1. **Given** a course that completes while certificate-service is unavailable, **When**
   certificate-service becomes available again, **Then** exactly one certificate is created for that
   completion.
2. **Given** a notification that cannot be processed after a limited number of attempts, **When** that
   limit is reached, **Then** it is moved to a holding area visible to platform operators instead of
   being silently discarded or retried forever.
3. **Given** a malformed notification, **When** it is received, **Then** it is set aside in the holding
   area without blocking any other, unrelated notification.

---

### Edge Cases

- What happens if a course's price or catalog status changes, or the course is archived, after a
  student has already enrolled? Progress tracking and completion continue to work normally — they
  depend only on the existing enrollment, not on the course's current catalog status.
- What happens if a student unmarks a lesson of an already-completed course down to zero completed
  lessons? The enrollment's completion fact (User Story 2) is unaffected; it was already completed and
  stays that way permanently.
- What happens if the same underlying completion is somehow delivered to certificate-service more than
  once? Exactly one certificate still results (User Story 3, scenario 2).
- What happens when someone checks a verification code that belonged to a certificate that was later
  somehow invalidated? Out of scope — this feature has no certificate-revocation capability; every
  issued certificate stays valid permanently (see Assumptions).

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The system MUST allow an enrolled student to mark a lesson of their enrolled course as
  complete.
- **FR-002**: The system MUST allow an enrolled student to undo (unmark) a previously completed lesson.
- **FR-003**: The system MUST refuse a lesson-completion change from a user who is not enrolled in that
  lesson's course.
- **FR-004**: Marking an already-complete lesson complete again MUST change nothing.
- **FR-005**: Unmarking a lesson that is not currently complete MUST change nothing.
- **FR-006**: The system MUST refuse marking a lesson that does not belong to the student's enrolled
  course, or that does not exist at all.
- **FR-007**: A student MUST be able to view their own progress for a course: the number of completed
  lessons, the total number of lessons, and the resulting percentage.
- **FR-008**: When every lesson of a course becomes complete, the system MUST mark that enrollment
  completed, exactly once, recording when it happened.
- **FR-009**: Once an enrollment is marked completed, later unmarking and re-marking of lessons MUST NOT
  change or reset that completion fact (FR-008).
- **FR-010**: Completing an enrollment MUST automatically result in exactly one certificate for that
  student and that course.
- **FR-011**: A certificate MUST carry a verification code, the course's title, and the date the course
  was completed.
- **FR-012**: Certificate issuance MUST normally complete within 10 seconds of the enrollment being
  completed.
- **FR-013**: A student MUST be able to list their own certificates.
- **FR-014**: The system MUST NOT show a user another student's certificates.
- **FR-015**: Anyone, without authenticating, MUST be able to check a certificate by its verification
  code.
- **FR-016**: A certificate check MUST report whether the code is valid, and if so, the course title and
  the completion date.
- **FR-017**: A certificate check MUST NOT reveal anything about who holds the certificate.
- **FR-018**: An unknown or invalid verification code MUST be reported as a clear "not valid" result, not
  as an error.
- **FR-019**: A student MUST receive an email when they become enrolled in a course.
- **FR-020**: A student MUST receive an email when a certificate is issued to them.
- **FR-021**: Every email this feature sends MUST be captured by the platform's local email catcher and
  MUST never be delivered anywhere outside the machine.
- **FR-022**: A redelivered enrollment or certificate notification MUST NOT cause a second email to be
  sent for the same underlying event.
- **FR-023**: A notification that cannot be processed after a limited, bounded number of attempts, and a
  notification that is malformed, MUST be set aside in a holding area visible to platform operators
  rather than discarded or retried indefinitely.
- **FR-024**: A notification that cannot be processed MUST NOT block or delay the processing of other,
  unrelated notifications.
- **FR-025**: A completed enrollment MUST eventually result in exactly one certificate even if
  certificate processing is temporarily unavailable at the moment the enrollment completes.
- **FR-026**: Every error response in this feature MUST follow the platform's standard error structure
  and include a correlation ID.
- **FR-027**: certificate-service and notification-service MUST each own their own data store and MUST
  register with discovery and load their configuration the same way every other platform service does;
  enrollment-service's existing data store gains this feature's progress and completion data.
- **FR-028**: Every endpoint this feature adds for clients MUST be reachable only through the platform's
  single public entry point.

### Key Entities

- **Lesson progress**: Whether a specific enrolled student has completed a specific lesson of their
  course. Exists per student, per lesson; a lesson not recorded here is simply not yet complete.
- **Enrollment** (extended): The existing record of a student's access to a course (specs/004) gains a
  completion fact — whether, and when, every one of its lessons has been completed. Set at most once,
  permanently.
- **Certificate**: Proof that a student completed a course. Carries a verification code, the course's
  title (captured as of completion), the completion date, and which student and course it belongs to
  (visible only to the holder and never through the public verification check). At most one per student
  per course.
- **Held notification**: An enrollment or certificate notification that could not be turned into an
  email after repeated attempts, or that was malformed, kept visible for a platform operator to inspect.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A student who enrolls in a course and completes every one of its lessons ends up with
  exactly one certificate for that course.
- **SC-002**: Anyone can verify that certificate by its code and see that it is valid, with the correct
  course title and completion date, without learning who holds it.
- **SC-003**: The student receives exactly one visible email for becoming enrolled and exactly one for
  earning the certificate, and redelivering the same underlying event never produces an extra email for
  it.
- **SC-004**: When certificate-service is unavailable at the moment a course is completed, exactly one
  certificate exists for that completion once certificate-service is available again.
- **SC-005**: Marking the same lesson complete repeatedly never changes a student's reported progress
  beyond the first time it was marked.
- **SC-006**: Certificate issuance normally completes within 10 seconds of a course being completed.
- **SC-007**: All previously verified platform behaviors continue to pass unchanged.

## Assumptions

- No certificate-revocation capability exists in this feature; once issued, a certificate remains valid
  permanently, as stated in Edge Cases.
- No instructor or administrator view of a student's progress or certificates exists in this feature,
  consistent with the same scope decision already made for enrollment visibility in
  specs/004-checkout-enrollment.
- Progress tracking and course completion continue to work for an already-enrolled student regardless
  of the course's current catalog status (published, archived, or otherwise).
- Lesson order is never enforced — lessons may be completed in any order, consistent with "forcing
  lesson order" being explicitly out of scope.
- A verification code's exact format and length are implementation details; functionally it only needs
  to be unique per certificate and safe to share.
- Email content is plain and minimal; exact wording, layout, templates and recipient preferences are
  explicitly out of scope.
- "A limited, bounded number of attempts" before a notification is held for inspection follows the same
  small, fixed retry count already established for message processing in
  specs/004-checkout-enrollment (three attempts).
- Coupons, refunds, subscriptions, PDF certificates, email templates and preferences, reminders, SMS or
  push notifications, unsubscribing, forcing lesson order, and any frontend screens are all out of
  scope, as stated by the feature description.
