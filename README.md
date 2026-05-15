# Transaction Rate Engine

A self-contained Spring Boot service that:

1. **Stores** a purchase transaction (description, date, USD amount) and assigns a unique id.
2. **Retrieves** a stored transaction converted into a target currency using the
   [U.S. Treasury Reporting Rates of Exchange API](https://fiscaldata.treasury.gov/datasets/treasury-reporting-rates-exchange/treasury-reporting-rates-of-exchange),
   using the most recent rate **on or before** the purchase date and **within the prior 6 months**.

Built to be run as if in production: layered design, full automated functional tests, embedded
database, and no external infrastructure to install.

## Prerequisites

The **only** requirement is **JDK 21**. Maven, the database, and the web server are all
bundled (Maven wrapper + embedded H2 + embedded Tomcat) — nothing else to install. Internet
access to `api.fiscaldata.treasury.gov` is needed only for *live* currency conversion; the
test suite stubs it and runs fully offline.

### macOS

```bash
# 1. Install JDK 21 (Homebrew). Skip if `java -version` already shows 21.
brew install openjdk@21

# 2. Point this shell at JDK 21 (Homebrew's JDK is keg-only, so this is required).
export JAVA_HOME=$(/usr/libexec/java_home -v 21)

# 3. (optional) make it permanent for future shells
echo 'export JAVA_HOME=$(/usr/libexec/java_home -v 21)' >> ~/.zshrc

# 4. Verify — must print 21.x
java -version
```

If `/usr/libexec/java_home -v 21` fails, the JDK isn't installed; install it (step 1) or
use `export JAVA_HOME=/opt/homebrew/opt/openjdk@21` (Apple Silicon) /
`/usr/local/opt/openjdk@21` (Intel).

### Windows

```powershell
# 1. Install JDK 21 (winget). Or download Temurin 21 from https://adoptium.net
winget install --id EclipseAdoptium.Temurin.21.JDK

# 2. Set JAVA_HOME for the current PowerShell session
$env:JAVA_HOME = (Get-ChildItem 'C:\Program Files\Eclipse Adoptium' -Directory |
  Where-Object Name -like 'jdk-21*' | Select-Object -First 1).FullName

# 3. (optional) make it permanent for your user
setx JAVA_HOME "$env:JAVA_HOME"

# 4. Verify — must print 21.x
java -version
```

Use `mvnw.cmd` instead of `./mvnw` in all commands below (e.g. `mvnw.cmd test`).

## Get the code

```bash
git clone https://github.com/dhruma/transaction-rate-engine.git
cd transaction-rate-engine
```

## Run it

```bash
./mvnw spring-boot:run
```

Then open:

| URL | What |
|---|---|
| http://localhost:8080/ | UI — store a purchase, retrieve it converted |
| http://localhost:8080/swagger-ui.html | Interactive API documentation |
| http://localhost:8080/h2-console | Database console — see connection settings below |

### Connecting the H2 console

The login page defaults to `jdbc:h2:~/test`, which is **not** this app's database. Replace
the fields with:

| Field | Value |
|---|---|
| Driver Class | `org.h2.Driver` |
| JDBC URL | `jdbc:h2:file:./data/wex;AUTO_SERVER=TRUE` |
| User Name | `sa` |
| Password | *(leave blank)* |

Notes:

- The `data/` directory is created the first time the app starts, so start the app before
  connecting. Store a transaction first or the tables will be empty.
- `;AUTO_SERVER=TRUE` is required so the console can attach while the app holds the file;
  without it you get a "database may be already in use" lock error.
- The path is relative to the directory the app was started from. If the console reports the
  database is not found, use the absolute path instead, e.g.
  `jdbc:h2:file:/absolute/path/to/transaction-rate-engine/data/wex;AUTO_SERVER=TRUE`.
- Running a query: clicking a table name in the left tree **appends** that name into the SQL
  editor (built-in H2 behaviour, not configurable). Click **Clear** (or select-all and
  delete) before composing the next query, then type e.g.
  `SELECT * FROM PURCHASE_TRANSACTION;` and click **Run**.
- `IDEMPOTENCY_RECORD` only has rows when a `POST /api/transactions` was sent with an
  `Idempotency-Key` header (via curl or Swagger — not surfaced in the UI).

Run the tests:

```bash
./mvnw test
```

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
  "originalAmountUsd": 250.00, "targetCurrency": "Canada-Dollar",
  "exchangeRate": 1.357, "exchangeRateDate": "2024-03-31", "convertedAmount": 339.25 }
```

### List stored transactions — `GET /api/transactions`

Returns the most recent stored transactions, newest first (id, description, date, amount).
Not a brief requirement — it backs the UI's click-to-select list so retrieval doesn't
require copying ids by hand. Accepts an optional `?limit=` (default 20, capped at 100) so
the response stays bounded as data grows. The `X-Total-Count` response header reports the
**full number of stored transactions** — the cap is a view limit, never a storage limit.
The UI shows "Showing N of <total>" so it is always clear that every transaction is
persisted; fetch more with a larger `?limit=`.

### List currencies — `GET /api/currencies`

Distinct currency identifiers from the Treasury API; used by the UI dropdown.

## Running the packaged jar (optional)

Reviewing this project does not require deploying it anywhere — `./mvnw spring-boot:run`
above is all that is needed to exercise every requirement. This section is only here to
show that the build produces a single self-contained, deployable artifact (the form in
which a Spring Boot service actually ships):

```bash
./mvnw clean package
java -jar target/wex-purchase-currency-service-1.0.0.jar
```

This runs the exact same application (embedded web server, embedded H2, UI, API) with only
a JDK on the machine — no Maven, no separate server. It is an alternative to
`spring-boot:run`, not an extra step a reviewer needs to perform.

## Idempotency

`POST /api/transactions` accepts an optional `Idempotency-Key` header.

- Same key + identical body → the **original** transaction is returned (no duplicate created).
- Same key + different body → `422 IDEMPOTENCY_CONFLICT` (returning the original would
  misrepresent the new request and hide a client bug).

Idempotency is an API-level concern and is exercised via the `Idempotency-Key` request
header (curl or Swagger UI). It is deliberately not surfaced in the web UI — most users
have no reason to set a key by hand, and an extra field there caused more confusion than it
was worth. The behaviour is fully covered by automated tests.

## Error model

A consistent JSON error body is returned for every failure. Status codes deliberately
separate two distinct failure families:

| Status | When |
|---|---|
| `400` | Invalid input (description > 50, non-positive amount, bad date, malformed JSON) |
| `404` | Transaction id not found |
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

`./mvnw test` runs 35 functional tests (no network required — the Treasury API is stubbed
with WireMock):

- **Unit**: conversion + rounding, the inclusive 6-month boundary (exact / one-day-older /
  no-rate), idempotency replay vs. conflict, store-amount rounding, and the Treasury client
  (JSON mapping, currency de-dup/sort, timeout / connection-refused / 4xx / 5xx → 503).
- **Integration** (full Spring context + in-memory H2 + WireMock): store + validation
  failures, successful conversion, idempotent replay / conflict, list + pagination, and
  every error status — `400` (bad input / missing or non-UUID param), `404` (unknown id /
  unknown path), `422` (no rate / idempotency conflict), `503` (Treasury unavailable).

Non-functional testing (performance/load) is intentionally out of scope per the brief.

## Concurrency & known limitations

Concurrency was explicitly out of scope per the brief, so the code takes the simple,
single-threaded-correct path. The implementation is deliberately scoped, but the trade-offs
are understood:

- **Idempotency claim race (the one real hazard).** `TransactionService.store()` does
  *find-key → create transaction → register key*. Two simultaneous requests with the same
  `Idempotency-Key` could both pass the "not found" check and each create a transaction;
  the second `IdempotencyRecord` insert then collides on its primary key. The correct fix
  is to let the database arbitrate: claim the key first (the `idempotency_key` PK already
  guarantees uniqueness), and on `DataIntegrityViolationException` treat it as "another
  request won" — roll back and replay the original transaction. This is a small, contained
  change; it is documented rather than implemented because the brief waived concurrency and
  scope discipline is part of the exercise.
- **Append-only data model.** `PurchaseTransaction` is never updated, so there are no
  update races and no need for optimistic locking (`@Version`); the idempotency claim above
  is the only true write-contention point.
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
  service/     TransactionService, CurrencyConversionService, IdempotencyService
  controller/  REST API + UI page controller
  exception/   typed exceptions + global handler
  config/      API-key filter, OpenAPI metadata
src/main/resources/  application.yml, templates/index.html, static/app.js
src/test/java/...    unit + integration tests
docs/PLAN.md         the design/approach document
```
