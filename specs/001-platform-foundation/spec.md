# Feature Specification: Backend Platform Foundation

**Feature Branch**: `001-platform-foundation`

**Created**: 2026-10-17

**Status**: Draft

**Scope**: Backend only. Touches `backend/` (all services and shared code, one folder per service) and
the repo-root files `docker-compose.yml`, `.env.example` and `README.md`. The `frontend/` folder is
not touched and stays empty.

**Input**: User description: "Build the backend foundation of LearnHub, an online learning platform
made of microservices. There is no business domain yet. The goal is that a developer can start the
whole platform locally with one command, and every service we build later can plug in without extra
setup."

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Start the whole platform with one command (Priority: P1)

A developer clones the repository, copies the example settings file, and runs one command from the
repo root. Every platform component starts in the right order, reports whether it is healthy, and
the platform is ready to use. If something is wrong (a missing setting, a port already in use), the
developer is told exactly what, before or at startup, instead of seeing a confusing partial failure.

**Why this priority**: Nothing else can be built, tested or demonstrated until the platform runs. This
is the base every other story depends on and the main promise of the foundation.

**Independent Test**: On a clean checkout, run the single startup command and wait. Every component
reports healthy within 3 minutes. Then stop, restart, and finally run the documented reset.

**Acceptance Scenarios**:

1. **Given** a clean checkout with the example settings copied and filled in, **When** the developer
   runs the one startup command from the repo root, **Then** all components start in dependency order
   and every one reports healthy within 3 minutes.
2. **Given** a required setting is missing, **When** the developer runs the startup command, **Then**
   startup stops and a message names the missing setting.
3. **Given** one of the host ports in the port table is already occupied, **When** the developer runs
   the startup command, **Then** startup stops and a message names the port and the component that
   needs it.
4. **Given** the platform is running, **When** the developer stops it and starts it again, **Then**
   it becomes healthy again without any manual cleanup and previously stored data is still present.
5. **Given** the platform has stored data, **When** the developer runs the documented reset
   procedure, **Then** all stored data is wiped and the next start behaves like a clean checkout.

---

### User Story 2 - Services find each other by name (Priority: P1)

A developer starts any service and sees it appear on a registry dashboard under its service name.
Other services and the entry point address it by that name, never by host or port. Starting two copies
of the same service spreads calls across both. A short registry outage does not break services that are
already running.

**Why this priority**: Every later service depends on name-based lookup; without it, no service can
call another or be routed to.

**Independent Test**: Start the registry and the placeholder echo service, open the dashboard, then
start a second echo copy, send repeated calls by name, and stop/restart the registry.

**Acceptance Scenarios**:

1. **Given** the registry is healthy, **When** a service starts, **Then** it appears on the dashboard
   under its service name within about 30 seconds.
2. **Given** a service is listed on the dashboard, **When** it is stopped, **Then** it disappears from
   the dashboard within about 30 seconds.
3. **Given** two copies of the same service are registered, **When** a caller makes repeated calls by
   service name, **Then** calls are spread across both copies.
4. **Given** services are running, **When** the registry goes down briefly, **Then** running services
   keep serving requests, and when the registry returns they reconnect and are listed again without a
   manual restart.

---

### User Story 3 - Central configuration for every service (Priority: P2)

A developer keeps all settings in one central place. Each service receives shared defaults, its own
settings and the overrides for the current environment. Changing a setting reaches a running service
without rebuilding or restarting it. If the central configuration is unavailable when a service
starts, the service waits and retries for a short while, then fails with a clear message instead of
running with missing settings.

**Why this priority**: Gateway, echo service and all later services need their settings from one place,
but the platform can already start and register (Stories 1 and 2) without it.

**Independent Test**: Start the configuration service and the echo service, change one of the echo
service's settings in the central place, and observe the new value in the running service. Then start
the echo service while the configuration service is down.

**Acceptance Scenarios**:

1. **Given** shared defaults, service-specific settings and an environment override exist, **When** a
   service starts, **Then** it receives all three, with the service-specific and environment values
   taking precedence over the shared defaults.
2. **Given** a service is running, **When** a setting is changed centrally, **Then** the running
   service uses the new value within about 10 seconds, with no rebuild or restart.
3. **Given** the configuration service is down, **When** a service starts, **Then** it retries for a
   short bounded period and then exits with a message stating that central configuration could not be
   reached.
4. **Given** any environment other than local development, **When** someone opens the registry
   dashboard or requests settings from the configuration service without credentials, **Then** access
   is refused.
5. **Given** the local development environment, **When** the dashboard or configuration service is
   opened, **Then** no login is required.

---

### User Story 4 - One entry point for all clients (Priority: P2)

A client (and later the frontend) calls a single address. Requests are routed by path prefix to the
right service. Every request carries a correlation ID that is passed to the service and returned to the
client. When something goes wrong, the client always gets an error with the same structure.

**Why this priority**: It is the only way clients reach the backend, so its behavior sets the contract
for the frontend. It needs working discovery and configuration to be meaningful.

**Independent Test**: With the platform running, call the entry point on a route that maps to the echo
service, then on an unknown path, a protected path, a service with no healthy copy, and in a burst that
exceeds the rate limit.

**Acceptance Scenarios**:

1. **Given** the echo service is healthy, **When** a client calls its path prefix through the entry
   point, **Then** the request reaches the echo service and the response is returned.
2. **Given** any request, **When** it passes through the entry point, **Then** it has a correlation ID
   (the client's, if it supplied a valid one, otherwise a new one), the same ID is passed to the
   service and appears in the service's logs, and it is returned in the response.
3. **Given** a path that matches no route, **When** a client calls it, **Then** it receives 404.
4. **Given** a route whose service has no healthy copy, **When** a client calls it, **Then** it
   receives 503 within 5 seconds.
5. **Given** a client exceeds the configured request rate, **When** it sends further requests,
   **Then** it receives 429 until the rate falls back within the limit.
6. **Given** a protected route, **When** any client calls it (authentication does not exist yet),
   **Then** it receives 401.
7. **Given** a browser request from an origin listed in the central settings, **When** it reaches the
   entry point, **Then** it is allowed; **and given** an origin not listed, **Then** it is refused.
8. **Given** any error produced by the entry point (404, 429, 503, 401), **When** the client reads
   it, **Then** it has the same machine-readable structure and includes the correlation ID.

---

### User Story 5 - A consistent skeleton for new services (Priority: P3)

A developer building a later service (course, payment, and so on) starts from the same skeleton: one
shared place defines versions, a shared definition of the standard event wrapper exists, and a small
placeholder "echo" service demonstrates registration, configuration and routing. A new service needs no
extra code to register, load its settings and expose health checks.

**Why this priority**: It pays off for later specs rather than for this one, and it largely falls out of
the work in Stories 1 to 4. It must still be explicit so the conventions are fixed before any business
service exists.

**Independent Test**: Add a new empty service folder following the documented steps. Start it. It
registers, loads its settings, exposes health, and is routable without writing registration or
configuration code. Serialize and read back a sample event in the standard wrapper.

**Acceptance Scenarios**:

1. **Given** the shared definition of versions, **When** a developer inspects the backend, **Then**
   every service and shared library takes its versions from that one place.
2. **Given** a new service created from the skeleton, **When** it starts with the platform, **Then** it
   registers, loads central settings and reports health with no service-specific registration,
   configuration or health code.
3. **Given** a sample event, **When** it is written in the standard wrapper and read back, **Then** it
   carries event identifier, type, version, time of occurrence, correlation ID and payload unchanged.
4. **Given** a second copy of the echo service is started, **When** calls are made through the entry
   point, **Then** responses show both copies being used.

---

### Edge Cases

- A required setting is empty rather than absent: treated the same as missing, with a message naming it.
- A required port is occupied by an unrelated program: startup fails naming the port, and nothing is
  left half-started.
- The container runtime is not running: startup fails with a message saying so.
- The platform is started a second time while already running: no duplicate components or errors.
- The registry or configuration service restarts while others are running: behavior as described in
  Stories 2 and 3; services recover on their own.
- A service that crashes (rather than stops cleanly) is removed from the registry within a bounded time
  and the entry point returns 503 for it once no healthy copy remains.
- A client sends a malformed correlation ID: a new one is generated and used instead.
- A client sends a correlation ID that is valid: it is reused, not replaced.
- Database initialization is re-run on a restart or reset: it is safe to repeat and never fails because
  a database or login already exists.
- Concurrent clients are each limited separately by the rate limit, so one noisy client does not
  cause 429 for others.
- A browser pre-check (preflight) request from an allowed origin is answered without needing a
  protected-route login.

## Requirements *(mandatory)*

### Functional Requirements

**Startup and environment**

- **FR-001**: The platform MUST start completely with one command run from the repo root on a clean
  checkout, after the example settings file is copied.
- **FR-002**: Components MUST start in dependency order (discovery and configuration first, then the
  entry point and services), and each MUST report health that the startup command waits for.
- **FR-003**: Startup MUST stop with a message naming the problem when a required setting is missing or
  empty, or when a host port needed by a component is already occupied.
- **FR-004**: Stopping and restarting the platform MUST work without manual cleanup and MUST preserve
  stored data.
- **FR-005**: A documented reset procedure MUST wipe all stored data and return the platform to
  clean-checkout behavior.
- **FR-006**: The local environment MUST include a message broker, a database server and a catcher for
  outgoing emails, all following the port table in the constitution.
- **FR-007**: The database server MUST provide a separate database and a separate login for each of the
  seven planned business services (auth, course, enrollment, payment, certificate, review,
  notification), created automatically and safely repeatable. No tables are created.
- **FR-008**: The email catcher MUST accept outgoing mail from services and let a developer view it,
  without delivering anything outside the machine.
- **FR-009**: All secrets MUST come from environment values. The repo root MUST contain an example
  settings file listing every setting name with non-secret placeholder values, and no real secret may
  be committed.

**Discovery**

- **FR-010**: Every service except the registry itself MUST register under its service name, with no
  service-specific registration code.
- **FR-011**: A started service MUST appear on the registry dashboard within about 30 seconds, and a
  stopped service MUST disappear within about 30 seconds.
- **FR-012**: Services and the entry point MUST address other services by name only; no host or port may
  be hardcoded.
- **FR-013**: Calls to a service with several registered copies MUST be spread across the copies.
- **FR-014**: Running services MUST keep working during a brief registry outage and MUST re-register
  automatically when it returns.

**Central configuration**

- **FR-015**: All service settings MUST be held centrally, delivered to each service as shared defaults,
  service-specific settings and per-environment overrides, with more specific values taking precedence.
- **FR-016**: A change to a central setting MUST take effect in a running service within about 10
  seconds without a rebuild.
- **FR-017**: A service that cannot reach central configuration at startup MUST retry for a short bounded
  period and then fail with a clear message; it MUST NOT run with missing settings.
- **FR-018**: Outside the local development environment, the registry dashboard and the configuration
  service MUST require a login; in local development they MUST NOT.

**Entry point**

- **FR-019**: The platform MUST expose a single client address, and requests MUST be routed to services
  by path prefix.
- **FR-020**: Every request MUST receive a correlation ID, reusing a valid one supplied by the client,
  passing it to the target service and returning it in the response; services MUST include it in their
  structured logs.
- **FR-021**: Allowed browser origins MUST be read from central settings.
- **FR-022**: Unknown paths MUST return 404; a route with no healthy service copy MUST return 503 within
  5 seconds; clients exceeding the rate limit MUST receive 429; protected routes MUST return 401 for
  every request until authentication exists.
- **FR-023**: Public routes MUST be listed explicitly, and every route not listed MUST be protected.
- **FR-024**: Rate limits MUST come from central settings and apply per client.
- **FR-025**: Every error produced by the entry point MUST share one structure and include the
  correlation ID.

**Skeleton and shared code**

- **FR-026**: The backend MUST be one build with a single shared place that defines all versions; one
  folder per service under `backend/`.
- **FR-027**: A shared definition of the standard event wrapper (event identifier, type, version, time of
  occurrence, correlation ID, payload) MUST exist and MUST contain no domain logic.
- **FR-028**: A placeholder echo service MUST exist that proves registration, configuration and routing,
  and MUST be startable in two copies.
- **FR-029**: A new service MUST register, load central settings and expose liveness and readiness health
  without any service-specific code for these.
- **FR-030**: The README MUST document how to start, stop, restart and reset the platform, the port
  table, how to add a service, and how to change a central setting.

### Key Entities

- **Service**: A runnable platform component identified by a service name; may have several running
  copies.
- **Registry entry**: A record that a service copy is alive and reachable, keyed by service name.
- **Central setting set**: Shared defaults, service-specific values and environment overrides delivered
  to a service.
- **Route**: A mapping from a path prefix to a service name, marked public or protected.
- **Correlation ID**: An identifier attached to a request that follows it through services and logs and
  is returned to the client.
- **Standard event wrapper**: The common envelope for messages between services: event identifier,
  type, version, time of occurrence, correlation ID, payload.
- **Service database**: A separate database and login per planned business service, empty in this spec.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: On a clean checkout, one command brings the whole platform to fully healthy in under 3
  minutes, with every component reporting healthy.
- **SC-002**: A started service appears in the registry dashboard within 30 seconds, and a stopped one
  disappears within 30 seconds, in 100% of trials.
- **SC-003**: A changed central setting is in effect in a running service within 10 seconds, in 100% of
  trials, with no rebuild or restart.
- **SC-004**: 100% of errors produced by the entry point (404, 401, 429, 503) share the same structure
  and include a correlation ID.
- **SC-005**: A request for a service with no healthy copy receives its 503 response within 5 seconds.
- **SC-006**: With two echo copies running, at least 40% of 100 repeated calls reach each copy.
- **SC-007**: A new service created from the skeleton registers, loads central settings and reports
  health with zero service-specific registration, configuration or health code.
- **SC-008**: A developer can stop, restart and reset the platform following only the README, with
  no manual cleanup.
- **SC-009**: Startup with a missing setting or an occupied port fails in under 30 seconds with a
  message naming the exact setting or port.
- **SC-010**: No real secret appears in the repository; every setting the platform needs is listed in
  the example settings file.

## Assumptions

- Developers have a container runtime installed and running; no other local tooling is required to start
  the platform.
- Development environment is called "local"; "outside local" covers every other named environment, and
  those environments need no production deployment in this spec.
- Routes use the form `/api/v1/<service-name>/...`, per the constitution's REST convention.
- The correlation ID travels in a documented request/response header, and a client-supplied value is
  accepted only if it is well formed.
- The echo service offers one public path (to prove routing) and one protected path (to prove the
  401 behavior), since no real routes exist yet.
- Default rate limit values and the allowed-origin list are placeholders in central settings, to be
  tuned by later specs.
- Container ports follow the constitution's port table (gateway 9080, registry 9761, configuration
  9888, echo 9099, database 9432, broker 9672/9673, email catcher 9025/9825); business service ports
  9001–9007 are reserved but no business service runs yet.
- Health reported by components covers both "alive" and "ready to serve" states.
- Stored data (database contents) lives in persistent volumes; the reset procedure removes them.
- Out of scope: anything in `frontend/`, login and token checks, real business services and their
  tables, message queue layouts, high availability, production deployment, and tracing or log
  aggregation tools. Only the correlation ID and structured logs are included.
