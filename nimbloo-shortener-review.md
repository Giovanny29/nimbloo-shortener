# Technical Review: Nimbloo URL Shortener

**Review date:** 2026-08-17  
**Target level:** Junior Software Engineer  
**Specification reviewed:** `teste-tecnico-jr-encurtador.md`  
**Repository revision:** `4c9ca6e` on `main`  

## Executive summary

This is a strong Junior-level submission in breadth, presentation, and basic implementation. It satisfies most mandatory requirements, starts successfully with Docker Compose, passes all 53 backend tests, builds the frontend, and supports the main end-to-end flows.

The main weakness is that both optional infrastructure features, Redis caching and asynchronous SQS click tracking, introduce failure modes that the implementation does not fully handle. Several README claims about resilience and asynchronous behavior are contradicted by live tests. There is also a frontend error-contract bug that hides useful backend messages, plus pagination behavior that can make links unreachable from the UI.

**Hiring recommendation:** proceed to technical interview, with focused questions on cache consistency, asynchronous APIs, race conditions, and integration testing. Do not reject solely from these findings. For a Junior role, ability to identify and repair them during discussion matters more than having anticipated every distributed-systems edge case.

**Overall assessment:** approximately **7/10 for a Junior submission**. Above average ambition and documentation, but optional complexity reduced correctness.

## Verdict by area

| Area | Assessment | Notes |
|---|---|---|
| Mandatory backend endpoints | Pass | All required routes exist and normal flows work. |
| Java 21 and Spring Boot 3 | Pass | Java 21 and Spring Boot 3.3.5. |
| DynamoDB persistence | Pass | DynamoDB Local starts and CRUD works. |
| Input validation | Pass | Malformed URL, non-HTTP schemes, and past expiration are rejected. |
| Expired and disabled redirects | Partial | Normal case works, but stale Redis data can redirect a disabled link after a failed delete response. |
| Click counting | Pass with caveats | Count updates through SQS, but send blocks during outage and delivery is not integration-tested. |
| Automated tests | Pass | 53 tests pass, including happy and error paths. Most infrastructure behavior is mocked. |
| Docker Compose startup | Pass | All four services built and started successfully. |
| Required frontend | Partial | Form, copy, list, statuses, loading, error, and empty states exist. Error details are lost because thrown API errors are not `Error` instances. |
| README requirements | Pass with inaccuracies | Run instructions, trade-offs, limitations, future work, and AI disclosure exist. Some resilience claims are incorrect. |
| Bonus limit | Pass | Exactly two bonuses selected: SQS and Redis. |

## What was done well

### 1. Submission is runnable

`docker compose up -d --build` successfully built and started:

- Spring Boot backend
- DynamoDB Local
- Redis
- LocalStack with SQS

The root page returned HTTP 200, and the empty list endpoint returned a valid paginated response. This directly satisfies one of the most important evaluation constraints.

### 2. Main API behavior works

Live checks confirmed:

- Link creation returns HTTP 201.
- Redirect returns HTTP 302 with correct `Location`.
- Click count reaches 1 after redirect.
- Delete returns HTTP 204.
- Disabled link normally returns HTTP 404.
- Invalid scheme returns HTTP 400.
- Past expiration returns HTTP 400.
- Duplicate alias returns HTTP 409.

### 3. Basic data-race protection is thoughtful

`UrlItemRepository.saveIfAbsent` uses a conditional DynamoDB write, so alias creation does not rely only on a check-then-write sequence. Generated codes retry after collision. Click and ID counters use DynamoDB atomic increments.

These choices show awareness of concurrency beyond typical Junior submissions.

### 4. Required validation is present in two layers

`CreateLinkRequest` applies Bean Validation, while `LinkService` validates programmatic calls. URL scheme and host checks are present, expiration must be future-dated, aliases have a constrained character set, and URL length is capped.

### 5. Tests cover required paths

All **53 tests passed**:

```text
Tests run: 53, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

Coverage includes creation, redirect, duplicate aliases, malformed requests, past expiration, expired links, disabled links, reserved counter protection, cache hits, corrupted cached JSON, and pagination bounds.

### 6. Frontend covers requested states

Frontend includes:

- Creation form
- Optional alias and expiration
- Copy action
- Paged list
- Code, destination, clicks, status, and creation date
- Loading state
- Error state with retry
- Empty state
- Delete confirmation

Production TypeScript and Vite build completed successfully.

### 7. Documentation is unusually thorough

README explains architecture, trade-offs, known limitations, future work, troubleshooting, and AI assistance. AI use is disclosed explicitly, satisfying specification requirement.

README is longer than needed for a six-hour Junior exercise, but it provides useful interview material and demonstrates written communication.

## Confirmed findings

### High: Redis outage breaks redirect instead of falling back to DynamoDB

**Location:** `backend/src/main/java/com/nimbloo/shortener/service/LinkService.java:169`

`redisTemplate.opsForValue().get(...)` runs outside the surrounding deserialization `try/catch`. A Redis timeout escapes the service and becomes HTTP 500.

**Live evidence:** stopping Redis and requesting an existing short link returned:

```text
redirect_with_redis_down=500
Redis command timed out
```

**Why this matters:** README states Redis is only an accelerator and that Redis exceptions are swallowed so redirects continue through DynamoDB. Current behavior contradicts that design promise.

**Recommended fix:** wrap both Redis read and deserialization in failure handling, log the cache failure, then query DynamoDB.

### High: Redis outage can leave a disabled link redirecting

**Location:** `backend/src/main/java/com/nimbloo/shortener/service/LinkService.java:165`

Disable flow performs these operations:

1. Reads item from DynamoDB.
2. Writes `active=false` to DynamoDB.
3. Deletes cached item from Redis.

If step 3 fails, API returns HTTP 500 after DynamoDB already committed the disable. Old active cache remains.

**Live evidence:** with Redis stopped during delete:

```text
delete_with_redis_down=500
redirect_after_recovery=302
detail.status=DISABLED
```

DynamoDB reported disabled, but redirect still used stale Redis data and returned 302 after Redis recovered.

**Why this matters:** this violates mandatory requirement that disabled links must not redirect.

**Recommended fix:** simplest safe option for this exercise is removing Redis bonus. If cache remains, failure semantics need deliberate design, such as short TTL plus a durable invalidation strategy or treating DynamoDB as authoritative before every redirect. Catching delete errors alone would avoid 500 but would not fix stale redirects.

### High: SQS send is synchronous on redirect path

**Location:** `backend/src/main/java/com/nimbloo/shortener/service/LinkService.java:200`

`SqsTemplate.send(...)` is called directly on request thread. Method name `dispatchClickEventAsync` and README describe fire-and-forget behavior, but this call waits for SQS operation.

**Live evidence:** stopping LocalStack and redirecting produced HTTP 302 only after approximately:

```text
duration_ms=8316
```

**Why this matters:** queue outage adds more than eight seconds to redirect latency. Main user path is therefore coupled to optional analytics infrastructure.

**Recommended fix:** use asynchronous SQS API and attach completion error handling, or remove SQS bonus and increment atomically in DynamoDB for this exercise.

### High: Disable can overwrite concurrent click increments

**Locations:**

- `backend/src/main/java/com/nimbloo/shortener/service/LinkService.java:163`
- `backend/src/main/java/com/nimbloo/shortener/repository/UrlItemRepository.java:45`

Disable reads complete item, changes `active`, then writes complete item with `putItem`. SQS consumer increments `click_count` independently with atomic `ADD`.

A possible race:

1. Disable reads `click_count=10`.
2. SQS increments stored value to 11.
3. Disable writes stale complete item with `click_count=10`.

**Impact:** click data can be lost.

**Recommended fix:** disable with atomic DynamoDB update that changes only `active`, for example `SET active = :false`, rather than replacing complete item.

### High: Frontend discards backend error messages

**Location:** `frontend/src/api.ts:21`

`request` throws plain object returned by `parseError`:

```ts
throw await parseError(response);
```

UI catches test `err instanceof Error`. Plain object fails this check, so frontend replaces specific backend messages with generic messages.

**Impact:** duplicate alias, validation detail, and server-provided messages are hidden. Claimed explicit field-level server errors do not work as documented.

**Recommended fix:** define `ApiError extends Error`, retain HTTP status and field errors on instance, then throw that instance.

### Medium: Status filtering can trap user on first loaded page

**Location:** `frontend/src/components/LinkList.tsx:180`

Status filtering only examines already-loaded items. When selected status has no match on current page, UI shows “Nenhum link” and hides controls containing “Carregar mais,” even if later pages contain matching links.

**Impact:** existing links can appear absent and become unreachable through filter.

**Recommended fix:** keep pagination control visible when `hasMore` is true, or move filtering to backend so pagination and filter operate on same dataset.

### Medium: Counter filtering can produce empty pages with more data available

**Location:** `backend/src/main/java/com/nimbloo/shortener/repository/UrlItemRepository.java:124`

DynamoDB applies scan limit before filter expression. Internal `__counter__` item can consume page limit and then be removed by filter.

**Live evidence with `pageSize=1`:** one page returned zero items while reporting `hasMore=true`, and cursor exposed internal key:

```text
items=[]
lastEvaluatedKey=__counter__
hasMore=true
```

**Impact:** frontend treats zero-item first page as globally empty and does not offer “Carregar mais.” Internal reserved key also leaks through pagination metadata despite explicit attempts not to expose it elsewhere.

**Recommended fix:** store counter separately, or continue scanning until requested number of visible links is collected or scan is exhausted.

### Medium: Infrastructure initialization failures do not fail startup

**Location:** `backend/src/main/java/com/nimbloo/shortener/config/AwsResourceInitializer.java:67`

Initializer catches all DynamoDB and SQS setup exceptions, logs them, and lets application remain ready.

**Impact:** Compose may report backend running while required resources are absent, causing runtime failures on first request.

**Recommended fix:** propagate initialization failures so container exits and Compose shows unhealthy startup. If SQS is intentionally optional, separate required DynamoDB failure from optional analytics failure.

### Medium: Infrastructure behavior is almost entirely mocked

**Locations:**

- `backend/src/test/java/com/nimbloo/shortener/service/LinkServiceTest.java`
- `backend/src/test/java/com/nimbloo/shortener/controller/LinkControllerTest.java`

Test count is high, but DynamoDB conditional writes, atomic updates, scan filtering, Redis outages, SQS listener delivery, DLQ behavior, and full HTTP-to-storage flow are not automated against real services.

**Impact:** most important confirmed bugs occur exactly at mocked boundaries.

**Recommended fix:** add a small integration suite using Testcontainers or existing Docker Compose stack. Three focused tests would provide more confidence than many additional mock tests:

1. Create, redirect, click count, disable.
2. Redirect during Redis outage.
3. Disable racing with click increment or at least atomic active update verification.

### Medium: Maven wrapper is not executable

**Location:** `backend/mvnw:1`

Git tracks wrapper with mode `100644`. README documents `./mvnw test`, but command fails on Linux/macOS:

```text
./backend/mvnw: Permission denied
```

**Recommended fix:** commit executable bit:

```bash
chmod +x backend/mvnw
git add backend/mvnw
```

### Medium: README promises `fieldErrors`, API does not return them

**Location:** `backend/src/main/java/com/nimbloo/shortener/exception/GlobalExceptionHandler.java:48`

Validation handler returns only first field error in `message`. README says response includes `fieldErrors` per field.

**Impact:** frontend contract and documentation disagree with backend.

**Recommended fix:** either return stable `fieldErrors` map or remove claim and keep one-message contract. For required scope, simpler documentation correction is acceptable.

### Low: Frontend toolchain has known development-server vulnerabilities

**Location:** `frontend/package.json:21`

`npm audit` reported:

```text
2 vulnerabilities: 1 moderate, 1 high
```

Findings affect Vite/esbuild development tooling, including Vite path traversal advisories. Production app serves compiled static assets from Spring Boot, so production exposure is lower than audit severity suggests.

**Recommended fix:** upgrade Vite to supported patched version, update related plugin if needed, regenerate lockfile, then rebuild.

### Low: Generated short URL assumes localhost by default

**Location:** `backend/src/main/java/com/nimbloo/shortener/service/LinkService.java:45`

`app.base-url` defaults to `http://localhost:8080`, and Compose does not explicitly configure deployment URL.

**Impact:** correct for local evaluation, wrong when deployed elsewhere.

**Recommended fix:** expose `APP_BASE_URL` in Compose/deployment documentation. This is not a blocker for local technical test.

## Scope and engineering judgment

Candidate chose both Redis and SQS, maximum allowed bonus count. This demonstrates ambition, but both bonuses created highest-severity defects.

For a six-hour Junior exercise, simpler design likely scores better:

- DynamoDB only
- Atomic click increment during redirect
- No Redis cache
- Required frontend states
- One small integration test

That version would contain fewer moving parts, fewer external dependencies, and stronger mandatory behavior. Specification explicitly prefers small, well-finished scope over broad, partial scope.

This does not make bonus choices inherently wrong. Interview should test whether candidate recognizes trade-off and can explain when complexity becomes justified.

## Code quality notes

### Positive

- Clear package boundaries: controller, service, repository, DTO, entity, config.
- Constructor injection used consistently.
- Conditional write protects alias uniqueness race.
- Atomic counters are appropriate DynamoDB operations.
- Reserved key protection shows response to discovered edge case.
- Naming is generally understandable.
- Frontend components remain reasonably small.
- TypeScript types mirror API shape.

### Concerns

- `LinkService` owns validation, caching, persistence orchestration, queue dispatch, pagination mapping, and URL generation. Still manageable at current size, but bonus features made it failure-prone.
- README sometimes presents desired behavior as verified behavior. Example: Redis fallback claim is not true for cache reads.
- Several comments explain obvious mechanics while subtle failure semantics remain undocumented.
- Large test count may create false confidence because AWS and Redis boundaries are mocked.
- Repository history contains very large feature commits and several near-immediate follow-up fixes. This is not disqualifying, but interview should verify understanding rather than infer authorship from commit granularity.

## AI-use assessment

README meets disclosure requirement and clearly describes claimed division of work. Code review alone cannot verify which lines were generated by AI or establish authorship.

Best evaluation method is discussion and live modification. Ask candidate to:

1. Explain why `SqsTemplate.send` is or is not asynchronous.
2. Diagnose Redis outage returning 500.
3. Explain stale-cache behavior after disable.
4. Replace whole-item disable write with atomic field update.
5. Explain why mock tests missed these bugs.
6. Repair frontend `ApiError` handling without external help.

If candidate can reason through these points, project is strong evidence of Junior readiness even if AI helped produce parts. If candidate cannot explain core flows or SDK choices, README authorship claims become less credible.

## Suggested interview questions

### Backend fundamentals

1. What guarantees does `attribute_not_exists(code)` provide compared with `existsByCode` followed by `putItem`?
2. Why can whole-item `putItem` lose a concurrent atomic click increment?
3. What happens if DynamoDB succeeds but Redis invalidation fails?
4. Which datastore is source of truth, and does redirect implementation consistently enforce that?
5. Why does DynamoDB `Scan` apply `limit` before filter expression?

### Asynchronous processing

1. Is `SqsTemplate.send` asynchronous from HTTP request perspective?
2. What behavior should redirect have when SQS is unavailable?
3. How would duplicate SQS delivery affect metrics?
4. How would idempotent click processing work?
5. Why does DLQ configuration not solve request latency?

### Frontend

1. Why does `throw { status, message }` fail `instanceof Error`?
2. How should API field errors be represented in TypeScript?
3. Why can client-side filtering conflict with cursor pagination?
4. What should happen when current page has no matches but `hasMore=true`?

### Testing

1. Which bugs can unit mocks not detect here?
2. What are minimum three integration tests worth adding?
3. How would Redis and SQS failures be tested deterministically?
4. Why is test count not same as confidence?

### Scope

1. Given six-hour limit, would candidate still choose both bonuses?
2. Which bonus would be removed first and why?
3. What mandatory behavior deserves protection before optional scaling work?

## Recommended fix order

1. Make disabled-link invariant correct under Redis failure.
2. Handle Redis reads as optional cache failures.
3. Remove SQS blocking from redirect path.
4. Replace whole-item disable write with atomic active update.
5. Fix frontend error class.
6. Fix pagination/filter empty-page behavior.
7. Add one real integration suite.
8. Make Maven wrapper executable.
9. Align README claims with tested behavior.
10. Upgrade Vite tooling.

## Final hiring calibration

### Reasons to advance

- Completed full-stack assignment.
- Required stack and persistence used correctly in normal path.
- Docker Compose works.
- Required API and frontend surface exists.
- Main happy and error paths are tested.
- Documentation and AI disclosure are strong.
- Candidate attempted concurrency-safe DynamoDB operations.

### Reasons for caution

- Optional features weakened mandatory correctness.
- README overstates Redis resilience and SQS asynchrony.
- Important distributed-system boundaries lack integration tests.
- Frontend API error contract is broken despite documentation claiming explicit handling.
- Pagination composition has user-visible correctness gaps.

### Recommendation

**Advance to interview.** Treat submission as promising Junior work, not production-ready service. Focus interview on reasoning, ownership, and ability to simplify. Strong live diagnosis would raise assessment significantly. Inability to explain cache consistency, blocking SQS send, or mocked boundaries would lower it.

## Verification record

Performed without TokenSave and without storing project details in assistant memory, per request.

| Check | Result |
|---|---|
| Git working tree before review | Clean |
| Docker Compose config validation | Pass |
| Full Docker Compose build/start | Pass |
| Four service health/startup | Pass |
| Root web response | HTTP 200 |
| Empty list API | Pass |
| Create/redirect/click/delete flow | Pass under healthy infrastructure |
| Invalid scheme | HTTP 400 |
| Past expiration | HTTP 400 |
| Duplicate alias | HTTP 409 |
| Backend tests | 53 passed |
| Frontend TypeScript/Vite build | Pass |
| Cursor pagination across multiple pages | Normal dataset pass, counter-filter empty-page defect confirmed |
| Redis outage redirect | Fail, HTTP 500 |
| Redis outage disable consistency | Fail, stale active redirect confirmed |
| SQS outage redirect latency | Fail, approximately 8.3 seconds |
| Frontend dependency audit | 1 moderate and 1 high vulnerability |
| Git working tree after review | Clean |
| Review containers after verification | Stopped and removed |
