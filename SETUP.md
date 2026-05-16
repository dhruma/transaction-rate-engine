# Setup & Run

Everything needed to build, test, and run the Transaction Rate Engine. For what the service
does and how it works (API, design, testing strategy, limitations), see [README.md](README.md).

## Prerequisites

The **only** requirement is **JDK 21**. Maven, the database, and the web server are all
bundled (Maven wrapper + embedded H2 + embedded Tomcat) — nothing else to install. Internet
access to `api.fiscaldata.treasury.gov` is needed only for *live* currency conversion; the
test suite stubs it and runs fully offline.

### macOS (Apple Silicon — M1/M2/M3, the default)

```bash
# 1. Install JDK 21 (Homebrew). Skip if `java -version` already shows 21.
brew install openjdk@21

# 2. Point this shell at JDK 21 (Homebrew's JDK is keg-only, so this is required).
export JAVA_HOME=/opt/homebrew/opt/openjdk@21

# 3. (optional) make it permanent for future shells
echo 'export JAVA_HOME=/opt/homebrew/opt/openjdk@21' >> ~/.zshrc

# 4. Verify — must print 21.x
java -version
```

**Intel Macs:** Homebrew installs under `/usr/local` instead, so use
`export JAVA_HOME=/usr/local/opt/openjdk@21`. The architecture-agnostic
`export JAVA_HOME=$(/usr/libexec/java_home -v 21)` also works on either Mac once a
JDK 21 is installed.

### Windows

```powershell
# 1. Install JDK 21 (winget). Or download Temurin 21 from https://adoptium.net
winget install --id EclipseAdoptium.Temurin.21.JDK
```

**After installing, open a NEW PowerShell window** before continuing — `winget` does not
refresh the current session's environment, so `java` will be "not recognized" until you
start a fresh terminal. Then, in the new window:

```powershell
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

## Run the tests

```bash
./mvnw test
```

43 functional tests (unit + WireMock-stubbed integration), no network required.

## Connecting the H2 console

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
