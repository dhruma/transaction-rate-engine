# Transaction Rate Engine

A self-contained Spring Boot service that:

1. **Stores** a purchase transaction (description, date, USD amount) and assigns a unique id.
2. **Retrieves** a stored transaction converted into a target currency using the
   [U.S. Treasury Reporting Rates of Exchange API](https://fiscaldata.treasury.gov/datasets/treasury-reporting-rates-exchange/treasury-reporting-rates-of-exchange),
   using the most recent rate **on or before** the purchase date and **within the prior 6 months**.

Built to be run as if in production: layered design, full automated functional tests, embedded
database, and no external infrastructure to install.

## Setup & running

All build / install / run / test instructions live in **[SETUP.md](SETUP.md)** —
per-OS JDK 21 setup (macOS Apple Silicon by default, Intel, Windows), `git clone`,
`./mvnw spring-boot:run`, `./mvnw test`, and connecting the H2 console.

The rest of this document is **reference** — what the service does and how it works. It is
not needed to set up or run the app.

## API

### Store a transaction — `POST /api/transactions`

```bash
curl -X POST http://localhost:8080/api/transactions \
  -H 'Content-Type: application/json' \
  -d '{"description":"Conference ticket","transactionDate":"2024-03-15","purchaseAmount":250.00}'
```

`201 Created`:

```json
{ "id": "f1c2...", "description": "Conference ticket",
  "transactionDate": "2024-03-15", "purchaseAmount": 250.00 }
```

Field rules: `description` non-blank and ≤ 50 chars; `transactionDate` valid `yyyy-MM-dd`;
`purchaseAmount` a positive number (rounded to the nearest cent on store).

### Retrieve converted — `GET /api/transactions/{id}?currency=Canada-Dollar`

```bash
curl 'http://localhost:8080/api/transactions/<id>?currency=Canada-Dollar'
```

`200 OK`:

```json
{ "id": "f1c2...", "description": "Conference ticket", "transactionDate": "2024-03-15",
  "originalAmountUsd": 250.00, "targetCurrency": "Canada-Dollar", "isoCode": "CAD",
  "exchangeRate": 1.357, "exchangeRateDate": "2024-03-31", "convertedAmount": 339.25 }
```

`isoCode` is the ISO 4217 code when known (best-effort — the Treasury dataset has no ISO
codes; see *List currencies*). It is `null` for currencies not in the catalog.

**USD → USD:** the Treasury dataset has no USD record (every rate is quoted against USD),
so `?currency=USD` is a special case: it returns `exchangeRate: 1.00`, `convertedAmount`
equal to the original amount, `isoCode: "USD"`, and makes **no** outbound Treasury call.

### List stored transactions — `GET /api/transactions`

Returns the most recent stored transactions, newest first (id, description, date, amount).
Not a brief requirement — it backs the UI's click-to-select list so retrieval doesn't
require copying ids by hand. Accepts an optional `?limit=` (default 20, capped at 100) so
the response stays bounded as data grows. The `X-Total-Count` response header reports the
**full number of stored transactions** — the cap is a view limit, never a storage limit.
The UI shows "Showing N of <total>" so it is always clear that every transaction is
persisted; fetch more with a larger `?limit=`.

### List currencies — `GET /api/currencies`

Selectable target currencies for the UI dropdown. Returns
`[{ "value": "...", "label": "..." }]` where `value` is what you pass to the convert
endpoint and `label` includes the ISO 4217 code when known:

```json
[ { "value": "USD", "label": "United States-Dollar (USD)" },
  { "value": "Canada-Dollar", "label": "Canada-Dollar (CAD)" },
  { "value": "Afghanistan-Afghani", "label": "Afghanistan-Afghani" } ]
```

USD is always first (the no-conversion passthrough). ISO codes are a curated, best-effort
map over the common currencies plus a rule mapping every `*-Euro` variant to `EUR` — the
Treasury Reporting Rates of Exchange dataset does not publish ISO codes, so currencies
outside the catalog show the Treasury name with no code (an accepted limitation).

## Idempotency

`POST /api/transactions` accepts an optional `Idempotency-Key` header. Behaviour by case:

| Requests | Result |
|---|---|
| Key sent on **only one** request (or different keys) | Each is an independent create — `201`, new id. Idempotency is not engaged; this is **not** a conflict. |
| **Same key + same body** (sent on *both* requests) | `201` with the **same id** as the first — a replay, no duplicate row. |
| **Same key + different body** | `422 IDEMPOTENCY_CONFLICT` — reusing a key with changed data is rejected rather than silently returning the wrong record. |
| **Same key, two concurrent requests** | One wins; the other gets `409 DUPLICATE_REQUEST`. No duplicate is ever persisted. |

> **Gotcha:** the key must be sent on **every** request you want treated as idempotent. In
> Swagger UI the `Idempotency-Key` box is per-call and does **not** carry over between
> "Execute" clicks — a blank key on the second call creates a new transaction (a new id),
> which is correct behaviour, not a bug. Verify by comparing the `id` in the two responses:
> same id ⇒ replay; new id ⇒ the key wasn't sent.

Body equivalence is by **value, not text**: `purchaseAmount` `205`, `205.0`, and `205.00`
are the same purchase and hash identically (so they replay, they don't conflict).

```bash
KEY=$(uuidgen)
# 1st — creates
curl -s -X POST http://localhost:8080/api/transactions \
  -H 'Content-Type: application/json' -H "Idempotency-Key: $KEY" \
  -d '{"description":"demo","transactionDate":"2026-05-16","purchaseAmount":205}'
# 2nd — SAME key + SAME body → 201, SAME id (replay)
curl -s -X POST http://localhost:8080/api/transactions \
  -H 'Content-Type: application/json' -H "Idempotency-Key: $KEY" \
  -d '{"description":"demo","transactionDate":"2026-05-16","purchaseAmount":205}'
# 3rd — SAME key + CHANGED body → 422 IDEMPOTENCY_CONFLICT
curl -s -o /dev/null -w '%{http_code}\n' -X POST http://localhost:8080/api/transactions \
  -H 'Content-Type: application/json' -H "Idempotency-Key: $KEY" \
  -d '{"description":"demo-CHANGED","transactionDate":"2026-05-16","purchaseAmount":999}'
```

**Implementation:** the key is **claimed before the transaction is created**, and uniqueness
is enforced by the `idempotency_key` **primary-key constraint** (insert-or-return), not an
app-level check-then-write — so a concurrent duplicate cannot create a second row. Sequential
replay, body-mismatch conflict, and the lost-race-creates-nothing path are covered by
automated tests. Idempotency is an API-level concern and is deliberately not surfaced in the
web UI — most users have no reason to set a key by hand.

## Error model

A consistent JSON error body is returned for every failure. Status codes deliberately
separate two distinct failure families:

| Status | When |
|---|---|
| `400` | Invalid input (description > 50, non-positive amount, bad date, malformed JSON) |
| `400 BAD_PARAMETER` | Missing / non-UUID id, or a `currency` that is blank, over-long, or contains a Treasury filter separator (`,` `:`) |
| `404` | Transaction id not found |
| `409 DUPLICATE_REQUEST` | Same `Idempotency-Key` claimed concurrently — the losing request; no transaction was created |
| `422 CONVERSION_UNAVAILABLE` | No exchange rate on/before the purchase date within 6 months — an **expected business outcome** |
| `422 IDEMPOTENCY_CONFLICT` | `Idempotency-Key` reused with a different body |
| `503 TREASURY_UNAVAILABLE` | Treasury API unreachable / timed out / 5xx — an **infrastructure fault** |

Conflating the 422 ("we cannot convert this") and 503 ("the dependency is down") cases would
mislead both API clients and on-call engineers, so they are kept separate.

## Key design decisions & assumptions

- **Money is `BigDecimal`, never `double`/`float`.** Stored amount is rounded to the nearest
  cent with `HALF_UP`. The converted amount is computed as `amount × rate` at the rate's
  native precision, then rounded to 2 decimals with `HALF_UP`.
- **6-month window is inclusive.** A rate dated exactly `purchaseDate.minusMonths(6)` is
  accepted; older is rejected. `minusMonths(6)` is calendar-aware. This is a documented,
  defensible reading of an ambiguous requirement — in a real setting it would be confirmed
  with the product owner.
- **Currency is identified by the Treasury `country_currency_desc`** value (e.g.
  `Canada-Dollar`), exactly as the API exposes it; the UI dropdown is populated from the API.
- **Rate selection**: the newest record with `record_date <= purchaseDate` is fetched
  (`sort=-record_date`, `page[size]=1`); the 6-month check is then applied in the service so
  it is independently unit-tested.
- **Embedded file-based H2** (`jdbc:h2:file:./data/wex`) so stored transactions survive a
  restart while needing zero DB install. (The `data/` directory is git-ignored.)

## Authentication

A simple shared-secret API-key filter is included but **disabled by default** so reviewers
can run the service friction-free. Enable it with:

```bash
./mvnw spring-boot:run \
  -Dspring-boot.run.arguments="--app.auth.enabled=true --app.auth.api-key=secret123"
```

Then `/api/**` requires header `X-API-Key: secret123` (UI, Swagger and H2 console stay open).

**In production** this in-app key would be replaced by authentication enforced at an API
gateway (OAuth2/OIDC for clients), mTLS for service-to-service calls, secrets stored in a
vault rather than configuration, and per-client rate limiting.

## Testing

`./mvnw test` runs 50 functional tests (no network required — the Treasury API is stubbed
with WireMock):

- **Unit**: conversion + rounding, the inclusive 6-month boundary (exact / one-day-older /
  no-rate / **month-end clamp**, e.g. Aug 31 → Feb 28), a rate dated **after** the
  purchase date, an unusable (missing / non-positive) rate, idempotency replay vs.
  conflict, store-amount rounding, the currency ISO catalog, and the Treasury client
  (JSON mapping, currency de-dup/sort, timeout / connection-refused / 4xx / 5xx → 503).
- **Integration** (full Spring context + in-memory H2 + WireMock): store + validation
  failures, successful conversion, USD→USD passthrough, idempotent replay / conflict,
  **two concurrent same-key POSTs persisting exactly one row** (real port, real
  threads), list + pagination, and every error status — `400` (bad input / missing or
  non-UUID param), `404` (unknown id / unknown path), `422` (no rate / idempotency
  conflict), `503` (Treasury unavailable).

Non-functional testing (performance/load) is intentionally out of scope per the brief.

## Concurrency & known limitations

Concurrency engineering was out of scope per the brief, so the code does not add locking or
threading machinery. Two data-integrity correctness fixes were made, however, because they
rely only on the database and are correct regardless of thread count — they are *not*
concurrency features:

- **Idempotency is integrity-safe via the primary key.** `store()` claims the key
  (`saveAndFlush` of `IdempotencyRecord`) **before** creating the transaction; the
  `idempotency_key` PK constraint is the dedup guarantee. A concurrent duplicate's insert
  fails fast → `409 DUPLICATE_REQUEST`, and because the key is claimed first the losing
  request never creates a transaction. This is insert-or-return, not check-then-write —
  no duplicate can be persisted. (Sequential replay still takes a fast pre-check path.)
  This is **verified, not just argued**: `IdempotencyConcurrencyIntegrationTest` fires
  two simultaneous same-key POSTs at a real running server and asserts exactly one row
  is persisted with no 5xx.
- **No DB connection is held across the Treasury call.** `retrieveConverted` is not
  `@Transactional`; the entity is loaded by a single short repository call and the outbound
  HTTP call runs outside any transaction, so pool lifetime is not tied to Treasury latency.
- **Append-only data model.** `PurchaseTransaction` is never updated, so there are no
  update races and no need for optimistic locking (`@Version`).
- **Embedded H2** allows multiple connections (`AUTO_SERVER=TRUE`) but is not built for
  high write concurrency — see *Production hardening* for the Postgres path.
- **Live Treasury call per retrieve** does not scale under load (thundering herd / rate
  limits); the batch-ingestion design below removes it from the request path.
- On Java 21, `spring.threads.virtual.enabled=true` would cheaply scale the blocking,
  I/O-bound request model — a one-line change deferred with the rest of concurrency.

## Production hardening (what would change to ship this at scale)

- **Rates via scheduled ingestion**, not a live call per request: a nightly job loads
  Treasury rates into our own table; lookups become a fast indexed query, resilient to
  Treasury downtime and fully auditable. The live call remains a backfill/fallback.
- Managed Postgres + schema migrations (Flyway) instead of embedded H2.
- Resilience4j circuit breaker + backoff around the outbound call; bulkheading.
- Containerized, horizontally autoscaled, stateless instances behind a load balancer.
- Observability: structured logs with correlation ids, RED metrics, distributed tracing,
  alerting on conversion-error and ingest-failure rates.
- Idempotency store with TTL eviction; CI/CD with tests + static analysis + dependency scans.

## Project layout

```
src/main/java/com/wex/currency/
  domain/      JPA entities (PurchaseTransaction, IdempotencyRecord)
  repository/  Spring Data repositories
  dto/         request/response records + error body
  client/      TreasuryRatesClient (RestClient, timeouts) + TreasuryRate
  service/     TransactionService, CurrencyConversionService, IdempotencyService, CurrencyCatalog
  controller/  REST API + UI page controller
  exception/   typed exceptions + global handler
  config/      API-key filter, OpenAPI metadata
src/main/resources/  application.yml, templates/index.html, static/app.js
src/test/java/...    unit + integration tests
SETUP.md             build / run / test instructions
docs/PLAN.md         the design/approach document
```
