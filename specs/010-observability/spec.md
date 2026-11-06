# Feature Specification: Platform Observability — Metrics and Dashboards

**Feature Branch**: `010-observability`

**Created**: 2026-11-06

**Status**: Draft

**Input**: User description: "Observability: Prometheus metrics and Grafana dashboards for the whole platform. Operators currently have no way to see how the platform is behaving at runtime. Scope for this feature: metrics only — a scrape endpoint on every service, a metrics collector, two provisioned dashboards, provisioned alert rules, startup and smoke-test integration. Distributed tracing and log aggregation are explicitly out of scope. Constraints: a memory-constrained host, and the scrape endpoint must not be publicly reachable outside the local profile."

## User Scenarios & Testing *(mandatory)*

### User Story 1 - See that a dead-letter queue has stopped messages (Priority: P1)

An operator wants to know, without reading logs or opening a broker console, whether any message in
the platform has failed three times and been set aside. Today a stuck message is silent: the student
never gets their enrollment, and nobody finds out until the student complains.

**Why this priority**: This is the single highest-consequence blind spot. LearnHub runs four
asynchronous event chains (payment → enrollment → completion → certificate → email). A dead-lettered
message means a paying student silently did not get what they paid for. The project's own risk
register already names "DLQ messages go unnoticed" as a live risk with no mitigation.

**Independent Test**: Stop a consuming service, push a message that cannot be processed, restart, and
confirm the operator sees a non-zero dead-letter count on a dashboard and receives a firing alert —
without touching the broker console or any log file.

**Acceptance Scenarios**:

1. **Given** the platform is running and every queue is empty, **When** an operator opens the event
   pipeline dashboard, **Then** every dead-letter count reads zero and no alert is firing.
2. **Given** a message has been dead-lettered, **When** the operator opens the same dashboard within
   one minute, **Then** that queue's dead-letter count reads at least one and names the queue.
3. **Given** a message has been dead-lettered, **When** the configured evaluation window passes,
   **Then** an alert for that queue is in a firing state.
4. **Given** a dead-lettered message is later drained, **When** the next collection happens, **Then**
   the count returns to zero and the alert resolves on its own.

---

### User Story 2 - See whether the platform is healthy right now (Priority: P2)

An operator wants one screen that answers "is anything wrong?" — which services are up, how much
traffic each is taking, how fast they are answering, how many requests are failing, and whether any
service is running out of memory.

**Why this priority**: This is the everyday view. It turns "a user says the site is slow" into a
specific service and a specific symptom. It depends on the same collection mechanism as P1, so it is
cheap once P1 exists, but it is less urgent because a human complaint eventually surfaces these
problems, whereas a stuck message never surfaces at all.

**Independent Test**: With the platform running and demo traffic generated through the web app, open
the platform health dashboard and confirm every service appears with live request, latency, error and
memory readings.

**Acceptance Scenarios**:

1. **Given** the platform is running, **When** an operator opens the platform health dashboard,
   **Then** every service appears with a current up/down state.
2. **Given** a user has browsed the catalog and enrolled in a course, **When** the operator looks at
   the dashboard, **Then** request counts and response times for the services involved are non-zero.
3. **Given** a service is stopped, **When** the collection interval passes, **Then** that service
   shows as down on the dashboard and a service-unreachable alert enters a firing state.
4. **Given** requests are failing with server errors, **When** the operator looks at the dashboard,
   **Then** the error percentage for the affected service is visibly elevated.

---

### User Story 3 - Trust that the monitoring is actually watching everything (Priority: P3)

An operator needs confidence that the monitoring has not quietly stopped seeing part of the platform.
A dashboard that shows "all clear" because a service was never being collected from is worse than no
dashboard at all.

**Why this priority**: Important for trust, but it protects the monitoring rather than the platform.
It only matters once P1 and P2 exist.

**Independent Test**: Run the platform's existing automated smoke test and confirm it fails if any
service is missing from collection, and passes when all are present.

**Acceptance Scenarios**:

1. **Given** the platform has started, **When** the automated smoke test runs, **Then** it asserts
   that every service and the message broker are being collected from successfully, and fails if any
   one of them is not.
2. **Given** a new service is added to the platform without being registered for collection,
   **When** the smoke test runs, **Then** it fails and names what is missing.
3. **Given** the operator starts the platform with the single documented start command, **When**
   startup finishes, **Then** the monitoring tools are running and reachable alongside everything
   else, with no extra step.

---

### Edge Cases

- **A service is still starting up.** It must appear as not-yet-collectable rather than as a failure,
  and must not fire a service-down alert during its normal startup window.
- **The metrics collector restarts.** History for the retention window may be lost, but collection
  must resume automatically with no manual step, and the dashboards must render again without being
  re-imported.
- **A dashboard or alert definition is changed in the running tool's own UI.** The committed
  definitions remain the source of truth; a restart must restore them, so an accidental UI edit cannot
  silently become the real configuration.
- **The host is short of memory.** The monitoring tools must stay within a bounded memory budget and
  must not be the reason the rest of the platform fails to start.
- **Collection history grows unbounded.** Retention must be capped so disk use stays bounded on a
  developer machine.
- **A service exposes no traffic yet.** Panels must show an empty or zero state rather than an error
  or a broken chart.
- **Someone reaches a service's metrics endpoint from outside.** Outside the local development
  profile the endpoint must require credentials, consistent with how the registry and configuration
  server are already protected.

## Requirements *(mandatory)*

### Functional Requirements

**Exposure**

- **FR-001**: Every runnable service in the platform MUST expose its runtime metrics on a dedicated
  endpoint, in a format a collector can read on a schedule.
- **FR-002**: A service MUST gain that endpoint without any service-specific code or configuration of
  its own, so adding a future service needs no metrics wiring.
- **FR-003**: The exposed metrics MUST include, for each service: memory in use and garbage-collection
  activity, request count, response time and outcome per endpoint, and whether the service is up.
- **FR-004**: The metrics endpoint MUST NOT be reachable without credentials outside the local
  development profile, matching the protection already required for the registry and configuration
  server. Health probe endpoints keep their existing exemption.

**Collection**

- **FR-005**: The platform MUST include a collector that gathers metrics from every service and from
  the message broker on a fixed, short interval.
- **FR-006**: The collector MUST retain a short, bounded window of recent history, sized so that disk
  use on a developer machine stays bounded.
- **FR-007**: The collector MUST report, in a form that can be checked automatically, which targets it
  is successfully collecting from and which it is not.
- **FR-008**: Broker metrics MUST include per-queue message depth, including depth of each
  dead-letter queue, and consumer throughput.

**Presentation**

- **FR-009**: The platform MUST present a platform-health view showing, per service: up/down state,
  request rate, response time, error percentage, and memory and garbage-collection activity.
- **FR-010**: The platform MUST present an event-pipeline view showing, per queue: message depth,
  consumer throughput, and dead-letter depth.
- **FR-011**: All views MUST be defined as committed configuration, created automatically on startup,
  so no manual setup or import step is needed and an accidental edit in the running tool does not
  become the source of truth.
- **FR-012**: Access to the presentation tool MUST require credentials, supplied the same way as the
  platform's other service credentials, with no default or blank password.

**Alerting**

- **FR-013**: The platform MUST raise an alert when any dead-letter queue is non-empty.
- **FR-014**: The platform MUST raise an alert when a service stops being reachable by the collector.
- **FR-015**: The platform MUST raise an alert when a service's server-error rate rises above a
  defined threshold.
- **FR-016**: Alert definitions MUST be committed configuration, created automatically on startup, on
  the same basis as the views.
- **FR-017**: An alert MUST resolve by itself once the condition it describes no longer holds.
- **FR-018**: A service in its normal startup window MUST NOT cause a service-unreachable alert to
  fire.

**Operation**

- **FR-019**: The platform's existing single start command MUST bring the monitoring tools up together
  with everything else, with no additional step.
- **FR-020**: The existing pre-start check that confirms required host ports are free MUST cover the
  monitoring tools' ports, so a port conflict is reported clearly before anything starts.
- **FR-021**: The existing automated smoke test MUST assert that the collector is reachable, that
  every expected target is being collected from successfully, and that the presentation tool is
  reachable.
- **FR-022**: The monitoring tools MUST operate within an explicitly bounded memory budget, so they
  cannot be the cause of the rest of the platform failing to start on a constrained host.
- **FR-023**: Collected history MUST survive a restart of the monitoring tools, and MUST be removable
  by the platform's existing data-reset command along with all other stored data.

### Key Entities

- **Metric sample**: One named measurement from one service at one moment, carrying labels that
  identify which service, endpoint and outcome it describes.
- **Scrape target**: A service or broker the collector is expected to gather from, with a current
  success/failure state. The set of targets is the definition of "everything being watched".
- **View (dashboard)**: A named, committed arrangement of panels over collected metrics, intended to
  answer one operator question.
- **Alert rule**: A named, committed condition over collected metrics, with a threshold and an
  evaluation window, that moves between resting and firing on its own.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: An operator who has never seen the platform before can tell whether anything is wrong
  within 30 seconds of opening the platform-health view, without reading a log file.
- **SC-002**: A dead-lettered message becomes visible to an operator within 60 seconds of being
  dead-lettered, and raises an alert within 2 minutes.
- **SC-003**: 100% of runnable services appear as successfully collected targets after a normal start,
  verified automatically rather than by eye.
- **SC-004**: Adding a future service requires no metrics-specific code in that service — demonstrated
  by the fact that no existing service contains any.
- **SC-005**: Both views and all alert rules are present and populated after a fresh start with no
  manual setup, import or click.
- **SC-006**: The monitoring tools together add no more than 500 MB of memory use to the running
  platform, and the full platform still starts successfully on the project's existing constrained
  host.
- **SC-007**: The platform's automated smoke test fails if any service is missing from collection, so
  a monitoring gap cannot pass unnoticed.
- **SC-008**: Stored metrics history stays within a bounded size indefinitely, with no manual pruning.
- **SC-009**: Service startup produces no false service-unreachable alerts during a normal platform
  start.

## Assumptions

- **Audience is the operator, not the end user.** These views are for whoever runs the platform.
  Nothing in this feature appears in the student or instructor web app. In particular this feature is
  distinct from, and does not overlap with, the existing instructor statistics: those are business
  counts of enrollments and revenue for instructors, whereas this feature is technical telemetry about
  the running system for operators.
- **Single-host development deployment.** The platform runs as one set of containers on one machine.
  The set of services is known and fixed at start time, so the collector may be told what to collect
  from by committed configuration rather than discovering it dynamically. A future multi-host or
  cloud deployment may need dynamic discovery; that is out of scope here.
- **The message broker can already report its own metrics**, so no change to the broker beyond
  configuration is assumed necessary. This is to be confirmed during planning.
- **Metrics only.** Distributed tracing (following one user request across services and events) and
  log aggregation (searching logs centrally) are deliberately excluded and will be separate features.
  Correlation IDs already in the logs remain the way to follow one request for now.
- **Short retention is acceptable.** This is a development and demonstration platform, so a window of
  roughly a day of history is enough; long-term capacity trends are not a goal.
- **Business-level alerting is out of scope.** Alerts here are technical (a queue is stuck, a service
  is down, errors are elevated). Alerts about business conditions, and delivery of alerts to email,
  chat or a pager, are not included — an alert firing in the tool's own UI satisfies this feature.
- **This feature introduces new technology** and therefore depends on an amendment to the project
  constitution before planning can pass its Constitution Check. The amendment must also record the
  two new host ports and the rule that the metrics endpoint is protected outside the local profile.
- **Custom business-process metrics are a stretch goal.** Measures such as transactional-outbox lag
  would need a small amount of per-service instrumentation. They are desirable but not required for
  this feature to deliver value, and should be deferred if they threaten the no-wiring-code rule in
  FR-002.
