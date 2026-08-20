# NovaBank User Service

Identity and authentication service for the NovaBank digital banking platform. Owns user registration, login, JWT issuance/verification, and session revocation. Does **not** own customer profile/KYC data — that's a deliberate boundary (see [Domain Model](#domain-model)).

This README is written for two audiences: a human engineer picking this up cold, and an AI assistant being asked to work on this codebase without the full history of *why* things are built this way. Sections marked **Context for AI assistants** exist specifically so a model doesn't have to re-derive settled decisions or "fix" something that's intentional.

---

## Tech stack

- **Java 21**, **Spring Boot 4.1.0** / Spring Framework 7 (current stable as of this project — note this is a newer major line than a lot of tutorial content online, which still targets Boot 3.x/`javax.*`)
- **PostgreSQL 16** (Flyway-managed schema)
- **Redis 7** (session revocation + rate-limit-adjacent state, not general caching)
- **JJWT** for JWT signing/verification (HS256)
- **Testcontainers** for integration tests (shared singleton containers, see [Testing](#testing))

## Architecture at a glance

```
Client → API Gateway (future) → User Service
                                    ├── PostgreSQL (users table)
                                    └── Redis (active session per user)
```

Every other future service in the platform verifies JWTs **locally** (signature check, no network call to User Service) so that User Service's own downtime doesn't take down authentication platform-wide. Redis is the one exception to "no network call" — it's used specifically to support real-time session revocation, which a purely stateless JWT can't provide on its own.

### Context for AI assistants
If you're asked to "simplify" or "remove the Redis dependency" from auth, understand this is a **deliberate trade-off**, not an oversight: pure stateless JWT verification can't support logout / single-active-session semantics, which were treated as a hard requirement for a banking product. See [Redis session model](#redis-session-model) before proposing changes here.

---

## Domain model

- **`User`** (Postgres): `userId` (UUID, immutable, the stable cross-service reference), `email` (mutable, but only via a re-verification flow — never a casual edit), `passwordHash` (salted, never encrypted — see [Security](#security-decisions)), `status` (native Postgres enum: `PENDING_VERIFICATION`, `ACTIVE`, `LOCKED`), `creationDate` (immutable, `@CreationTimestamp`).
- **`CustomerProfile`** — deliberately **not** part of this service. `User` holds identity/credentials only; profile/KYC data belongs to a separate service that references `User` by `userId`. `User` has no knowledge of profile data. This boundary exists because credentials and profile data change for different reasons, at different rates, and have different sensitivity — conflating them was considered and rejected early in design.
- **Failed-login counters and active-session tokens live in Redis, not Postgres.** `User` is treated as a low-write, high-importance aggregate; transient, self-expiring, operational state doesn't belong on it.

---

## Security decisions

These aren't defaults — each one was a deliberate design choice, several after catching a real vulnerability during development. Do not "simplify" these without understanding why they exist.

### User enumeration defense
Both **registration** and **login** return **identical responses regardless of outcome**:
- `POST /users` (register): always returns the same generic body, whether the email was new or already taken. The real signal (a verification link, or a "someone tried to register with your email" notice) is delivered over **email**, never over the API response — because the API response is reachable by any anonymous caller, and an anonymous caller learning "this email has an account here" is itself a leak in a banking context.
- `POST /auth/login`: wrong password, nonexistent email, and unverified account all return the exact same `401` + `"Invalid email or password"`. Unlike registration, login failures **don't** get an email side-channel — a legitimate user gets immediate on-page feedback instead, since spamming someone's inbox on every failed login attempt would itself be a DoS vector.

**Context for AI assistants:** if you're asked to make error messages "more helpful" or "more specific" on these two endpoints, stop and flag it — that's very likely reintroducing a user-enumeration vulnerability (OWASP A07:2021) that was specifically closed during development. There are regression tests (`register_sameEmailTwice_returnsIdenticalResponse`, `login_enumerationFailures_returnIdenticalResponse`) guarding this exact behavior — if a change breaks them, the fix is almost certainly the change, not the test.

### Password storage
Passwords are **hashed** (BCrypt, salted), never encrypted. Encryption is reversible and therefore wrong for this use case — a breach of an encrypted password store can be reversed given the key; a breach of a properly salted-hashed store cannot, even by us.

### Email uniqueness under concurrency
Email format is validated at the application layer (fail fast, no DB round-trip for a bad format), but **uniqueness is enforced entirely by a database-level `UNIQUE` constraint**, not application-level "check then insert" logic — the latter is vulnerable to a TOCTOU race under concurrent identical requests. This is proven, not just asserted: see `register_concurrentlyWithSameEmail_createsExactlyOneUser`, which fires two simultaneous registration requests via a `CyclicBarrier` and asserts exactly one row lands.

### Redis session model
- **Keyed by `userId`**, value is the currently active JWT. A new login **overwrites** the previous entry — this is what gives "single active session, new login invalidates the old one" for free, without needing to explicitly hunt down and revoke a prior token.
- **The JWT filter is strictly read-only against Redis.** It never writes, never "heals" a mismatch by replacing what's stored. Only the login endpoint writes to this key. (Early design considered letting the filter reconcile mismatches — this was rejected because it would let a stolen, already-revoked token silently evict a legitimate active session.)
- **Filter check order matters**: JWT signature/expiry is verified *before* the Redis lookup, because it's free, local, and rejects garbage tokens without spending a network round-trip or Redis load on requests that were never going to be valid.

### Fail-closed on Redis unavailability
If Redis is unreachable during the session check, the request is rejected with `503`, not silently allowed through. This was a deliberate call: for a banking system, failing closed (temporarily losing availability) was judged safer than failing open (temporarily losing the real-time-revocation guarantee). The client-side Redis timeout is intentionally short (`2s` in both test and production config) — a healthy Redis responds in milliseconds, so a multi-second wait already indicates real trouble, and the default driver timeout (60s) would otherwise mean a full minute of hung requests during a Redis slowdown.

**Context for AI assistants:** the filter's Redis-failure catch block is typed to `DataAccessException` (Spring's root DAO exception), which is broader than it looks — it's the actual shared ancestor between `RedisConnectionFailureException` and `QueryTimeoutException` (two real, different exceptions this filter has hit in testing). There's an inline comment at the catch site explaining the scope. If this method's `try` block ever grows to include a non-Redis data-access call, revisit this catch — right now it's safe because Redis is the only DAO call in scope.

### Error responses — status codes and message granularity
- `401` for anything auth-related the caller can't fix by rewording their request (bad/missing/expired/revoked token, bad credentials).
- `403` is reserved for "you're authenticated, but not authorized" — not used anywhere yet, since there's no RBAC in this service yet.
- JWT-specific failures (expired vs. tampered vs. no token vs. revoked session) **do** get distinct messages — unlike registration/login, this is safe because only someone who already possesses a token can ever see these responses; there's no anonymous-prober risk here.
- Tampered/invalid signatures are logged at `WARN` (possible attack); expired tokens at `DEBUG` (routine, expected); login failures at `INFO` uniformly across all three reasons (individually unremarkable — the actual attack-pattern detection belongs to rate limiting, not log severity).

---

## Local setup

### Prerequisites
- JDK 21 (not lower — Boot 4.x requires 17+, and this project targets 21 for language features)
- Maven 3.9+
- Docker Desktop (WSL2 backend on Windows)

### Environment variables
Real secrets (`JWT_SECRET`, `POSTGRES_PASSWORD`) must be supplied via environment variables — there are **no working defaults** in `application.yml` on purpose. Options:
- A git-ignored `.env` file at the project root (picked up via `spring.config.import: optional:file:.env[.properties]` — this import is deliberately `optional:` so a missing `.env` in a real deployment is a silent no-op, not a startup failure).
- A local, git-ignored PowerShell script (`set-env.ps1`, dot-sourced before running) if you're not using `.env`.

**Never put a real, working secret in `application.yml`'s default value** — even temporarily. This has happened more than once during development; treat any secret that ever reached a commit as compromised and rotate it, even after removing it from the file (removing it from HEAD doesn't remove it from git history).

### Known environment gotchas (Windows-specific, but documented in case they recur)
- **Timezone**: `application.yml` sets `hibernate.jdbc.time_zone: UTC`, and the JDBC URL includes `?options=-c%20TimeZone=UTC`, because on some Windows machines the JVM resolves the local timezone to a legacy alias (`Asia/Calcutta`) that Postgres's bundled tz data may not recognize, producing a startup failure. Both fixes are needed — Hibernate's setting doesn't cover Flyway's separate raw JDBC connection.
- **Port `8081`**: excluded by a Windows/WSL2 dynamic port reservation on some machines (`netsh interface ipv4 show excludedportrange protocol=tcp`). If the app fails to bind with "port already in use" and no process is actually listening on it, this is why — change `server.port` rather than chasing a phantom process.

### Running

```bash
docker compose up -d      # Postgres + Redis
mvn spring-boot:run
```

---

## Testing

```bash
mvn test
```

- **Unit tests** (`UserServiceTest`): fast, no containers, mock all dependencies. Cover both `registerUser` and all four `loginUser` branches (not found, inactive, wrong password, success).
- **Integration tests** (`AuthControllerIntegrationTest`): full Spring context + real filter chain, backed by **shared, singleton Testcontainers** (one Postgres + one Redis instance for the whole suite, not per-class) for resource efficiency. State is truncated/flushed before every test (`AbstractIntegrationTest.cleanState()`), not after — this matters specifically for tests whose assertions depend on absence of prior state (the concurrency test).
- The Redis-outage test **pauses** (not stops) the shared Redis container mid-test and unpauses it in a `finally` block. This was chosen deliberately over stop/start: Testcontainers assigns a new random port on every container start, which would silently break the app's already-wired connection factory for every subsequent test in the suite. Pause/unpause preserves the container's network identity.

**Context for AI assistants:** if a test related to Redis unavailability or container lifecycle starts failing in a confusing way (especially if *other, unrelated* tests fail immediately afterward with timeouts), check whether a container's identity (host/port) changed mid-suite before assuming the application code is broken.

---

## Known gaps / deliberately deferred

Not oversights — tracked, deliberate scope cuts:

- **No Vault/secrets-manager integration.** Was attempted once mid-sprint, reverted — introducing new infrastructure mid-feature-build was judged to add too many simultaneous variables. A real secrets-management pass is planned as its own scoped piece of work later, once there's a clearer, organically-arising need (this has now happened twice — a real secret was accidentally committed to `application.yml`'s default value twice during development — which is a legitimate argument *for* revisiting this).
- **No Redis HA story.** Fail-closed behavior is implemented and tested, but there's no actual high-availability Redis setup (replication, Sentinel, etc.) — a real Redis outage currently means real authentication downtime, by design, not by accident. Flagged for a later infrastructure-maturity pass.
- **No rate limiting yet.** NFR-A5 (per-IP/source rate limiting + exponential backoff on failed logins) was designed but not implemented — currently only a conceptual defense, not a running one.
- **No `UserRegistered` event / Kafka integration.** The eventual need to notify a Customer/Profile service on registration is acknowledged but not built — Kafka doesn't enter the platform until a later phase, and this hand-off is either synchronous or stubbed for now.

---

## API summary

| Endpoint | Method | Auth required | Notes |
|---|---|---|---|
| `/users` | `POST` | No | Registration. Always returns generic response (see [enumeration defense](#user-enumeration-defense)). |
| `/auth/login` | `POST` | No | Returns a JWT on success. Generic failure message for all rejection reasons. |
| *(protected endpoints)* | — | Yes (`Bearer` JWT) | Signature + expiry checked locally, then Redis session check. |
