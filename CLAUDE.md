# CLAUDE.md — build & contribution guide for this repo

Authoritative rules for any AI/CLI-assisted work in this project. Keep changes inside the
scope below; do not add frameworks, queues, or auth servers.

## Stack
- Java 21, Spring Boot 3.3, Maven (wrapper committed — use `./mvnw`, no Maven install needed)
- Embedded file-based H2 (no external DB)
- Thymeleaf single-page UI + springdoc-openapi (Swagger UI)
- Tests: JUnit 5, Spring Boot Test / MockMvc, WireMock (stubs the Treasury API)

## Commands
- Test:  `./mvnw test`
- Run:   `./mvnw spring-boot:run`  → http://localhost:8080
- Build: `./mvnw clean package` then `java -jar target/*.jar`

## Hard constraints (do not violate)
- **Money is `BigDecimal` only — never `double`/`float`.**
- Stored amount: `setScale(2, RoundingMode.HALF_UP)` ("nearest cent"), applied once on store.
- Converted amount: `amount.multiply(rate)` at full rate precision, then `setScale(2, HALF_UP)`.
- **6-month rule (exact):** use the newest Treasury record with `record_date <= purchaseDate`;
  accept only if `record_date >= purchaseDate.minusMonths(6)` (inclusive); else 422.
- **Error-mode separation:** "no rate within 6 months" → 422 `CONVERSION_UNAVAILABLE`;
  Treasury unreachable/5xx/timeout → 503 `TREASURY_UNAVAILABLE`. Never conflate the two.
- Validation: description not blank and ≤ 50 chars; date valid ISO `yyyy-MM-dd`; amount > 0.
- Idempotency: optional `Idempotency-Key` header on POST; same key + same body → original
  record; same key + different body → 422 `IDEMPOTENCY_CONFLICT`.

## Testing expectation
Every requirement and every unhappy path has an automated test. `git clone && ./mvnw test`
must pass on a machine with only a JDK installed.

## Commenting policy (reviewer-focused)
- Javadoc on every public controller/service/client method: contract + the requirement it
  satisfies + any encoded assumption.
- Inline comments only on non-obvious logic (6-month boundary, rounding, idempotency hash,
  422-vs-503). Explain the *why*. No redundant noise.
