# Event Ledger

A distributed event ledger system composed of two Spring Boot microservices that process financial transaction events with idempotency, out-of-order tolerance, distributed tracing, and resiliency.

## Architecture

```
Browser / Client ──→  Event Gateway API (port 8080)
                              │ REST (sync)
                              ▼
                      Account Service (port 8081)
```

### Event Gateway API (public-facing, port 8080)
Receives transaction events, validates input, enforces idempotency, persists event records in its own H2 in-memory database, and calls the Account Service to apply transactions.

### Account Service (internal, port 8081)
Manages account state — balances and transaction history — in its own independent H2 in-memory database. Only called by the Gateway.

**Each service has its own embedded H2 database and no shared state.**

## Prerequisites

- **Java 21** (required — the build will fail with a clear message if a different version is used)
- Maven 3.9+
- Docker & Docker Compose (for Docker-based startup)

> **Important:** This project requires exactly Java 21. If your default `java` points to a different version, set `JAVA_HOME` before running Maven:
> ```bash
> # macOS (Homebrew)
> export JAVA_HOME=$(/usr/libexec/java_home -v 21)
> # Then run tests or start the service
> mvn test
> ```

## How to Start

### Option 1: Docker Compose (recommended)

```bash
docker-compose up --build
```

Both services will start. The gateway waits for the account service to be healthy before starting.

### Option 2: Manual (two terminals)

**Terminal 1 — Account Service:**
```bash
cd account-service
mvn spring-boot:run
```

**Terminal 2 — Event Gateway:**
```bash
cd event-gateway
mvn spring-boot:run
```

## How to Run Tests

**Gateway tests:**
```bash
cd event-gateway
mvn test
```

**Account Service tests:**
```bash
cd account-service
mvn test
```

## API Quick Reference

### Event Gateway (port 8080)

```bash
# Submit an event
curl -X POST http://localhost:8080/events \
  -H "Content-Type: application/json" \
  -d '{
    "eventId": "evt-001",
    "accountId": "acct-123",
    "type": "CREDIT",
    "amount": 150.00,
    "currency": "USD",
    "eventTimestamp": "2026-05-15T14:02:11Z",
    "metadata": { "source": "mainframe-batch", "batchId": "B-9042" }
  }'

# Get a single event
curl http://localhost:8080/events/evt-001

# List events for an account (ordered by eventTimestamp)
curl "http://localhost:8080/events?account=acct-123"

# Health check
curl http://localhost:8080/health

# Custom metrics
curl http://localhost:8080/metrics
```

### Account Service (port 8081)

```bash
# Get account balance
curl http://localhost:8081/accounts/acct-123/balance

# Get account details + transaction history
curl http://localhost:8081/accounts/acct-123

# Health check
curl http://localhost:8081/health
```

## Resiliency Pattern: Circuit Breaker

The Event Gateway uses **Resilience4j Circuit Breaker** on all calls to the Account Service.

**Why Circuit Breaker?**  
The Account Service is a hard dependency for `POST /events`. Without a circuit breaker, a slow or down Account Service would cause cascading thread exhaustion in the Gateway. The circuit breaker detects repeated failures and short-circuits subsequent calls, returning a fast `503 Service Unavailable` to the client rather than hanging indefinitely.

**Configuration:**
- Sliding window: 5 calls
- Failure threshold: 50% failures → circuit opens
- Wait in open state: 10 seconds before trying half-open
- Timeout per call: 3 seconds

**Graceful degradation:**
- `POST /events` → returns `503` when circuit is open (clear error, no hang)
- `GET /events/{id}` and `GET /events?account=...` → **always work**, served from Gateway's local DB
- Balance queries → returns clear error that Account Service is unreachable

## Distributed Tracing

A `X-Trace-Id` header is generated (or forwarded if provided) at the Gateway for every incoming request and propagated to the Account Service via HTTP header. Both services log the trace ID in structured JSON format, enabling a traceable path across both services for any single client request.

## Design Decisions

- **H2 in-memory DB** per service: zero external dependencies, fast startup, clean per-run state
- **Save event before calling Account Service**: ensures the Gateway's record exists even if the Account Service call fails. GET endpoints always work.
- **Idempotency at both layers**: Gateway checks `eventId` before persisting; Account Service checks before applying — handles concurrent duplicate submissions
- **Out-of-order events**: events are stored with their original `eventTimestamp` and all listings sort by it, not by arrival time. Balance is computed as sum of CREDITs − sum of DEBITs regardless of arrival order.
