# Feature Specification: Student Web App

**Feature Branch**: `006-student-web-app`

**Created**: 2026-10-19

**Status**: Draft

**Input**: User description: "Add the student web app to LearnHub. Frontend only: a React app in frontend/, a container for it in docker-compose.yml, and README updates. The backend is not changed, and the app talks to it only through the existing entry point. Who it's for: visitors and students. Instructors and administrators keep using the API for now. Browse without logging in; register, log in and log out; enroll (free one click, paid with a simulated payment choice); learn with progress tracking; certificates with public verification; account with payment history. Every error shown includes a short reference code. Pages work on phone and desktop, are keyboard accessible, and have loading and error states. Lesson text is never interpreted as HTML."

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Browse the catalog without an account (Priority: P1)

A visitor who has never heard of LearnHub opens the site and immediately sees what is on offer. They
search by keyword, narrow the list by category, level and free-only, sort it, and page through the
results. Opening a course shows what it covers and the lesson outline, but no lesson content —
enough to decide whether it is worth signing up for.

**Why this priority**: This is the only journey that needs nothing else to work. It is the whole
shop window: without it there is no reason for a visitor to register, and it is independently
shippable as a read-only catalog.

**Independent Test**: With no account and no session, open the app, search and filter the catalog,
and open a course page. Everything renders, nothing asks for a login, and no lesson content appears.

**Acceptance Scenarios**:

1. **Given** a visitor who is not signed in, **When** they open the app, **Then** they see a list of
   published courses, each showing at least its title, level, category and price (or "Free").
2. **Given** the catalog is showing, **When** the visitor types a keyword, **Then** the list narrows
   to courses whose title or description matches, without needing a page reload.
3. **Given** the catalog is showing, **When** the visitor picks a category, a level, or turns on
   free-only, **Then** the list narrows accordingly and the active filters are visible.
4. **Given** filters that match nothing, **When** the results come back empty, **Then** a friendly
   empty state explains that nothing matched and offers a way to clear the filters — not an error.
5. **Given** more courses than fit one page, **When** the visitor moves to the next page, **Then**
   the next set of courses is shown and the current position is clear.
6. **Given** a visitor viewing a course page, **When** the page loads, **Then** they see the course
   details and an ordered lesson outline with titles and durations, and no lesson's text.
7. **Given** a visitor who has searched and filtered, **When** they reload or share the address,
   **Then** the same result set is shown.

---

### User Story 2 - Register, sign in, stay signed in, sign out (Priority: P1)

A visitor creates an account, is signed in straight away, and stays signed in as they move around
and reload the page. When their session quietly expires, it is renewed behind the scenes and they
never notice. If renewal is impossible, they are asked to sign in again and land back on the page
they were trying to reach. Server complaints about a specific field appear next to that field.

**Why this priority**: Every other journey beyond browsing is gated by it. It also carries the
platform's session rules, which are easy to get wrong and expensive to retrofit.

**Independent Test**: Register a new account, reload the page (still signed in), let the access
session expire, continue using the app (no visible interruption), then sign out and confirm
protected pages are no longer reachable.

**Acceptance Scenarios**:

1. **Given** a visitor on the registration form, **When** they submit a valid email and password,
   **Then** the account is created and they are signed in without a second login step.
2. **Given** a registration or sign-in the server rejects for a specific field (for example an email
   already in use), **When** the response arrives, **Then** the message appears next to that field
   and the rest of the form keeps what the user typed.
3. **Given** a signed-in user, **When** they reload the page in the same tab, **Then** they are
   still signed in.
4. **Given** a signed-in user whose access has expired, **When** they do anything that needs the
   backend, **Then** the session renews and the action completes with no sign-in prompt.
5. **Given** a signed-in user whose renewal fails, **When** they do anything that needs the backend,
   **Then** they are taken to sign-in, and after signing in they are returned to the page they were
   on.
6. **Given** a signed-in user, **When** they sign out, **Then** protected pages are no longer
   reachable and the catalog still is.
7. **Given** a signed-in user in one tab, **When** they open the app in a new tab, **Then** the new
   tab starts signed out, by design.

---

### User Story 3 - Enroll in a course (Priority: P1)

A signed-in student decides to take a course. A free course takes one click. A paid course asks them
to pick a clearly labelled test outcome — succeeds, is declined, provider unavailable — and never
asks for card details. Whatever happens is explained in plain words. After a successful payment the
app says the enrollment is being confirmed and flips the course to enrolled as soon as the backend
agrees, or tells them to check back shortly if it is taking longer. Clicking twice, or retrying
after the network drops, never charges twice.

**Why this priority**: It is the step that turns a visitor into a student, and the one where a
mistake costs real money. The no-double-charge rule has to be designed in, not added later.

**Independent Test**: As a signed-in student, enroll in a free course in one click; enroll in a paid
course with each of the three test outcomes and confirm each is explained correctly; double-click
the enroll control and confirm only one payment exists in payment history.

**Acceptance Scenarios**:

1. **Given** a signed-in student on a free course page, **When** they confirm enrollment, **Then**
   no payment choice is shown and the enrollment proceeds.
2. **Given** a signed-in student on a paid course page, **When** they start enrollment, **Then**
   they are offered exactly three clearly labelled simulated outcomes and no field asks for a card,
   bank or other payment credential.
3. **Given** the "succeeds" outcome, **When** the payment completes, **Then** the app states the
   enrollment is being confirmed, and shows the course as enrolled once confirmation arrives.
4. **Given** confirmation has not arrived after about ten seconds, **When** the wait ends, **Then**
   the app tells the user the enrollment is still being confirmed and to check back shortly, without
   claiming it failed.
5. **Given** the "is declined" outcome, **When** the result arrives, **Then** the stated reason is
   shown and the student is not enrolled.
6. **Given** the "provider unavailable" outcome, **When** the result arrives, **Then** the app says
   the service is temporarily unavailable and invites the student to try again.
7. **Given** a student already enrolled in the course, **When** they try to enroll again, **Then**
   the app says they are already enrolled and offers a way into the course.
8. **Given** the signed-in user is the course's own instructor, **When** they try to enroll, **Then**
   the app explains that they cannot enroll in their own course.
9. **Given** a student clicking enroll twice quickly, or retrying after a network error, **When**
   the attempts are made, **Then** at most one payment is recorded.
10. **Given** an enrollment attempt is in flight, **When** the student looks at the control, **Then**
    it is disabled and shows that work is in progress.

---

### User Story 4 - Learn and track progress (Priority: P2)

A student opens My Courses, sees how far they have got in each one, and goes into a course to work
through it. The lesson list is on the page, the selected lesson's text reads as plain text, and
marking a lesson complete (or undoing it) updates the progress bar straight away. A student who is
not enrolled is invited to enroll instead. If content cannot be fetched right now, the page says so
and offers to try again.

**Why this priority**: This is the product's actual value, but it depends on an enrollment existing
first, so it follows the three P1 journeys.

**Independent Test**: As a student enrolled in a course, open My Courses, enter the course, read a
lesson, mark it complete, see progress move, unmark it, and see progress move back.

**Acceptance Scenarios**:

1. **Given** a signed-in student with enrollments, **When** they open My Courses, **Then** each
   course shows its title and progress as completed/total lessons and a percentage.
2. **Given** a signed-in student with no enrollments, **When** they open My Courses, **Then** they
   see an empty state pointing them at the catalog.
3. **Given** an enrolled student in a course, **When** they open a lesson, **Then** its text is
   shown as plain text, and any markup-looking characters in it appear literally rather than being
   rendered.
4. **Given** an enrolled student viewing a lesson, **When** they mark it complete, **Then** the
   lesson shows as complete and the progress bar and counts update.
5. **Given** a lesson already marked complete, **When** the student unmarks it, **Then** it shows as
   not complete and progress updates.
6. **Given** a student who completes the final lesson, **When** progress reaches every lesson,
   **Then** the course shows as completed.
7. **Given** a signed-in user who is not enrolled, **When** they open the course's learning page,
   **Then** they see an invitation to enroll instead of any lesson content.
8. **Given** lesson content cannot be loaded because a backend dependency is temporarily
   unavailable, **When** the page gives up, **Then** it explains the situation and offers a retry.

---

### User Story 5 - Certificates and public verification (Priority: P2)

A student who has finished a course finds the certificate in their list, with the code they can
quote. Anyone at all — an employer, with no account — can type that code into a public page and see
that it is genuine, which course it was for and when it was issued, and nothing about who holds it.

**Why this priority**: It is the payoff for finishing a course and the only part of the app a
non-user is meant to reach, but nothing else depends on it.

**Independent Test**: As a student who has completed a course, open the certificates list and copy
the code; then, signed out, enter that code on the verification page and see a valid result; enter a
made-up code and see a clear "not valid".

**Acceptance Scenarios**:

1. **Given** a signed-in student with a certificate, **When** they open their certificates, **Then**
   each one shows the course title, the issue date and the verification code.
2. **Given** a certificate in the list, **When** the student uses the copy control, **Then** the
   code is copied and the app confirms it.
3. **Given** a student who has just completed a course but whose certificate has not been issued
   yet, **When** they open their certificates, **Then** the app says one is on its way rather than
   showing nothing.
4. **Given** anyone, signed in or not, **When** they enter a valid code on the verification page,
   **Then** they see that it is valid, with the course title and the issue date.
5. **Given** a valid verification result, **When** it is displayed, **Then** it reveals nothing about
   who holds the certificate.
6. **Given** an unknown or mistyped code, **When** it is submitted, **Then** a clear "not valid"
   result is shown, not an error page.
7. **Given** a code entered with extra spaces or in lower case, **When** it is submitted, **Then** it
   is still recognised.

---

### User Story 6 - Account and payment history (Priority: P3)

A signed-in user opens their account to check which email they signed up with, what role they hold,
and what they have paid for — and can sign out from there.

**Why this priority**: Useful and expected, but no other journey depends on it and nothing breaks
without it.

**Independent Test**: As a signed-in student who has made at least one payment, open the account
page and confirm the email, role and payment list are correct, then sign out from that page.

**Acceptance Scenarios**:

1. **Given** a signed-in user, **When** they open their account, **Then** they see the email they
   registered with and their role.
2. **Given** a signed-in user with payments, **When** they open their account, **Then** they see
   each payment's course, amount, status and date, newest first.
3. **Given** a signed-in user with no payments, **When** they open their account, **Then** they see
   an empty state rather than a blank area.
4. **Given** a signed-in user on the account page, **When** they sign out, **Then** they are signed
   out and sent somewhere public.

---

### Edge Cases

- A search or filter combination matches nothing: a friendly empty state with a way to clear
  filters, never an error.
- A course is withdrawn from the catalog between the visitor seeing it in the list and opening it:
  the course page says it is no longer available rather than failing silently.
- The session expires in the middle of an action (marking a lesson complete, submitting a
  checkout): the action completes after a silent renewal, without the user repeating it.
- Renewal fails because the session was revoked elsewhere: the user is taken to sign-in once, not
  into a redirect loop, and returns afterwards to where they were.
- A second browser tab is opened: it starts signed out, because the session is confined to one tab.
- The network drops mid-checkout and the student retries: still at most one payment.
- Enrollment confirmation takes longer than about ten seconds: the app says it is still being
  confirmed and to check back, and never claims success or failure it cannot prove.
- Lesson content cannot be served because enrollment cannot be confirmed right now: a retry, not an
  empty page and not a wrong "you are not enrolled" message.
- A certificate has not been issued yet immediately after the last lesson: the list says one is on
  its way.
- A verification code is pasted with surrounding whitespace or in the wrong case: still recognised.
- The backend is entirely unreachable: every page shows an error state with a retry and a reference
  code, rather than a blank screen.

## Requirements *(mandatory)*

### Functional Requirements

**Browsing the catalog**

- **FR-001**: Anyone, without signing in, MUST be able to see a list of published courses showing at
  least title, level, category and price (shown as "Free" when there is no charge).
- **FR-002**: The catalog MUST support a keyword search matched against course title and
  description.
- **FR-003**: The catalog MUST support filtering by one category at a time and by level.
- **FR-004**: The catalog MUST support a free-only filter that, when on, shows only courses with no
  charge.
- **FR-005**: The catalog MUST support sorting by newest, by title, by price ascending and by price
  descending.
- **FR-006**: The catalog MUST be paged, showing where the user is in the results and letting them
  move between pages.
- **FR-007**: When nothing matches, the catalog MUST show a friendly empty state offering a way to
  clear the filters, and MUST NOT present it as an error.
- **FR-008**: Anyone MUST be able to open a course page showing the course details and its lesson
  outline — titles, order and durations — and the page MUST NOT expose any lesson's content.
- **FR-009**: The current search, filters, sort and page MUST be reflected in the page address, so a
  result set survives a reload and can be shared.

**Session**

- **FR-010**: A visitor MUST be able to register with an email and password and be signed in as a
  result, without a separate sign-in step.
- **FR-011**: A registered user MUST be able to sign in with their email and password.
- **FR-012**: A signed-in user MUST be able to sign out, after which pages that require sign-in are
  no longer reachable.
- **FR-013**: A signed-in user MUST stay signed in across page reloads within the same browser tab,
  and MUST NOT be signed in in a new tab or after the tab is closed.
- **FR-014**: When the signed-in session expires, the app MUST renew it without the user noticing
  and MUST complete the action the user was performing.
- **FR-015**: When renewal cannot succeed, the app MUST send the user to sign-in exactly once and,
  on success, return them to the page they were trying to reach.
- **FR-016**: Server validation messages that concern one field MUST be shown next to that field,
  and the rest of the user's input MUST be preserved.
- **FR-017**: Pages that require sign-in MUST send a signed-out visitor to sign-in rather than
  showing a blank or broken page.

**Enrolling**

- **FR-018**: A signed-in user MUST be able to start enrollment from the course page.
- **FR-019**: A course with no charge MUST enroll in a single confirmation step, with no payment
  choice shown.
- **FR-020**: A paid course MUST present exactly three clearly labelled simulated payment outcomes —
  succeeds, is declined, provider unavailable — and MUST NOT present any field for a card number or
  any other real payment credential.
- **FR-021**: The app MUST explain each outcome in plain language: success, declined with the stated
  reason, already enrolled, the user's own course, and temporarily unavailable.
- **FR-022**: After a successful payment the app MUST state that the enrollment is being confirmed,
  MUST show the course as enrolled once confirmation arrives within about ten seconds, and MUST
  otherwise tell the user to check back shortly.
- **FR-023**: Repeated submissions of the same enrollment attempt — a double click, or a retry after
  a network error — MUST NOT result in more than one payment.
- **FR-024**: While an enrollment attempt is in flight the enroll control MUST be disabled and MUST
  show that work is in progress.

**Learning**

- **FR-025**: A signed-in user MUST be able to see the courses they are enrolled in, each with
  completed and total lesson counts and a percentage.
- **FR-026**: An enrolled user MUST be able to open a course learning page that lists the course's
  lessons and shows the selected lesson's content.
- **FR-027**: Lesson content MUST be displayed as plain text and MUST NOT be interpreted as markup
  of any kind.
- **FR-028**: An enrolled user MUST be able to mark a lesson complete and to undo that, with the
  progress bar and counts updating to match.
- **FR-029**: A user who is not enrolled MUST be shown an invitation to enroll instead of any lesson
  content.
- **FR-030**: When lesson content cannot be loaded because a dependency is temporarily unavailable,
  the page MUST say so and offer a retry, and MUST NOT claim the user is not enrolled.

**Certificates**

- **FR-031**: A signed-in user MUST be able to see their own certificates, each with the course
  title, the issue date and the verification code.
- **FR-032**: A user MUST be able to copy a verification code from that list.
- **FR-033**: Anyone, without signing in, MUST be able to enter a code on a public verification page
  and be told whether it is valid, and for a valid code see the course title and issue date.
- **FR-034**: A verification result MUST NOT reveal anything about who holds the certificate.
- **FR-035**: An unknown code MUST produce a clear "not valid" result rather than an error page.
- **FR-036**: Code entry MUST ignore surrounding whitespace and letter case.

**Account**

- **FR-037**: A signed-in user MUST be able to see the email they registered with and their role.
- **FR-038**: A signed-in user MUST be able to see their payment history, each entry showing the
  course, amount, status and date, newest first.
- **FR-039**: The account page MUST offer sign out.

**Across every page**

- **FR-040**: Every error shown to a user MUST include a short reference code they can quote when
  asking for help.
- **FR-041**: Every page MUST be usable on a phone screen and on a desktop screen.
- **FR-042**: Every interactive control MUST be reachable and operable using the keyboard alone, and
  the focused control MUST be visibly identifiable.
- **FR-043**: Every page that loads data MUST show a loading state while waiting, and an error state
  with a retry when loading fails.
- **FR-044**: The app MUST reach the backend only through the platform's single public entry point,
  and MUST NOT address any individual service.

### Key Entities

The app keeps no database of its own. Everything below is information it reads from the platform and
presents; the only thing it holds is the signed-in session, for the lifetime of the browser tab.

- **Catalog course**: what a visitor sees before enrolling — title, description, category, level,
  language, price, and the lesson outline (titles, order, durations) with no lesson text.
- **Category**: a named grouping used to filter the catalog.
- **Session**: the signed-in user's identity and the credentials that keep them signed in for the
  tab; discarded on sign-out and when the tab closes.
- **Enrollment**: the fact that the signed-in user has access to a course, plus whether the course
  has been completed.
- **Lesson progress**: which lessons of an enrolled course the user has marked complete, and the
  resulting completed/total counts.
- **Certificate**: proof of completing a course — course title, issue date and verification code.
- **Verification result**: what a public code check returns — valid or not, and for a valid code the
  course title and issue date, with nothing identifying the holder.
- **Payment**: a record in the user's own history — course, amount, status and date.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A new visitor can go from first opening the app to being enrolled in a free course in
  under two minutes.
- **SC-002**: A new visitor can complete the whole journey in one sitting: browse, register, enroll
  in a free course, complete every lesson, see the certificate, and then — signed out — verify its
  code successfully.
- **SC-003**: The same journey succeeds for a paid course using the "succeeds" test outcome.
- **SC-004**: Choosing the "is declined" outcome shows the stated reason and leaves the user not
  enrolled, with no payment counted as successful.
- **SC-005**: An expired session is renewed with no sign-in prompt and no lost action, in every
  journey above.
- **SC-006**: Repeating an enrollment attempt — double click, or retry after a network error —
  results in at most one payment, every time it is tried.
- **SC-007**: Enrollment confirmation appears within ten seconds of a successful payment, or the app
  tells the user it is still being confirmed.
- **SC-008**: Every page is fully operable with the keyboard alone and remains readable and usable
  at a 360-pixel-wide screen width.
- **SC-009**: The backend test suite and the platform smoke test pass unchanged, confirming no
  backend behaviour was altered by this feature.

## Assumptions

- The backend is unchanged **except for one additive field**, agreed during planning: the
  lesson-content endpoint now also returns each lesson's identifier. Without it no endpoint a
  student can reach says *which* lesson to mark complete, so FR-028 and SC-002 would be
  unbuildable. See the Complexity Tracking table in [plan.md](plan.md) for the full justification.
  Every other capability above is already exposed through the platform's single entry point:
  catalog listing with keyword, category, level, free-only, sorting and paging; public course
  detail with a lesson outline; the public category list; register, sign-in, renewal, sign-out and
  profile; checkout with the three simulated payment outcomes; payment history; enrollments, lesson
  progress and lesson content; certificates and public verification.
- The category filter selects one category at a time, and the free-only filter is a simple on/off
  that shows only free courses when on — matching what the catalog already offers.
- The catalog is the app's home page; there is no separate marketing landing page.
- Instructors and administrators continue to use the API directly. If an account with another role
  signs in, it sees the same student-facing app; the only role-specific behaviour is that enrolling
  in one's own course is refused.
- The My Courses list and learning page assemble their information from the existing per-course
  endpoints; no new backend aggregate is introduced for them.
- The session is deliberately confined to one browser tab, so a new tab starts signed out. This
  follows the platform's existing session rules and is not treated as a defect.
- "About ten seconds" is the confirmation window the platform already targets between a successful
  payment and a visible enrollment.
- There is no real payment processing anywhere in this feature; only the three simulated outcomes
  exist.
- Course prices are shown in the single currency the platform already uses; no conversion or
  localisation of amounts is involved.
- Small supporting affordances are included where they directly serve a stated goal and cost
  little: keeping the result set in the page address (FR-009), copying a verification code from the
  list (FR-032), and accepting a pasted code regardless of surrounding spaces or letter case
  (FR-036).

## Dependencies

- The platform must be running for the app to do anything beyond render its own shell; the app has
  no offline mode and no local data of its own.
- The published API contracts for the existing services are the source of truth for every request
  and response shape the app relies on.

## Out of Scope

- Instructor screens and administrator screens of any kind.
- Reviews and the review experience.
- Password reset and email verification.
- Any real payment form, card entry or payment provider integration.
- Translations and any language other than the platform's existing one.
- Dark mode and theming.
- Notification screens in the app (emails continue to be sent by the backend).
- Offline use and installability.
