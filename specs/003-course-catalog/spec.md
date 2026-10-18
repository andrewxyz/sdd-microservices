# Feature Specification: Course Catalog

**Feature Branch**: `003-course-catalog`

**Created**: 2026-10-18

**Status**: Draft

**Input**: User description: "Add the course catalog to LearnHub. This spec covers the backend only (a new course-service under backend/ and the entry point's routing rules for it). The frontend is not touched. ... (full text provided by user)"

## Clarifications

### Session 2026-10-18

- Q: Should an administrator be able to create a brand-new course (becoming its owning instructor of record), or should course creation stay instructor-only, with administrators limited to editing or moderating courses instructors have already created? → A: Administrators can create a course too, becoming its owning instructor of record, same as any instructor.
- Q: When an edit to an already-published course would leave it missing something publishing requires, should the platform refuse that edit outright, or accept it and automatically move the course back to draft? → A: Refuse the edit outright, with the same "what's missing" message publishing would give.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Browse and view the public catalog (Priority: P1)

Anyone, without logging in, can list published courses, page through them, sort and filter them, search
by keyword, and open one to see its details and lesson outline.

**Why this priority**: The catalog is the product's front door; nothing else in this feature matters if
the one thing every visitor can already do — look at what's for sale — doesn't work.

**Independent Test**: Can be fully tested by calling the public list and detail endpoints with no
credentials at all, against a mix of published, draft and archived courses, and confirming only
published ones ever appear.

**Acceptance Scenarios**:

1. **Given** a mix of draft, published and archived courses, **When** an anonymous visitor lists the
   catalog, **Then** only published courses appear, and never a draft or an archived one.
2. **Given** more published courses than fit on one page, **When** a visitor pages through the list,
   **Then** every published course appears exactly once across the pages, in a stable order.
3. **Given** published courses at different prices, **When** a visitor sorts by "price low to high" or
   "price high to low", **Then** the results are ordered accordingly.
4. **Given** published courses in different categories and levels, **When** a visitor filters by
   category, by level, or by free-versus-paid, **Then** only matching published courses appear.
5. **Given** a keyword that matches no course's title or description, **When** a visitor searches for
   it, **Then** the result is an empty list, not an error.
6. **Given** a published course, **When** an anonymous visitor opens it, **Then** they see its title,
   description, category, level, language, price, instructor, and its lesson outline (each lesson's
   title, order and duration) — but no lesson's text content.
7. **Given** a draft course, an archived course, or an identifier that matches no course at all, **When**
   an anonymous visitor tries to open it, **Then** all three produce the identical "not found" response.

---

### User Story 2 - Instructors create and manage their courses (Priority: P1)

A logged-in instructor creates a course as a draft, edits it, and manages its lessons, seeing all of
their own courses including drafts — but never anyone else's.

**Why this priority**: Without this, there is no catalog to browse in the first place; tied for highest
priority with Story 1, and a prerequisite for the publish/archive story below.

**Independent Test**: Can be fully tested by one instructor account creating a draft course, adding and
reordering lessons, and confirming a second instructor account cannot read or change it, and that a
student account cannot create one at all.

**Acceptance Scenarios**:

1. **Given** a logged-in instructor, **When** they create a course, **Then** it is created as a draft
   owned by them.
2. **Given** a logged-in student, **When** they try to create a course, **Then** the request is refused.
3. **Given** an instructor's own course, **When** they edit its details or add, edit, remove or reorder
   its lessons, **Then** the change is saved.
4. **Given** an instructor's own course, **When** they list their courses, **Then** the list includes
   every status, including drafts.
5. **Given** a course owned by a different instructor, **When** an instructor tries to view its full
   detail, edit it, or manage its lessons, **Then** the request is refused.
6. **Given** a draft course, **When** its owning instructor deletes it, **Then** it no longer exists.
7. **Given** an administrator, **When** they edit, delete a draft of, or manage the lessons of any
   instructor's course, **Then** the change is accepted the same as it would be for the owning
   instructor.

---

### User Story 3 - Publish and archive (Priority: P2)

An instructor publishes a draft once it is complete, taking it live in the catalog; archiving later
pulls it back out without destroying it, and it can be republished.

**Why this priority**: Depends on Story 2 (a course and its lessons must exist first) and is what makes
Story 1's catalog ever show anything real.

**Independent Test**: Can be fully tested by publishing a complete draft and confirming it now appears
in the public catalog (Story 1), then archiving it and confirming it disappears again without being
deleted, then publishing it a second time.

**Acceptance Scenarios**:

1. **Given** a draft with a title, a description, a category, a price and at least one lesson, **When**
   its owner publishes it, **Then** it becomes published and appears in the public catalog.
2. **Given** a draft missing one or more of those things, **When** its owner tries to publish it,
   **Then** the request is refused with a message naming every missing part.
3. **Given** a published course, **When** its owner archives it, **Then** it no longer appears in the
   public catalog, but it still exists and all of its data is intact.
4. **Given** an archived course, **When** its owner publishes it again, **Then** it reappears in the
   public catalog.
5. **Given** a published course, **When** anyone tries to delete it, **Then** the request is refused.

---

### User Story 4 - Categories (Priority: P2)

Administrators maintain the set of categories courses are organized under.

**Why this priority**: Courses can exist and even be browsed with a flat category list seeded once, but
category management rounds out the feature and is needed before Story 1's category filter is fully
meaningful across a growing catalog.

**Independent Test**: Can be fully tested by an administrator creating a category, renaming it, and
confirming a non-administrator cannot do either, and that deleting a category still in use by a course is
refused.

**Acceptance Scenarios**:

1. **Given** a logged-in administrator, **When** they create a category with a name not already in use,
   **Then** it is created.
2. **Given** a category name already in use, **When** an administrator tries to create another category
   with that name, **Then** the request is refused.
3. **Given** a logged-in administrator, **When** they rename a category, **Then** every course already
   in that category keeps its (now renamed) category.
4. **Given** a category with no courses in it, **When** an administrator deletes it, **Then** it no
   longer exists.
5. **Given** a category that at least one course still uses, **When** an administrator tries to delete
   it, **Then** the request is refused.
6. **Given** a non-administrator (including an instructor), **When** they try to create, rename or
   delete a category, **Then** the request is refused.

---

### User Story 5 - Lesson content stays private until enrollment (Priority: P3)

Only a course's owning instructor and administrators can read a lesson's actual text content; everyone
else sees only the lesson outline (Story 1), with full access for enrolled students arriving in a later
spec.

**Why this priority**: A real but narrow privacy boundary within data already covered by Stories 1 and
2; it doesn't unlock new capability on its own, so it is last.

**Independent Test**: Can be fully tested by confirming the owning instructor and an administrator can
read a lesson's full content while a different instructor, a student, and an anonymous caller cannot.

**Acceptance Scenarios**:

1. **Given** a lesson on their own course, **When** the owning instructor reads it, **Then** they see
   its full text content.
2. **Given** a lesson on any course, **When** an administrator reads it, **Then** they see its full text
   content.
3. **Given** a lesson on a course they do not own, **When** a different instructor, a student, or an
   anonymous caller tries to read its full content, **Then** the request is refused (or, for the public
   detail view, simply not included — see Story 1).

---

### User Story 6 - Other services look up a course (Priority: P3)

Another backend service (not a client, and not through the entry point) asks for a course by its
identifier and gets back the facts it needs to make its own decision: status, price, currency,
instructor and lesson count.

**Why this priority**: Nothing in this feature depends on it, but later specs (payment, enrollment) do;
building it now, while the course data model is fresh, avoids rework.

**Independent Test**: Can be fully tested by one backend service calling course-service directly (not
through the gateway) for a known course identifier and an unknown one, and confirming the entry point
itself has no route that reaches this lookup.

**Acceptance Scenarios**:

1. **Given** a course identifier that exists, **When** another service looks it up, **Then** it
   receives the course's status, price, currency, instructor identifier and lesson count — regardless of
   whether the course is a draft, published or archived.
2. **Given** a course identifier that does not exist, **When** another service looks it up, **Then** it
   receives a clear "not found" result.
3. **Given** the entry point's full set of routes, **When** anything outside the platform's own services
   tries to reach this lookup, **Then** no route exists for it.

---

### Edge Cases

- What happens when an edit to an already-published course would remove the last thing keeping it
  publish-eligible (for example, deleting its only lesson, or clearing its category)? The edit is
  refused with the same "what's missing" message publishing would give, so a published course can never
  be left in a state that would not itself have been publishable (Clarifications, 2026-10-18).
- What happens when two people edit the same course at the same time? The second save to arrive is
  refused with a clear conflict response naming that the course changed underneath it, rather than
  silently overwriting the first save.
- What happens when an instructor reorders lessons using a position that doesn't exist, or duplicates a
  position? The request is refused with a message naming the problem; no partial reorder is applied.
- What happens when someone searches or filters using a category that doesn't exist? Treated the same as
  "no matches": an empty list, not an error.
- What happens when a course's category is deleted while the course still references it? Prevented
  outright — Story 4 already refuses to delete a category any course still uses.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The platform MUST let anyone, without logging in, list published courses with pagination.
- **FR-002**: The published-course list MUST support sorting by newest, title, price low-to-high, and
  price high-to-low.
- **FR-003**: The published-course list MUST support filtering by category, by level, and by whether the
  course is free (price is zero) or paid (price is greater than zero).
- **FR-004**: The published-course list MUST support a keyword search matching the course's title or
  description, and MUST return an empty list rather than an error when nothing matches.
- **FR-005**: A draft or archived course MUST NOT appear in the published-course list or search results,
  under any filter or sort combination.
- **FR-006**: The platform MUST let anyone, without logging in, view a single published course's title,
  description, category, level, language, price, instructor, and lesson outline (each lesson's title,
  order and duration), without exposing any lesson's text content.
- **FR-007**: A request to view a draft course, an archived course, or a non-existent course identifier,
  by someone who is not that course's owner or an administrator, MUST produce the identical "not found"
  response in all three cases.
- **FR-008**: The platform MUST let a logged-in instructor or administrator create a course, which MUST
  start as a draft owned by whichever of them created it (spec.md Clarifications, 2026-10-18).
- **FR-009**: The platform MUST refuse course creation by a logged-in student.
- **FR-010**: The platform MUST let a course's owning instructor, or any administrator, edit the
  course's details and add, edit, remove and reorder its lessons.
- **FR-011**: The platform MUST refuse an attempt by anyone other than a course's owning instructor or
  an administrator to edit that course or manage its lessons.
- **FR-012**: The platform MUST let a logged-in instructor list their own courses in every status,
  including drafts, and MUST NOT include another instructor's courses in that list.
- **FR-013**: The platform MUST let a draft course be deleted by its owning instructor or an
  administrator, and MUST refuse to delete a published course under any circumstance.
- **FR-014**: The platform MUST let a course's owning instructor or an administrator publish a draft
  only when it has a title, a description, a category, a price, and at least one lesson, and MUST refuse
  the attempt with a message naming every missing part otherwise.
- **FR-015**: A course's price of zero MUST be treated as valid and complete for publishing (a free
  course), not as a missing price.
- **FR-016**: The platform MUST let a course's owning instructor or an administrator archive a published
  course, after which it MUST NOT appear in the public catalog (FR-001, FR-006) while remaining fully
  intact and retrievable by its owner, an administrator, or the service lookup (FR-023).
- **FR-017**: The platform MUST let a course's owning instructor or an administrator publish an archived
  course again, subject to the same completeness check as FR-014.
- **FR-018**: An edit that would leave a published course no longer meeting the FR-014 completeness
  check MUST be refused outright, with the same "what's missing" message publishing would give, never
  silently demoting the course back to draft (spec.md Clarifications, 2026-10-18).
- **FR-019**: The platform MUST let an administrator create, rename, and delete categories.
- **FR-020**: The platform MUST refuse category creation or rename that would duplicate an existing
  category's name.
- **FR-021**: The platform MUST refuse to delete a category that any course currently references.
- **FR-022**: The platform MUST refuse category creation, rename, and deletion by anyone who is not an
  administrator.
- **FR-023**: The platform MUST let another backend service retrieve, by course identifier, a course's
  status, price, currency, instructor identifier, and lesson count, regardless of the course's status,
  and MUST NOT expose this lookup through the entry point to any client outside the platform's own
  services.
- **FR-024**: The platform MUST let a course's owning instructor or an administrator read a lesson's
  full text content, and MUST refuse that read for anyone else (including a different instructor or a
  student without a later-spec enrollment).
- **FR-025**: The platform MUST refuse a save (an edit, a lesson change, a publish, an archive, or a
  category change) that targets data which changed since the caller last read it, with a clear
  conflict response, rather than silently applying the save over the intervening change.
- **FR-026**: Every price MUST be a whole, non-negative number in the smallest unit of its currency, and
  every course's currency MUST be USD.
- **FR-027**: An invalid request MUST be refused with a message naming the specific field that failed
  and why, using the platform's standard error structure with the correlation ID.
- **FR-028**: Course-service's course, lesson, and category data MUST be stored in a database dedicated
  to course-service, not shared with any other service's data.

### Key Entities

- **Course**: A single offering in the catalog. Attributes: unique identifier, title, description,
  category, level, language, price (whole units of USD cents, zero or more), owning instructor
  (identifier only — the account itself lives in auth-service), status (`DRAFT`, `PUBLISHED`,
  `ARCHIVED`), creation and last-updated times, and a change marker used to detect concurrent edits
  (FR-025). Has one or more lessons once it has any. Belongs to exactly one category once it has one.
- **Lesson**: One unit of a course's content. Attributes: unique identifier, owning course, title,
  position within the course (defines its order), duration in minutes, and text content. Only the
  owning instructor and administrators may ever read the content attribute (FR-024); everyone permitted
  to see the course at all may see the title, position and duration (the "outline," FR-006).
- **Category**: A named grouping courses are organized under. Attributes: unique identifier, name
  (unique, case-sensitivity per Assumptions). Cannot be removed while any course references it (FR-021).

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: An instructor can go from having no courses to a published course — create, add a lesson,
  publish — using only the entry point, in under 5 minutes, with no other interface involved.
- **SC-002**: Across at least 50 varied catalog requests mixing drafts, published and archived courses,
  100% of anonymous responses contain only published courses and never a draft or archived one.
- **SC-003**: Across at least 20 varied attempts, 100% of attempts by a non-owner, non-administrator to
  modify a course (edit, lesson change, publish, archive, or delete) are refused.
- **SC-004**: With 10,000 published courses in the catalog, a filtered, sorted, paginated list request
  returns within 2 seconds.
- **SC-005**: Every search for a keyword with no matching course returns an empty list, never an error,
  across at least 20 varied searches.
- **SC-006**: When two updates to the same course are submitted concurrently, exactly one succeeds and
  the other receives a clear conflict response, across at least 10 repeated trials, with zero silent
  overwrites.
- **SC-007**: A duplicate category name is refused, and a category still referenced by a course is never
  deletable, across at least 10 varied attempts of each.
- **SC-008**: All platform behaviors already relied upon before this feature — startup, service
  discovery, central configuration pickup, entry-point rate limiting, and account/login/role
  enforcement — continue to pass their existing checks unchanged after this feature is added.

## Assumptions

- "Instructor" and "administrator" are the roles LearnHub's accounts feature already defines; this
  feature adds no new role and relies on the entry point to identify the caller and their role, the same
  way it already does for the echo service's protected path.
- This spec does not add a separate "assign to another instructor" action: an administrator who creates
  a course (Clarifications, 2026-10-18) owns it the same way an instructor-created course is owned.
- "Newest" sorting orders by when a course was most recently published (becoming visible), not by when
  its draft was first created, since a never-published course never appears in this ordering at all.
- Keyword search is a case-insensitive, whole-phrase match against the title or description — not a
  multi-word boolean or ranked search engine; that remains a reasonable place to improve later without
  changing this spec's intent.
- List pagination defaults to a reasonable page size (20) with a reasonable maximum (100) when the
  caller does not specify one; exact numbers are configuration, not user-facing scope.
- Category names are compared for uniqueness without regard to letter case, consistent with how email
  uniqueness already works for accounts.
- The "no route exists" requirement for the service-to-service lookup (FR-023) is enforced by the entry
  point's routing table, the same mechanism that already marks routes public or protected; the lookup
  itself needs no additional authentication beyond services being reachable only inside the platform's
  own network, consistent with how every other internal, non-client-facing call already works.
- Archiving and un-archiving are unlimited; nothing in this feature caps how many times a course may be
  published and archived again.
