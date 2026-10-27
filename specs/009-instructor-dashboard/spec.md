# Feature Specification: Instructor Dashboard

**Feature Branch**: `009-instructor-dashboard`

**Created**: 2026-10-27

**Status**: Draft

**Input**: User description: "Add an instructor dashboard to LearnHub, backend and frontend. This covers a new analytics-service under backend/, its gateway routes, one small addition to course-service, and the first instructor pages in frontend/. Who it's for: instructors, who want to know how their courses are doing, and administrators, who want a platform-wide view. Overview of totals and a per-course table (enrollments, revenue, completions, certificates, completion rate) sortable by enrollments, revenue or completion rate, including draft and archived courses; a course detail view with totals, a funnel and enrollments per day for 7, 30 or 90 days (default 30) with a table alternative to the chart; instructors see only their own courses and someone else's course looks the same as one that does not exist; administrators see everything; students and visitors cannot reach the dashboard; no student identities, only counts; new activity visible within about 10 seconds, with a refresh button and last-updated time; duplicate or replayed events never count twice and a persistently failing event is set aside without blocking others; if course ownership cannot be confirmed the dashboard says data is temporarily unavailable and shows no numbers. Money in USD from whole cents, days in UTC, standard error structure with a reference code, phone and desktop layouts with loading, empty and error states. Out of scope: export, review-rating charts, payouts or refunds, per-student details, notifications or email reports, creating or editing courses in the web app, real-time push, and counting activity from before the analytics service first started."

**Touches**: backend (new `backend/analytics-service`, its gateway routes, one small addition to `backend/course-service`) and frontend (`frontend/`, the first instructor pages).

## User Scenarios & Testing *(mandatory)*

### User Story 1 - See how all my courses are doing (Priority: P1)

An instructor opens the dashboard and sees totals across their own courses: students enrolled, revenue, completions and certificates issued. Below the totals, a table lists every course they own, including drafts and archived ones, with the same four numbers plus the completion rate. They can sort the table by enrollments, revenue or completion rate.

**Why this priority**: This is the core value of the feature: one place to see how teaching is going. It is useful on its own even without the course detail view.

**Independent Test**: Sign in as an instructor who owns several courses with different activity (including a draft with none), open the dashboard, and check that the totals equal the sums of the rows and that sorting reorders the rows.

**Acceptance Scenarios**:

1. **Given** an instructor with several courses that have activity, **When** they open the dashboard, **Then** the totals equal the sum of the per-course rows for enrollments, revenue, completions and certificates.
2. **Given** a course with 4 enrollments and 1 completion, **When** the table is shown, **Then** its completion rate is 25%.
3. **Given** a draft or archived course with no activity, **When** the table is shown, **Then** it appears as a row with zeros and a 0% completion rate.
4. **Given** a course with zero enrollments, **When** its completion rate is shown, **Then** it reads 0% and no error or "not a number" appears.
5. **Given** the table, **When** the instructor sorts by enrollments, revenue or completion rate, **Then** the rows reorder accordingly.
6. **Given** an instructor who owns no courses, **When** they open the dashboard, **Then** they see an empty state explaining that there is nothing to show yet, with all totals at zero.

---

### User Story 2 - Look closely at one course (Priority: P1)

An instructor opens one of their courses from the table and sees its totals, a simple funnel (enrolled, completed, certificate issued) and a chart of enrollments per day for the last 7, 30 or 90 days (30 by default). Days with no enrollments show as zero. The same numbers are also available as a table, so keyboard and screen-reader users get the full information.

**Why this priority**: Instructors need to see whether interest is growing and where learners drop off; the per-course view answers that.

**Independent Test**: Open a course with enrollments spread over several days, switch between 7, 30 and 90 days, and compare the chart and its table alternative with known data, including days with no activity.

**Acceptance Scenarios**:

1. **Given** a course with activity, **When** its detail page opens, **Then** it shows the course totals and the funnel counts for enrolled, completed and certificate issued.
2. **Given** the detail page, **When** no range is chosen, **Then** the last 30 days are shown; choosing 7 or 90 changes the range.
3. **Given** a day with no enrollments inside the range, **When** the daily series is shown, **Then** that day appears with zero rather than being missing.
4. **Given** the chart, **When** a keyboard or screen-reader user looks for the data, **Then** the same daily numbers are available as a table with the same range.
5. **Given** a course that belongs to a different instructor, **When** the instructor opens its address, **Then** the page behaves exactly as for a course that does not exist.
6. **Given** a course that exists in the instructor's list but has no activity, **When** its detail page opens, **Then** zeros and an empty-activity message are shown.

---

### User Story 3 - Only the right people see the numbers (Priority: P1)

Instructors see only their own courses. Administrators see platform-wide totals and can open any course. Students and visitors who are not signed in cannot reach the dashboard and are told clearly that they are not allowed. No screen ever shows who the students are, only counts.

**Why this priority**: Course performance and revenue are business-sensitive, and student identities are personal data; a leak is worse than a missing feature.

**Independent Test**: Sign in as instructor A, instructor B, an administrator and a student in turn, and check what each can see and open; confirm no student name, email or id appears anywhere.

**Acceptance Scenarios**:

1. **Given** two instructors with their own courses, **When** instructor A uses the dashboard, **Then** none of instructor B's courses, totals or activity appears.
2. **Given** instructor A knows the address of instructor B's course, **When** they ask for it, **Then** the response is identical to the response for a course that does not exist, so they cannot tell which one it is.
3. **Given** an administrator, **When** they open the dashboard, **Then** they see platform-wide totals and can open any course's detail.
4. **Given** a signed-in student, **When** they try to open the dashboard, **Then** they see a clear "not allowed" message with a reference code and no numbers.
5. **Given** a visitor who is not signed in, **When** they try to open the dashboard, **Then** they are asked to sign in and see no numbers.
6. **Given** any dashboard screen or response, **When** it is inspected, **Then** it contains counts and amounts only, never a student's name, email or identifier.

---

### User Story 4 - Numbers stay fresh and trustworthy (Priority: P2)

When a student pays, enrolls, completes a course or receives a certificate, the instructor's figures change within about 10 seconds. The page shows a "last updated" time and a refresh button. A duplicated or replayed notification never counts twice, and one that keeps failing is set aside without holding up the others.

**Why this priority**: Stale or inflated numbers destroy trust, but the dashboard is still valuable if freshness takes a moment longer than the target.

**Independent Test**: As a student, pay for, learn and finish a published course while an instructor's dashboard is open; press refresh and see each figure change within 10 seconds; replay the same activity notification and confirm nothing changes.

**Acceptance Scenarios**:

1. **Given** a published course, **When** a student pays, **Then** enrollments and revenue for that course and the instructor's totals rise within about 10 seconds of a refresh.
2. **Given** an enrolled student, **When** they complete the course and receive a certificate, **Then** completions and certificates rise within about 10 seconds of a refresh.
3. **Given** the dashboard is open, **When** the instructor presses refresh, **Then** the figures and the "last updated" time update.
4. **Given** an activity notification that is delivered twice or replayed, **When** it is processed again, **Then** no figure changes.
5. **Given** a notification that fails every time it is processed, **When** the retries are used up, **Then** it is set aside in the holding area and later notifications are still processed.
6. **Given** a second instructor, **When** the first instructor's students pay, **Then** the second instructor's figures do not change.

---

### User Story 5 - Never show numbers for the wrong person (Priority: P2)

If the system cannot confirm which courses belong to the signed-in instructor, the dashboard says the data is temporarily unavailable and shows no numbers at all, rather than guessing or showing a stale or wrong set.

**Why this priority**: Showing another instructor's numbers, or an empty list that looks like "you have no courses", would be misleading; failing safely is part of the privacy promise.

**Independent Test**: Make the course ownership information unavailable and open the dashboard as an instructor; confirm an unavailable message with a reference code and no numbers, then restore it and confirm the numbers return.

**Acceptance Scenarios**:

1. **Given** course ownership cannot be confirmed, **When** an instructor opens the dashboard, **Then** a "temporarily unavailable" message with a reference code is shown and no totals or rows appear.
2. **Given** ownership becomes available again, **When** the instructor refreshes, **Then** the correct figures are shown.
3. **Given** ownership cannot be confirmed, **When** the figures would otherwise have been shown, **Then** no numbers from any other course or instructor are shown.

---

### Edge Cases

- A course with enrollments but no revenue (a free course) shows revenue of $0.00 and still counts enrollments.
- A completion or certificate arriving for a course whose enrollment was not recorded (for example activity from before the service started) is not counted toward earlier history; only activity seen after the service started is counted.
- The completion rate never exceeds 100% in the display; if completions somehow exceed enrollments for a course, the rate is capped and the case is not shown as an error.
- Dates are by UTC day: an enrollment at 23:30 UTC and one at 00:30 UTC the next day fall on different days regardless of the viewer's time zone.
- A very new course has fewer days of history than the chosen range; the missing earlier days show as zero.
- An instructor owning more than 200 courses sees figures for their 200 newest courses, with a notice saying that totals cover only those; the notice is also shown at exactly 200 because the limit cannot be told apart from a full list.
- A period selection that is not 7, 30 or 90 is refused with the standard error structure.
- A failed request shows a clear error with its reference code and a way to retry; the rest of the page stays usable.
- The page is used on a phone: tables and the chart remain readable without horizontal page scrolling of the whole layout.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The system MUST show an instructor totals across their own courses for students enrolled, revenue, completions and certificates issued.
- **FR-002**: The system MUST show one table row per course the instructor owns, with enrollments, revenue, completions, certificates issued and completion rate, including draft and archived courses (zeros when there is no activity).
- **FR-003**: The completion rate MUST be completions divided by enrollments and MUST show as 0% when a course has no enrollments.
- **FR-004**: The table MUST be sortable by enrollments, revenue and completion rate.
- **FR-005**: The system MUST show an instructor, for one of their courses, its totals, a funnel of enrolled, completed and certificate issued, and enrollments per day for the last 7, 30 or 90 days, defaulting to 30.
- **FR-006**: Days with no enrollments inside the chosen range MUST appear with a count of zero.
- **FR-007**: The daily enrollment chart MUST have an equivalent table of the same data that keyboard and screen-reader users can use.
- **FR-008**: An instructor MUST see only the courses they own; a request for another instructor's course MUST produce exactly the same response as a request for a course that does not exist.
- **FR-009**: An administrator MUST be able to see platform-wide totals and open the detail for any course.
- **FR-010**: Students and visitors who are not signed in MUST NOT be able to see any dashboard data; a signed-in student MUST see a clear not-allowed message with a reference code.
- **FR-011**: The dashboard MUST NOT expose any student identity (name, email or identifier) in any screen or response; it exposes counts and amounts only.
- **FR-012**: New payments, enrollments, completions and certificates MUST be reflected in the figures within about 10 seconds.
- **FR-013**: The page MUST show when its figures were last updated and provide a refresh control.
- **FR-014**: Processing the same activity notification more than once, including a replay, MUST NOT change any figure.
- **FR-015**: A notification that keeps failing MUST be set aside in the platform's holding area after its retries and MUST NOT block the processing of other notifications.
- **FR-016**: When the system cannot confirm which courses belong to an instructor, it MUST show a "temporarily unavailable" message with a reference code and MUST show no numbers.
- **FR-017**: Money MUST be shown in US dollars, derived from whole cents, with no rounding drift in totals.
- **FR-018**: Days MUST be counted in UTC.
- **FR-019**: Every error MUST use the platform's standard error structure and the app MUST show its reference code to the user.
- **FR-020**: Dashboard pages MUST work on phone and desktop and MUST provide loading, empty and error states.
- **FR-021**: The dashboard MUST count only activity from after the analytics capability first starts; earlier activity is not backfilled.
- **FR-023**: When the figures cover only an instructor's 200 newest courses, the dashboard MUST say so rather than present partial totals as complete.
- **FR-022**: The new capability MUST be reachable only through the platform's gateway, follow the platform's established conventions for services, events and security, and leave existing backend and frontend behaviours unchanged.

### Key Entities

- **Course Activity Totals**: per course, counts of enrollments, completions and certificates issued, and total revenue in cents; never contains student identities.
- **Daily Enrollment Count**: for one course and one UTC day, the number of enrollments.
- **Instructor Course List**: the set of courses (including drafts and archived) that belong to one instructor, supplied by the course owner of record at the time of the request.
- **Dashboard Summary**: the totals and per-course rows shown to one instructor, or the platform-wide equivalent for an administrator, with a last-updated time.
- **Activity Notification**: a record that a payment, enrollment, completion or certificate happened, identified uniquely so repeated delivery can be recognised.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: For an instructor with a published course, enrollments, revenue, completions and certificates each change on the dashboard within 10 seconds of a student paying, learning and finishing.
- **SC-002**: A second instructor sees none of the first instructor's courses or activity, and a request for another instructor's course is indistinguishable from one for a course that does not exist.
- **SC-003**: A student who tries to open the dashboard sees a clear not-allowed message with a reference code and no figures.
- **SC-004**: Replaying the same activity notification twice changes no figure.
- **SC-005**: Across every dashboard screen and response, zero student names, emails or identifiers are present.
- **SC-006**: When course ownership cannot be confirmed, 100% of requests show an unavailable message and no numbers.
- **SC-007**: An instructor can find which of their courses has the highest completion rate in under 15 seconds from opening the dashboard.
- **SC-008**: Every acceptance scenario above is covered by an automated test that passes.
- **SC-009**: The earlier backend and frontend behaviours still pass their existing tests.

## Assumptions

- Instructors and administrators are existing roles in the platform; the course list for an instructor comes from the course owner (course-service) at the time of each request, which is the one small addition needed there.
- "Revenue" is the sum of successful payments for a course, in whole cents shown as USD; free courses contribute zero. Refunds and payouts are out of scope and are not subtracted.
- "Enrollments", "completions" and "certificates issued" are counts of distinct activity records for the course as announced by the existing platform notifications; a given record counts once.
- "Last updated" shows when the displayed figures were last fetched; the refresh control fetches them again.
- The 10-second freshness target applies from the moment the underlying activity is announced by the platform to the figure being visible after a refresh.
- Activity before the analytics capability first started is not counted and is not backfilled.
- The first instructor pages live inside the existing student web app, reachable only by signed-in instructors and administrators, and follow its existing conventions for sign-in, errors and layout.
- Per-student details, exports, review-rating charts, notifications or email reports, creating or editing courses in the web app, and real-time push are out of scope.
- Where the dashboard needs another service's data it fetches it when a request arrives, not while processing activity notifications (per constitution principle XIV).
