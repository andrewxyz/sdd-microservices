# LearnHub — Solution Architecture

| | |
|---|---|
| **Document** | Solution Architecture: current state, target state and migration path |
| **System** | LearnHub, an online learning platform (B2C web application) |
| **Version** | 1.0 (2026-10-20) |
| **Baseline** | `master` at commit `9040f8d`, specs 001–006 complete |
| **Governing rules** | `.specify/memory/constitution.md` v1.2.0 |

## 0. Context and scope

### 0.1 The application

LearnHub lets students browse a catalog of courses, buy or take free courses, work through lessons,
track their progress and receive a verifiable certificate when they finish. Instructors author and
publish courses; administrators manage categories and roles.

| Template field | LearnHub |
|---|---|
| Application type | B2C online learning platform: a student-facing web SPA backed by a microservices API |
| Frontend | React 19, TypeScript (strict), Vite, React Router, plain CSS, served by nginx |
| Backend | Java 21, Spring Boot 3.5.15, Spring Cloud 2025.0.3 (Eureka, Config Server, Gateway, OpenFeign, LoadBalancer, Resilience4j) |
| Data | PostgreSQL 16 (one logical database per business service), Flyway migrations |
| Messaging | RabbitMQ 4.3, one topic exchange `learning.events` |
| Email | SMTP; Mailpit captures every email locally |
| Payments | In-process simulated provider driven by test tokens (`sim-success`, `sim-decline`, `sim-unavailable`) |
| Hosting today | Docker Compose on a single host (developer laptop / CI runner). No cloud deployment yet |

### 0.2 Core features and user flows

1. **Identity**: register, sign in, silent token refresh, sign out, role change (administrator only).
2. **Catalog**: public browse, search, filter and sort of published courses; instructor course and lesson authoring with optimistic concurrency.
3. **Checkout and enrollment**: idempotent checkout of free and paid courses; enrollment created asynchronously from `payment.succeeded`.
4. **Learning and progress**: lesson content gated by enrollment; mark lessons complete; progress percentage; course completion detected exactly once.
5. **Certificates**: one certificate per student per course, issued from `course.completed`; public verification by 16-character code.
6. **Notifications**: plain-text email on enrollment and on certificate issue.

### 0.3 How to read this document

Sections 1–3 describe the system **as it is built today**; every claim there was checked against the
code. Sections 4–5 are **recommendations**. Wherever a recommendation adds a technology, it needs a
constitution amendment first (Principle X, Simplicity), and that is called out inline.

---

## 1. High-level architecture overview

### 1.1 Architectural style

LearnHub is a **microservices architecture with an API gateway, a service registry and externalised
configuration**. Services talk to each other synchronously for lookups and asynchronously, through
events, for anything that changes state. The main patterns:

| Pattern | Where it is applied |
|---|---|
| API Gateway (single entry point) | `api-gateway` (Spring Cloud Gateway, WebFlux). It validates JWTs, applies CORS and rate limits, adds correlation IDs and forwards identity as headers |
| Backend-for-frontend via reverse proxy | nginx in the `frontend` container serves the SPA and proxies same-origin `/api/` to the gateway |
| Service registry and client-side load balancing | Eureka (`discovery-server`) plus Spring Cloud LoadBalancer (`lb://` routes, Feign) |
| Externalised configuration with hot reload | `config-server` serves `backend/config-repo/`; services poll every 5 s and refresh within about 10 s |
| Database per service | Each business service owns one PostgreSQL database; cross-service reads are forbidden |
| Event-driven choreography | Business processes run as event chains on `learning.events`, with no central orchestrator |
| Transactional outbox | `payment-service`, `enrollment-service` and `certificate-service` write events in the same transaction as their state change, so no event is lost |
| Idempotent consumer and dead-letter queue | Every consumer de-duplicates on `eventId`, retries 3 times with backoff, then dead-letters |
| Idempotency key | Client-supplied key on `POST /api/v1/checkouts`, so a repeated checkout never charges twice |
| Optimistic concurrency | `ETag` / `If-Match` on every course and lesson mutation; a stale write gets `409` |
| Circuit breaker with fallback | Resilience4j on every Feign client |

### 1.2 System context and container view

```
                         +---------------------------+
   Student (browser) --->|  frontend  (nginx :9100)  |  serves the React SPA
                         |  /        -> index.html    |
                         |  /api/**  -> api-gateway   |
                         +-------------+-------------+
                                       |
                         +-------------v-------------+      +-------------------+
                         |  api-gateway  (:9080)      |<---->| discovery-server  |
                         |  JWT check, CORS, rate     |      | Eureka   (:9761)  |
                         |  limit, correlation ID     |      +-------------------+
                         +--+-----+-----+-----+----+--+      +-------------------+
                            |     |     |     |    |         | config-server     |
              lb:// routes  |     |     |     |    |         | (:9888) serves    |
                            v     v     v     v    v         | backend/config-repo
   +-----------+ +---------+ +-----------+ +---------+ +-------------+
   | auth      | | course  | | enrollment| | payment | | certificate |   notification
   | :9001     | | :9002   | | :9003     | | :9004   | | :9005       |   :9007 (no public
   +-----+-----+ +----+----+ +-----+-----+ +----+----+ +------+------+    route)
         |            |            |            |             |               |
         |      Feign lookups (Resilience4j circuit breaker + fallback)       |
         |            |            |            |             |               |
   +-----v------------v------------v------------v-------------v---------------v-----+
   |  PostgreSQL 16 (:9432): auth_db, course_db, enrollment_db, payment_db,          |
   |  certificate_db, notification_db (review_db reserved). One owner per database   |
   +----------------------------------------------------------------------------------+
   |  RabbitMQ 4.3 (:9672): topic exchange learning.events, one queue + DLQ per       |
   |  consumer                                                                        |
   +----------------------------------------------------------------------------------+
   |  Mailpit (:9025 SMTP / :9825 UI): local email capture                            |
   +----------------------------------------------------------------------------------+
```

### 1.3 Bounded contexts

| Context | Service | Owns | Status |
|---|---|---|---|
| Identity | `auth-service` | Accounts, roles, refresh-token sessions | Built (spec 002) |
| Catalog | `course-service` | Categories, courses, lessons, publish lifecycle | Built (spec 003) |
| Commerce | `payment-service` | Checkouts, payments, idempotency records | Built (spec 004) |
| Learning | `enrollment-service` | Enrollments, lesson completions, course completion | Built (specs 004–005) |
| Credentials | `certificate-service` | Certificates and verification codes | Built (spec 005) |
| Messaging | `notification-service` | Sent-notification log (for de-duplication) | Built (spec 005) |
| Feedback | `review-service` | Course reviews | Deferred; port 9006 and `review_db` reserved |

### 1.4 Architectural principles in force

These are enforced by the constitution and, in several cases, by tests (`NoWiringCodeTest`,
`NoHardcodedAddressTest`, Testcontainers integration tests):

- The gateway is the **only** client entry point. Every route is **protected by default**; public routes are listed explicitly.
- **No shared database and no cross-service joins.** Other contexts' data comes through Feign or events.
- Shared libraries are limited to `shared-events` (the event envelope) and `platform-common` (errors, correlation ID, logging). **No domain logic is shared.**
- Every client-reachable error is **RFC 7807 problem details** with a `correlationId`.
- **Test-first** development. Testcontainers for PostgreSQL and RabbitMQ; H2 is not allowed.
- All versions live in `backend/pom.xml` only. All host ports follow the 9xxx port map.

---

## 2. Component breakdown and responsibilities

### 2.1 Frontend (`frontend/`)

| Aspect | Design |
|---|---|
| Type | Single-page app: React 19, TypeScript strict, Vite build, React Router |
| Serving | nginx container on host port 9100; SPA fallback (`try_files … /index.html`); `/api/` reverse-proxied to `api-gateway:9080` |
| Security headers | `Content-Security-Policy: default-src 'self'`, `X-Frame-Options: DENY`, `X-Content-Type-Options: nosniff`, `Referrer-Policy: no-referrer` |
| Session | Access token **in memory only**; refresh token in `sessionStorage`; never `localStorage`, cookies or URLs |
| Token refresh | **Single-flight**: concurrent 401s await one shared refresh, because the backend treats reuse of a rotated refresh token as theft and revokes the session |
| API contract | Types generated from `specs/*/contracts/*.openapi.yaml`, not written by hand |
| Rendering safety | Server text is always rendered as text; `dangerouslySetInnerHTML` is forbidden |
| Error UX | Every error shows the backend `correlationId` as a reference code |
| Checkout | `useCheckout` hook: idempotency key reused across network/503 retries, regenerated only after a final business outcome; polls enrollment every 1 s for up to 15 s, then shows "still being confirmed" |
| State | React state and context only; no state library, no UI framework (constitution XI) |

### 2.2 Edge and platform layer

| Component | Responsibilities | Notes |
|---|---|---|
| `api-gateway` (Spring Cloud Gateway, reactive) | Routing (`lb://<service>`); JWT verification (HS256, shared secret); replaces the token with `X-User-Id` / `X-User-Role` headers; role-restricted routes (`metadata.roles`); public-route list (`metadata.public`); global CORS; token-bucket rate limiting per client address (general: 200 capacity, 100/s refill; strict bucket for certificate verification: 10 capacity, 2/s); correlation-ID filter; RFC 7807 error handler | Rate-limit state is **in-process memory**, so the limit applies per gateway instance |
| `discovery-server` (Eureka) | Service registration and lookup; 30 s lease expiry | Single node; basic auth outside the `local` profile |
| `config-server` | Serves `backend/config-repo/` (shared `application.yml`, per-service and per-profile files); services fail fast if it is unreachable (bounded retry, 30 s cap) | Currently reads the **working tree** directly; secrets are never stored there |
| `platform-common` (library) | Problem-details errors, correlation-ID propagation, structured ECS JSON logging, Eureka/config wiring | Services contain no wiring code of their own (`NoWiringCodeTest`) |
| `shared-events` (library) | Versioned envelope `{eventId, type, version, occurredAt, correlationId, payload}`, routing-key rule `<domain>.<event>`, payload records | No domain routing keys declared here |
| `echo-service` | Platform verification only (routing, load spreading, failover smoke tests) | Not part of the product |

### 2.3 Business services

**API surface** (all paths are prefixed with `/api/v1`):

| Service | Endpoints reachable through the gateway |
|---|---|
| `auth-service` | `POST /auth/register`, `/auth/login`, `/auth/refresh` (public); `PATCH /auth/accounts/{id}/role` (administrator) |
| `course-service` | `GET /courses`, `/courses/{id}`, `/categories` (public); course and lesson create/edit/delete, publish, archive; `GET /my/courses`; `GET /courses/{id}/content` (gated by enrollment). Internal only, not routed: `GET /internal/courses/{id}` |
| `payment-service` | `POST /checkouts`; `GET /payments/**` (caller's own only) |
| `enrollment-service` | `GET /enrollments`; `PUT` / `DELETE /enrollments/{courseId}/lessons/{lessonId}/complete`; `GET /enrollments/{courseId}/progress` |
| `certificate-service` | `GET /certificates/mine`; `GET /certificates/verify/{code}` (public, strict rate limit) |
| `notification-service` | none (event-driven only) |

**Dependencies and events:**

| Service | Calls (Feign, with circuit breaker) | Publishes (via outbox) | Consumes |
|---|---|---|---|
| `auth-service` | none | none | none |
| `course-service` | `enrollment-service`: is the caller enrolled? | none | none |
| `payment-service` | `course-service`: price, published state | `payment.succeeded` | none |
| `enrollment-service` | `course-service`: lesson set for progress | `enrollment.created`, `course.completed` | `payment.succeeded` |
| `certificate-service` | `course-service`: course title | `certificate.issued` | `course.completed` |
| `notification-service` | `auth-service`: email address; `course-service`: course title | none | `enrollment.created`, `certificate.issued` |

Every service follows the `api` / `application` / `domain` /
`infrastructure` layering under `com.example.learnhub.<service>`.

### 2.4 Data layer

| Aspect | Current design |
|---|---|
| Engine | One PostgreSQL 16 **instance** hosting one **database per service**, each with its own credentials (created by the one-shot `db-init` container) |
| Schema | Flyway migrations per service; no shared tables |
| Consistency | Strong within a service; **eventual** across services through events (target: enrollment visible within about 10 s of payment) |
| Concurrency | Optimistic locking (`ETag`/`If-Match`) for catalog edits; unique constraints plus idempotency records for checkouts, enrollments, completions and certificates |
| Durability of events | Transactional outbox tables in payment, enrollment and certificate databases, relayed to RabbitMQ |

### 2.5 Messaging, workers and caching

- **Broker**: RabbitMQ, one topic exchange `learning.events`; each consumer has its own queue and its own DLQ.
- **Workers**: there is no separate worker tier. Consumers and outbox relays run **inside** the owning service process, which keeps deployment simple and ties their scaling to the service's own replicas.
- **Cache**: **none** today. Catalog reads go to `course-service` and PostgreSQL every time. `ETag`s already exist on course resources, which makes adding HTTP caching cheap later (see 4.4).

### 2.6 Third-party and external services

| Capability | Today | Production need |
|---|---|---|
| Payments | Simulated provider in `payment-service`, behind a provider interface | A real PSP (for example Stripe) with webhooks |
| Email | SMTP to Mailpit (captured, never delivered) | A transactional email provider (SES, SendGrid, Postmark) |
| Identity | Self-hosted in `auth-service` (BCrypt, HS256 JWT, rotating refresh tokens) | Keep, but move to asymmetric signing (4.2) |

### 2.7 Security model summary

- **Authentication**: access JWT, HS256, **15-minute** lifetime; refresh token **30 days**, rotated on every use. Reusing a spent refresh token revokes the whole session.
- **Authorization**: coarse checks at the gateway (authenticated, `ADMINISTRATOR` role on admin routes); fine-grained ownership checks inside each service (course owner, own payments, own enrollments).
- **Secrets**: environment variables from `.env` (git-ignored); `.env.example` documents each one with a placeholder.
- **Internal endpoints** (for example `/api/v1/internal/courses/{id}`) have no gateway route, so clients cannot reach them.
- **Platform servers**: Eureka and config server require basic auth outside the `local` profile; only health probes are open.

---

## 3. Data flow and sequence

### 3.1 Authenticated request through the edge (every protected call)

```
Browser          nginx (9100)        api-gateway (9080)              target service
   |  GET /api/v1/enrollments            |                                |
   |  Authorization: Bearer <access JWT> |                                |
   |-------------->|                     |                                |
   |               |  proxy_pass /api/** |                                |
   |               |-------------------->| 1. correlation ID (new or kept)|
   |               |                     | 2. rate-limit bucket check     |
   |               |                     | 3. route match; public?        |
   |               |                     | 4. verify JWT (HS256, expiry)  |
   |               |                     | 5. strip token, add            |
   |               |                     |    X-User-Id / X-User-Role     |
   |               |                     | 6. lb://enrollment-service     |
   |               |                     |------------------------------->|
   |               |                     |                                | ownership check,
   |               |                     |                                | query own DB
   |               |                     |<-------------------------------|
   |<--------------|<--------------------|  200 JSON, or RFC 7807 with correlationId
```

**Token expiry path**: the service is never reached with an expired token. The gateway returns
`401`. The SPA's API client holds every in-flight request, makes **one** `POST /auth/refresh`, then
replays the held requests with the new access token. If the refresh itself fails, the session is
cleared and the user is sent to sign-in with a `returnTo` link.

### 3.2 Core flow A: paid checkout to enrollment (asynchronous, exactly-once effect)

```
SPA            gateway     payment-svc        course-svc   RabbitMQ          enrollment-svc   notification-svc
 | POST /checkouts            |                    |           |                    |                 |
 | {courseId, idempotencyKey:K, paymentToken:sim-success}      |                    |                 |
 |---------->|--------------->|                    |           |                    |                 |
 |           |                | key K seen? -> return stored result (no 2nd charge) |                 |
 |           |                | GET internal course (Feign, CB) ---------->|        |                 |
 |           |                |<-------------- price, published -----------|        |                 |
 |           |                | charge via provider (simulated)            |        |                 |
 |           |                | TX: payment=SUCCEEDED + outbox row         |        |                 |
 |<----------|<---------------| 201 {paymentId, status: SUCCEEDED}         |        |                 |
 |           |                | relay (polls every 2 s) --> payment.succeeded -->|  |                 |
 |           |                |                    |           |--------------->|   |                 |
 |           |                |                    |           |  eventId seen? skip; else           |
 |           |                |                    |           |  TX: enrollment + outbox            |
 |           |                |                    |           |<-- enrollment.created ----|          |
 |           |                |                    |           |------------------------------------>| email
 | poll GET /enrollments every 1 s (up to 15 s) until the course appears -> "You're enrolled!"        |
```

Behaviour under failure:

| Failure | Effect | Why it is safe |
|---|---|---|
| User double-clicks or retries after a timeout | Same idempotency key; the stored result is returned | One payment per key; a key reused with a different body is refused |
| `sim-decline` | Payment recorded as `DECLINED` with a reason; nothing is published | No enrollment, no success counted; the SPA issues a new key for the next attempt |
| `sim-unavailable` / course-service down | Problem details with `correlationId`; circuit breaker opens | Fallback refuses rather than guessing; the user can retry safely with the same key |
| RabbitMQ briefly unreachable | Payment already committed with its outbox row | Relay publishes when the broker returns; nothing is lost |
| Event redelivered | Consumer finds `eventId` already processed | No duplicate enrollment or email |
| Consumer keeps failing | 3 retries with backoff, then DLQ | The queue keeps moving; the poison message waits for an operator |
| Enrollment takes longer than 15 s | SPA shows "payment succeeded, enrollment still being confirmed" | User is told the truth; the enrollment still arrives |

Free courses follow the same path but skip the provider call.

### 3.3 Core flow B: completing a course to a verifiable certificate

```
SPA         gateway   enrollment-svc                 RabbitMQ      certificate-svc       notification-svc
 | PUT /enrollments/{c}/lessons/{l}/complete           |               |                      |
 |-------->|-------->| enrolled? (own DB)                |               |                      |
 |         |         | upsert completion (idempotent)    |               |                      |
 |         |         | all lessons done AND not yet completed?           |                      |
 |         |         |   -> TX: enrollment=COMPLETED + outbox (once, permanently)               |
 |<--------|<--------| 200 {completed, total, percent}   |               |                      |
 |         |         | relay --> course.completed ------>|-------------->| eventId seen? skip    |
 |         |         |                                   |               | one cert per (student,course)
 |         |         |                                   |               | 16-char code, title (Feign)
 |         |         |                                   |<-- certificate.issued (outbox)       |
 |         |         |                                   |------------------------------------->| email
 | Anyone: GET /certificates/verify/{code}  (public, strict rate-limit bucket)                  |
 |         -> {valid, courseTitle, completedOn}  (no holder identity is returned)               |
```

Course completion is guarded so that it fires **once**, even under ten concurrent completions of the
last lessons and even if lessons are later unmarked and marked again.

### 3.4 Content gating (synchronous, fail-closed)

`GET /courses/{id}/content` is answered by `course-service`. It serves full lessons to the owning
instructor, an administrator, or a student whom `enrollment-service` confirms as enrolled (Feign with
a circuit breaker). If enrollment status **cannot be confirmed**, the fallback returns a temporary
error and never the content. The SPA shows "We could not confirm your enrollment right now" with a
Retry button.

---

## 4. Technology recommendations and trade-offs

### 4.1 Keep

| Keep | Why |
|---|---|
| Microservices split by bounded context | Boundaries match the business. Each service is small, owns its data and has a clear event contract. Merging now would throw away proven work for little gain |
| Gateway-only entry, protected by default | One place for authentication, rate limits, CORS and correlation, and fewer ways to expose something by mistake |
| Outbox, idempotent consumers, DLQs, idempotency keys | This is the correctness core of the platform. These patterns are what make payments and enrollments exactly-once in effect. Do not trade them away for throughput |
| RabbitMQ topic exchange with per-consumer queues | Right-sized for the event volume; easy to operate; fan-out without coupling |
| PostgreSQL with Flyway | Mature, transactional and well understood; carries the outbox pattern naturally |
| React 19 SPA with no UI framework or state library, OpenAPI-generated types | Small surface, few dependencies, contract drift shows up at compile time |
| Test-first with Testcontainers | Real databases and brokers in tests are why these guarantees can be trusted |
| RFC 7807 errors with correlation IDs, end to end | One error model; a user's reference code leads straight to the backend log line |

### 4.2 Fix now (defects and gaps found in the live test run on 2026-10-20)

| # | Finding | Recommendation | Trade-off |
|---|---|---|---|
| F1 | **CORS workaround deviates from constitution XIII.** Registration from the browser returned `403`. The quick fix (commit `9040f8d`) added `http://localhost:9100` to the gateway's CORS allowed origins. Constitution XIII says no browser origin should need adding, because calls are same-origin. Likely root cause: nginx forwards `Host: $host`, which **drops the port**, so the gateway compares `Origin: http://localhost:9100` with host `localhost` and treats a same-origin call as cross-origin | Change nginx to `proxy_set_header Host $http_host;` (or send `X-Forwarded-Host`/`-Port` and enable forwarded-header handling in the gateway). Then **remove** the 9100 origin and prove it with a smoke test that POSTs with an `Origin` header | Very small change. It restores the constitutional guarantee and avoids hard-coding an origin per environment |
| F2 | Frontend health check used `localhost`; busybox `wget` resolved it to IPv6 `::1`, but nginx listens on IPv4 only | Already fixed (`127.0.0.1`). Also add `listen [::]:80;` to the nginx template so IPv6 clients work | None |
| F3 | Building every image in parallel on one WSL host exhausted memory (BuildKit `SIGBUS`) and corrupted the WSL distro | Build images **once in CI**, push them to a registry, and have `up.sh` pull by default (build only with an explicit flag). Cap local build parallelism | Needs a container registry; much faster and more reliable local start-up |
| F4 | `up.sh` reported "Platform is up" when **no** containers existed (an empty `docker compose ps` counts as "nothing unhealthy") | Treat "zero running services" or "expected service missing" as failure; compare against `docker compose config --services` | None; a correctness fix in a script |
| F5 | The README still says "no CORS configuration is needed", which contradicts the current config | Update once F1 lands | None |
| F6 | **Outbox relays are not safe to scale out.** Each relay polls `findByPublishedAtIsNull()` every 2 s with no row lock, so two replicas of payment, enrollment or certificate service would both publish the same rows | Claim rows with `SELECT … FOR UPDATE SKIP LOCKED` in a short transaction (or run one active relay through leader election), and test with two instances | Consumers already de-duplicate on `eventId`, so the effect stays correct today; the fix removes duplicate traffic and makes scale-out safe |

### 4.3 Upgrade or replace for production

| Area | Current | Recommendation | Trade-off / note |
|---|---|---|---|
| Framework line | Spring Boot 3.5.15 / Spring Cloud 2025.0.3: the **final OSS releases** of their lines (accepted risk in the constitution) | Plan the move to **Spring Boot 4 + Spring Cloud 2025.1.x** as its own spec, before production | Migration effort (Jakarta and API changes, Gateway module renames). Staying means no OSS security patches |
| Token signing | HS256 with one shared secret held by **both** auth-service and the gateway | **RS256/ES256 with a JWKS endpoint** on auth-service; the gateway verifies with the public key; rotate keys by `kid` | A leaked gateway can no longer mint tokens; slightly more key management |
| Gateway rate limiting | In-memory token bucket per instance | Move bucket state to **Redis** (Spring Cloud Gateway `RedisRateLimiter`, or Bucket4j on Redis) once more than one gateway replica runs | Adds Redis (constitution amendment). Without it, N replicas allow N× the intended limit |
| Configuration source | Config server reads the working tree | **Git-backed** config repo (versioned, reviewed, auditable), plus a secrets manager (Vault / AWS Secrets Manager / Azure Key Vault) for credentials | Hot reload still works; changes become traceable and reversible |
| Service discovery | Eureka, single node | On Kubernetes: **replace Eureka with Kubernetes Services/DNS** (Spring Cloud Kubernetes or plain DNS) and drop the registry warm-up 503 window. Outside Kubernetes: run Eureka as a 2–3 node peer cluster | Removing Eureka changes Principle III and needs an amendment; it removes one stateful component and the 30 s registration delay |
| Database topology | One PostgreSQL instance, many databases | **Managed PostgreSQL** (RDS/Aurora, Cloud SQL, Azure Flexible Server) with HA and point-in-time recovery. Start as one cluster with per-service databases; split `payment` and `enrollment` onto their own clusters when load or blast radius justifies it | Logical isolation already exists, so splitting later is an operations change, not a code change |
| Broker | Single RabbitMQ node | **3-node cluster with quorum queues** (or a managed broker such as Amazon MQ / CloudAMQP) | Quorum queues cost some throughput but survive node loss without losing messages |
| Payments | Simulated provider | **Real PSP adapter** behind the existing provider interface; PSP **webhooks** (signature-verified, idempotent on the PSP event ID) as the source of truth for asynchronous outcomes; reconciliation job | PCI scope stays minimal with hosted card fields; the outbox and idempotency already fit webhook delivery |
| Email | Mailpit | Transactional email provider via SMTP or API, with bounce/complaint handling | Per-message cost; needs verified sender domains (SPF/DKIM/DMARC) |
| Observability | Structured ECS JSON logs with correlation IDs; Actuator health | Add **OpenTelemetry tracing** (Micrometer Tracing → OTLP), **Prometheus metrics + Grafana**, and central log aggregation (Loki / ELK / CloudWatch). Carry trace context through the event envelope | Amendment needed; this is the biggest gap in operating an event-driven system |
| Frontend delivery | nginx container | Static assets on **object storage + CDN**, keeping the `/api/` same-origin path through the CDN to the gateway (or keep nginx behind the CDN) | Global latency improves; same-origin and CSP still hold |
| Edge security | None in front of the gateway | Managed load balancer with TLS termination and a **WAF**; HSTS | Standard for production; TLS is a must-have |

### 4.4 Add when the load demands it (not before)

| Addition | Trigger | Notes |
|---|---|---|
| HTTP caching for the public catalog | Catalog reads dominate traffic | `ETag`s already exist. Add `Cache-Control` on public `GET /courses*` and `/categories`, then CDN caching for anonymous traffic. This is the cheapest scale lever |
| Read replica for `course_db` | Catalog query latency rises under load | Route public list/detail reads to a replica; writes stay on the primary |
| Search engine (OpenSearch) | Full-text search or relevance ranking beyond simple filters | Fed from catalog events; would need a new `course.published`/`course.updated` event |
| Separate worker deployments | Consumers need to scale independently of API traffic | Same codebase, a different Spring profile or entry point; no new service boundaries |
| Event-driven projections instead of Feign lookups | Feign fan-out to `course-service` becomes a hotspot or a single point of failure | Consumers keep a local read model of course title and lesson set, fed by catalog events |

### 4.5 Options considered and rejected

| Option | Why not |
|---|---|
| Collapse into a modular monolith | Boundaries and contracts are already clean and tested. A monolith would simplify operations but lose independent deployment and failure isolation. Revisit only if team size stays at 1–2 engineers for a long time |
| Serverless functions for consumers | Cold starts and the JVM footprint fit poorly; the outbox relay needs a long-running process |
| Kafka instead of RabbitMQ | Event volume does not need log retention or partitioned throughput; RabbitMQ's per-consumer DLQ model fits better |
| A central saga orchestrator | Each flow is a short, linear chain with idempotent steps; choreography is simpler and there are no compensations to coordinate yet |

---

## 5. Scalability and migration path

### 5.1 Scaling characteristics today

| Component | Stateless? | Horizontal scaling | Known constraint |
|---|---|---|---|
| frontend (nginx) | Yes | Unlimited; move behind a CDN | None |
| api-gateway | Almost (rate-limit buckets are in memory) | Yes, after Redis-backed limits | Per-instance rate limits |
| auth, course, payment, enrollment, certificate, notification | Yes (state is in PostgreSQL) | Yes; LoadBalancer spreads calls; queues are competing consumers | Outbox relays take no row lock (F6), so publishers duplicate events when scaled out until `SKIP LOCKED` is added |
| PostgreSQL | No | Vertical first, then read replicas, then split clusters | Single instance today: a single point of failure |
| RabbitMQ | No | Clustered quorum queues | Single node today: a single point of failure |
| Eureka / config server | Small state | 2–3 replicas, or replace with platform-native services | Single node today |

The **critical path for scale** is checkout → `payment.succeeded` → enrollment, plus the public
catalog. The **critical path for correctness** is the outbox and the idempotency stores. Every phase
below protects the second while it improves the first.

### 5.2 Phased plan

Each phase is shippable on its own, has exit criteria, and can be rolled back without touching the
phases before it.

#### Phase 0: Harden what exists (1–2 weeks)

1. Fix **F1** (nginx `Host` header), remove the 9100 CORS origin, and add a smoke check that POSTs with an `Origin` header.
2. Fix **F4** in `up.sh` (fail when expected services are missing) and add a script test for it.
3. CI builds and pushes versioned images to a container registry; `up.sh` pulls by default (**F3**).
4. Fix **F6**: claim outbox rows with `FOR UPDATE SKIP LOCKED`; add a test that runs two relay instances and asserts each event is published once.
5. Write the runbook: how to inspect and replay DLQs, read correlation IDs, and rotate secrets.

*Exit criteria*: smoke tests green in CI on pulled images; the stack starts cleanly on a fresh machine
with no local build.

#### Phase 1: Production foundations (3–5 weeks, needs constitution amendments)

1. **Amend the constitution** for: OpenTelemetry, Prometheus/Grafana, a secrets manager, Redis (needed in Phase 2) and the hosting platform.
2. Move JWT signing to **RS256 + JWKS**. Roll out in two steps: the gateway accepts both HS256 and RS256 during one access-token lifetime (15 min) plus a safety margin, then HS256 is removed.
3. Externalise secrets to a secrets manager; config repo becomes **git-backed**.
4. Add distributed tracing through HTTP **and** the event envelope (put `traceparent` beside `correlationId`), plus metrics dashboards for queue depth, DLQ count, outbox lag, checkout latency and error rate.
5. Plan and run the **Spring Boot 4 / Spring Cloud 2025.1** upgrade as its own spec, one service at a time behind the unchanged gateway contract.

*Exit criteria*: one trace spans SPA → gateway → payment → RabbitMQ → enrollment → notification;
no shared signing secret; the framework is on a supported line.

#### Phase 2: First production deployment (3–4 weeks)

1. Choose a target: **managed Kubernetes** (EKS/AKS/GKE) is recommended for 11 deployables. A container PaaS (ECS Fargate, Azure Container Apps) is the lighter alternative.
2. Provision **managed PostgreSQL** (HA, PITR) and a **clustered RabbitMQ** with quorum queues. Migrate each per-service database with `pg_dump`/restore, or start fresh before launch.
3. Replace Eureka with platform-native discovery (amendment), or run Eureka with 3 peers if staying off Kubernetes.
4. Gateway: at least 2 replicas behind a managed load balancer with TLS and a WAF; **Redis-backed rate limiting**.
5. Frontend: CDN-fronted, keeping the same-origin `/api/` path; HSTS on.
6. Readiness probes gate traffic; set rolling deploys with `maxUnavailable: 0` for the gateway and payment-service.

*Rollback*: the Compose stack stays runnable and remains the reference environment until production
has been stable for one release cycle.

*Exit criteria*: zero-downtime rolling deploy shown for every service; losing one broker node or one
database failover loses no messages and no payments (chaos test).

#### Phase 3: Real-world integrations (2–4 weeks, in parallel with Phase 2 once it is staged)

1. **PSP adapter** behind the existing provider interface; hosted payment fields (keeps PCI scope at SAQ-A); signature-verified webhooks, idempotent on the PSP event ID; a nightly reconciliation job between PSP and `payment_db`.
2. Keep the simulated provider as the **default in non-production** profiles so the test cards in the manual test guide still work.
3. Transactional email provider with SPF/DKIM/DMARC and bounce handling; Mailpit stays for local and CI.

*Exit criteria*: real test-mode payments create enrollments end to end; webhook replays cause no
duplicates; reconciliation reports zero drift.

#### Phase 4: Scale the hot paths (driven by metrics, not the calendar)

1. **Catalog**: `Cache-Control` plus CDN caching for anonymous catalog reads; then a read replica for `course_db`.
2. **Checkout**: scale payment-service and enrollment-service consumers on queue depth (KEDA on Kubernetes); alert on outbox lag.
3. **Lower Feign fan-out**: replace hot synchronous lookups (course title, lesson set) with local read models fed by new catalog events (`course.published`, `course.updated`). This needs a spec, because those events are new contracts.
4. **Data**: split `payment_db` and `enrollment_db` onto their own clusters if contention or blast radius requires it. Code does not change; only connection settings do.

*Exit criteria* (proposed targets, to be confirmed with the business): p95 catalog list < 200 ms at
peak; p95 checkout-to-enrollment < 10 s (already a spec goal); zero lost or duplicated payments.

#### Phase 5: Product expansion

1. `review-service` (port 9006, `review_db` already reserved) as its own spec, consuming `course.completed` so only finishers can review.
2. Instructor and administrator web apps as separate specs and separate frontends (constitution: not folded into the student app).
3. Optional search service fed by catalog events, if filtering is no longer enough.

### 5.3 Risk register

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| Running unsupported framework versions in production | High if Phase 1.5 slips | High | Upgrade before go-live; track CVEs in CI (dependency scanning) |
| Shared HS256 secret leaks | Low | High (anyone could mint tokens) | RS256 + JWKS (Phase 1.2) |
| Outbox relay duplicates under scale-out (confirmed: no row lock today) | High once replicas > 1 | Low for correctness (consumers are idempotent), Medium for load | `SKIP LOCKED` claim (F6, Phase 0.4) |
| DLQ messages go unnoticed | Medium | Medium (a student paid but is not enrolled) | DLQ depth alert plus runbook (Phase 0.5, 1.4) |
| Single-node PostgreSQL or RabbitMQ failure | Medium (today) | High | Managed HA database, quorum-queue cluster (Phase 2) |
| Environment-specific CORS origins drift | Medium | Medium (production 403s like the one seen in testing) | Fix the root cause (F1) so no origin list is needed |
| Feign dependency on `course-service` cascades | Low–Medium | Medium | Circuit breakers already exist; read models in Phase 4.3 |

---

## Appendix A: Event catalogue

| Routing key | Producer | Consumers | Effect |
|---|---|---|---|
| `payment.succeeded` | payment-service (outbox) | enrollment-service | Create enrollment (idempotent) |
| `enrollment.created` | enrollment-service (outbox) | notification-service | "You're enrolled" email |
| `course.completed` | enrollment-service (outbox) | certificate-service | Issue one certificate per student per course |
| `certificate.issued` | certificate-service (outbox) | notification-service | "Your certificate" email |

Envelope (all events): `eventId`, `type`, `version`, `occurredAt`, `correlationId`, `payload`. Exchange:
`learning.events` (topic). Each consumer has its own queue and DLQ, 3 retries with backoff.

## Appendix B: Port map (local and CI)

| Component | Host port | Component | Host port |
|---|---|---|---|
| frontend (nginx) | 9100 | api-gateway | 9080 |
| auth-service | 9001 | course-service | 9002 |
| enrollment-service | 9003 | payment-service | 9004 |
| certificate-service | 9005 | review-service (reserved) | 9006 |
| notification-service | 9007 | echo-service | 9099 |
| discovery-server | 9761 | config-server | 9888 |
| PostgreSQL | 9432 | RabbitMQ AMQP / UI | 9672 / 9673 |
| Mailpit SMTP / UI | 9025 / 9825 | | |

## Appendix C: Source references

- Constitution: `.specify/memory/constitution.md`
- Gateway routes, CORS, rate limits: `backend/config-repo/api-gateway.yml`
- API contracts: `specs/00*/contracts/*.openapi.yaml`
- Event envelope: `backend/shared-events/`
- Stack definition: `docker-compose.yml`, `scripts/up.sh`, `scripts/smoke-test.sh`
