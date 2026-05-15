# WEX Take-Home: Purchase Transaction + Currency Conversion Service

## Context

This is a take-home assignment for a WEX Corporate Payments product/engineering role. The deliverable is a self-contained, production-quality application that:

1. **Stores** a purchase transaction (description, date, amount USD) with a generated unique ID.
2. **Retrieves** that transaction converted into a target currency using the U.S. Treasury Reporting Rates of Exchange API, using the most recent rate **on or before** the purchase date and **within the prior 6 months**.

The brief grades *product thinking, execution, and how complex problems are approached* — so correctness on the money/date edge cases, clear documentation of decisions, and a clean runnable repo matter more than feature volume.

### Key decisions (confirmed with user)

| Decision | Choice | Rationale |
|---|---|---|
| Language/stack | **Java 21 + Spring Boot 3.x** | Brief uses Java as the reference; payments reviewers expect `BigDecimal`; Spring Boot is self-contained (embedded Tomcat, no separate servlet container). |
| UI | **Lightweight integrated UI (Thymeleaf single page) + Swagger UI** | Satisfies "UI preferred" with lowest bug risk for a take-home; Swagger gives reviewers an easy API harness. |
| Database | **Embedded H2, file-based** (`jdbc:h2:file:./data/wex`) | Zero install; file-based so persistence across restarts is visibly demonstrable. H2 console enabled for reviewers. |
| Idempotency | **Implemented** — optional `Idempotency-Key` header on POST | Demonstrates production thinking; replays return the originally stored transaction. |
| Auth | **Simple API-key filter, toggleable (disabled by default) + production approach documented** | Reviewers can run with no friction; README explains real-world OAuth2/gateway/mTLS approach. |

## Architecture

Standard layered Spring Boot app:

- **Controller layer**: `TransactionController` (REST) + `WebController` (Thymeleaf page).
- **Service layer**: `TransactionService` (persist/retrieve, validation orchestration), `CurrencyConversionService` (rate lookup + 6-month rule + rounding), `IdempotencyService`.
- **Client layer**: `TreasuryRatesClient` — Spring `RestClient` (or `WebClient`) calling the Fiscal Data API, with connect/read timeouts and a single retry.
- **Persistence**: Spring Data JPA + H2. Entities: `PurchaseTransaction`, `IdempotencyRecord`.
- **Cross-cutting**: `@ControllerAdvice` global exception handler → consistent JSON error body; bean validation; `ApiKeyFilter`.

### Money & rounding (critical — call out in README)

- All money is `java.math.BigDecimal`. No `double`/`float` anywhere.
- Stored purchase amount: scale 2, `RoundingMode.HALF_UP` ("nearest cent").
- Converted amount: `purchaseAmount.multiply(exchangeRate)` at full precision, then `.setScale(2, RoundingMode.HALF_UP)`.
- Exchange rate kept at the API's native precision until the final multiply.

## Data model

`PurchaseTransaction`
- `id` UUID (primary key, server-generated) — the unique identifier.
- `description` VARCHAR(50), not null, validated ≤ 50 chars, not blank.
- `transactionDate` DATE, not null, must parse as ISO date.
- `purchaseAmountUsd` DECIMAL(19,2), not null, must be > 0.
- `createdAt` timestamp.

`IdempotencyRecord`
- `idempotencyKey` (PK), `transactionId`, `requestHash`, `createdAt`.

## API design

**POST `/api/transactions`** — store a transaction
- Body: `{ "description", "transactionDate", "purchaseAmount" }`
- Optional header: `Idempotency-Key`
- 201 → `{ id, description, transactionDate, purchaseAmount }`
- 400 → validation errors (field-level messages)
- Replay with same key + same body → 200 with original record; same key + different body → 422.

**GET `/api/transactions/{id}?currency=Canada-Dollar`** — retrieve converted
- 200 → `{ id, description, transactionDate, originalAmountUsd, exchangeRate, convertedAmount, targetCurrency }`
- 404 → unknown id
- 422 → "the stored purchase cannot be converted to the target currency" (no rate within 6 months on/before date)
- 400 → missing/unknown currency

**GET `/api/currencies`** — distinct `country_currency_desc` values (powers the UI dropdown).

### Treasury API integration

- Base: `https://api.fiscaldata.treasury.gov/services/api/fiscal_service/v1/accounting/od/rates_of_exchange`
- Query: `?fields=country_currency_desc,exchange_rate,record_date&filter=country_currency_desc:eq:{currency},record_date:lte:{purchaseDate}&sort=-record_date&page[size]=1`
- Take the single newest record. **6-month rule**: accept only if `record_date >= purchaseDate.minusMonths(6)` (inclusive boundary) and `record_date <= purchaseDate`. Otherwise → 422 conversion error.
- Document assumptions in README: currency identified by `country_currency_desc` (e.g. `Canada-Dollar`); boundary is inclusive; "6 months" = calendar months via `minusMonths(6)`.
- Resilience: timeouts + one retry; if Treasury API is unreachable → 503 with clear message (distinct from the 422 "no rate" case).

## UI (Thymeleaf single page at `/`)

- Form 1: add transaction (description, date picker, amount) → shows returned id.
- Form 2: retrieve by id + currency dropdown (populated from `/api/currencies`) → shows original, rate, converted amount, or the conversion error.
- Plain server-rendered HTML + minimal JS (fetch). No SPA build step.
- Swagger UI at `/swagger-ui.html` via springdoc-openapi as the API harness.

## Testing strategy (production-grade, functional only)

Framework: JUnit 5 + Mockito + Spring Boot Test (`MockMvc`) + **WireMock** (stub Treasury API) + H2.

Unit tests:
- Validation: description > 50 chars, blank description, negative/zero amount, malformed date.
- Rounding: HALF_UP at the half-cent for store and convert; high-precision rate multiply.
- 6-month boundary logic: rate exactly at `minusMonths(6)` (accepted), one day older (rejected), rate after purchase date (rejected), newest-of-many selected.
- `IdempotencyService`: same key/body replay, same key/different body conflict.

Integration tests (MockMvc + WireMock):
- Store → 201, persisted, retrievable.
- Store validation failures → 400 with field messages.
- Retrieve with stubbed rate → correct converted amount.
- Retrieve when WireMock returns no qualifying record → 422 conversion error.
- Retrieve unknown id → 404.
- Treasury API 500/timeout → 503.
- Idempotency replay end-to-end.

Goal: `git clone && ./mvnw test` passes on a machine with only a JDK.

## Project structure

```
pom.xml, mvnw, mvnw.cmd, .mvn/            # wrapper committed — no Maven install needed
src/main/java/.../wex/
  WexApplication.java
  controller/ service/ client/ domain/ repository/ config/ exception/
src/main/resources/
  application.yml
  templates/index.html
  static/app.js
src/test/java/...
src/test/resources/wiremock/
README.md
```

## Run instructions (goes in README)

- **Prereq**: JDK 21 only. No DB/server install.
- **Test**: `./mvnw test`
- **Run**: `./mvnw spring-boot:run` → open `http://localhost:8080/` (UI), `http://localhost:8080/swagger-ui.html` (API), `http://localhost:8080/h2-console` (DB).
- **Or jar**: `./mvnw clean package && java -jar target/wex-*.jar`
- README documents: assumptions, money/rounding decisions, 6-month interpretation, idempotency behavior, auth (how to enable the API-key filter via `app.auth.enabled=true` + `app.auth.api-key`, and the production approach: OAuth2/OIDC at an API gateway, mTLS service-to-service, secrets in a vault, rate limiting).

## Verification (end-to-end before submitting)

1. Fresh clone in a temp dir → `./mvnw test` green.
2. `./mvnw spring-boot:run`; via UI: add a transaction dated ~2023-07-15, retrieve in `Canada-Dollar` → sane converted amount and a real rate.
3. Retrieve with a date that has no rate within 6 months (e.g. very old date) → 422 conversion error message.
4. Validation: 51-char description and negative amount → 400 with clear messages.
5. POST twice with the same `Idempotency-Key` → identical response, one DB row.
6. Restart app → previously stored transaction still retrievable (file-based H2).
7. Enable auth, confirm 401 without key / 200 with key.

## Production Spec — how this is fully productionized (document in README + be ready to defend)

The take-home is deliberately scoped; this section is the "if this shipped at WEX" design and is a likely interview deep-dive.

- **Compute & packaging**: containerized (Docker), deployed to Kubernetes / ECS with horizontal autoscaling; stateless app instances behind a load balancer.
- **Datastore**: managed Postgres (RDS/Cloud SQL) replacing embedded H2; Flyway/Liquibase migrations; read replicas if needed; daily backups + PITR.
- **Treasury rates — batch ingestion (key design insight)**: instead of calling the Treasury API live per request, run a scheduled job (e.g. nightly) that ingests rates into our own `exchange_rate` table. Requests then resolve rates from our DB → fast, resilient to Treasury downtime, auditable, and the 6-month/`<=` query becomes a simple indexed SQL lookup. Live API call kept only as a backfill/fallback path. (Strong point to raise unprompted in the interview.)
- **Resilience**: circuit breaker + retry with backoff on any outbound call (Resilience4j); explicit timeouts; bulkhead so Treasury slowness can't exhaust threads.
- **Idempotency at scale**: idempotency store with TTL eviction; persisted request hash; safe under retries and concurrent duplicates (unique constraint + insert-or-return).
- **Security**: TLS everywhere; authN/authZ via OAuth2/OIDC enforced at an API gateway; service-to-service mTLS; secrets in a vault (AWS Secrets Manager / Vault), never in config; per-client rate limiting; input validation at the edge; audit log of all writes.
- **Observability**: structured JSON logs with correlation IDs; metrics (RED — rate/errors/duration) to Prometheus/Grafana; distributed tracing (OpenTelemetry); alerting on conversion-error rate and Treasury-ingest failures.
- **Data/compliance**: amounts/currency are financial data → retention policy, immutable audit trail, no PII beyond description (validated/escaped); decimal precision policy documented.
- **CI/CD**: pipeline runs build + full test suite + static analysis (SpotBugs) + dependency/vuln scan; trunk-based with required checks; blue/green or canary deploy with automated rollback.
- **API governance**: versioned API (`/v1`), OpenAPI contract published, backward-compatibility policy.

## CLAUDE.md for the CLI-driven build (create at project root)

Before building, drop a `CLAUDE.md` at the repo root so your Claude Code CLI produces consistent, review-ready code. It should contain (concise, imperative):

- **Stack**: Java 21, Spring Boot 3.x, Maven (wrapper committed), H2 file DB, Thymeleaf, springdoc-openapi, JUnit 5 + Mockito + WireMock.
- **Build/test commands**: `./mvnw test`, `./mvnw spring-boot:run`, `./mvnw clean package`.
- **Money rules (hard constraints)**: `BigDecimal` only — never `double`/`float`; store amount scale 2 `HALF_UP`; converted = `amount.multiply(rate)` then `setScale(2, HALF_UP)`; keep rate at native precision until final multiply.
- **6-month rule (exact)**: select newest Treasury record with `record_date <= purchaseDate`; accept only if `record_date >= purchaseDate.minusMonths(6)` (inclusive); else 422 conversion error.
- **Error-mode separation**: "no rate within 6 months" → 422 (expected, clear message); "Treasury API unreachable/5xx/timeout" → 503. Never conflate.
- **Validation**: description not blank & ≤ 50 chars; date valid ISO; amount > 0.
- **Idempotency**: optional `Idempotency-Key` header on POST; same key+body → original record; same key+different body → 422.
- **Commenting policy**: see "Code commenting policy" below — reviewer-readable, WHY-focused, Javadoc on public service/controller methods.
- **Testing expectation**: every requirement + every unhappy path has an automated test; `git clone && ./mvnw test` must pass with only a JDK.
- **Scope guardrail**: implement only the brief + confirmed decisions; do not add frameworks, queues, or auth servers.

This file both steers the CLI and signals engineering discipline to the reviewer.

## Execution plan — parallelism & time estimate

This is a small, tightly-coupled single module (controller → service → client → repo), so unbounded parallelism causes integration churn. Recommended approach:

- **Phase 0 — skeleton (serial, 1 tab, ~10 min)**: scaffold project, `pom.xml` + wrapper, domain entities, repository interfaces, `application.yml`, empty service/controller signatures + the `CLAUDE.md`. Commit. This defines the contracts the parallel work depends on.
- **Phase 1 — fan out (up to 3 parallel tabs/worktrees, ~25–35 min)**:
  - Tab A: persistence + `TransactionService` + `TransactionController` + validation + global exception handler.
  - Tab B: `TreasuryRatesClient` + `CurrencyConversionService` (6-month rule, rounding) + `IdempotencyService`.
  - Tab C: test suite + WireMock fixtures + UI (Thymeleaf page, `app.js`) + Swagger config + README.
- **Phase 2 — integration & verification (serial, 1 tab, ~10–15 min)**: merge worktrees, run full suite, fresh-clone smoke test, manual UI/Swagger pass.

**Totals**: ~3 parallel tabs → **~45–60 min wall-clock**. Single tab end-to-end (safest, least merge risk) → **~60–90 min**. More than 3 tabs gives diminishing returns and raises conflict cost — **3 is the practical max**; **2 (A+B together as one, C separate) is the sweet spot** for least friction.

## Code commenting policy (reviewer-focused — explicit user requirement)

For this graded take-home, comments are a feature, not noise — but keep them meaningful:

- **Javadoc on every public controller/service/client method**: state the contract, inputs, the assumption it encodes, and which requirement it satisfies (e.g. "Implements Requirement #2: nearest qualifying rate ≤ purchase date within 6 months").
- **Inline `// WHY` comments on the non-obvious logic only**: the inclusive 6-month boundary, the rounding mode choice, the idempotency hash/conflict rule, the 422-vs-503 split. Explain the *reasoning*, reference the brief.
- **Class-level summary** on each service explaining its responsibility.
- **No redundant noise** (no `// increment i`); comments explain decisions a reviewer would otherwise have to ask about.
- README is the top-level commentary: assumptions, tradeoffs, what you'd do with more time.

## Dos & Don'ts — using Claude and submitting to WEX

**Do**
- Understand every line. Be able to whiteboard the 6-month rule and `BigDecimal` rounding live — these are the most likely interview deep-dive.
- Document assumptions and tradeoffs explicitly (idempotency model, auth, currency-key choice, rounding mode, inclusive boundary). Reviewers reward stated reasoning.
- Test the unhappy paths hardest (boundary date, missing rate, validation, API down) — that's where take-homes are won.
- Commit the Maven wrapper and verify a clean clone runs with only a JDK installed.
- Write the README in your own voice; keep git history clean and meaningful.
- Be ready to honestly discuss AI assistance and defend the design as your own.

**Don't**
- Over-engineer: no microservices, message queues, external DB, or full auth server. Match scope to the brief.
- Commit secrets or AI co-author trailers / "Generated with Claude" comments unless you deliberately choose to disclose — decide consciously, don't leak them by default.
- Leave dead code, TODOs, commented-out blocks, or failing/flaky tests.
- Use `double`/`float` for money anywhere.
- Conflate the two failure modes: "no rate within 6 months" (422, expected) vs "Treasury API unreachable" (503, infra) must be distinct.
- Skip the fresh-clone verification — a repo that doesn't run on first try fails the "Production: team can run code without bugs" bar immediately.
