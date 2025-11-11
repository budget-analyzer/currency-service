# Currency Service - Exchange Rate Management

## Service Purpose

Manages currencies and exchange rates for the Budget Analyzer application with automated import from external data providers.

**Domain**: Currency and exchange rate management
**Responsibilities**:
- CRUD operations for currency series
- Exchange rate queries with date range filtering
- Automated import from FRED (Federal Reserve Economic Data)
- Scheduled background imports with distributed coordination
- High-performance distributed caching

## Spring Boot Patterns

**This service follows standard Budget Analyzer Spring Boot conventions.**

See [@service-common/CLAUDE.md](https://github.com/budget-analyzer/service-common/blob/main/CLAUDE.md) and [@service-common/docs/](https://github.com/budget-analyzer/service-common/tree/main/docs) for:
- Architecture layers (Controller → Service → Repository)
- Naming conventions (`*Controller`, `*Service`, `*ServiceImpl`, `*Repository`)
- Testing patterns (JUnit 5, TestContainers)
- Error handling (exception hierarchy, `BusinessException` vs `InvalidRequestException`)
- Logging conventions (SLF4J structured logging)
- Dependency management (inherit from service-common parent POM)
- Code quality standards (Spotless, Checkstyle, var usage, Javadoc)
- Validation strategy (Bean Validation vs business validation)

## Advanced Patterns Used

**This service implements ALL advanced patterns documented in service-common.**

See [@service-common/docs/advanced-patterns.md](https://github.com/budget-analyzer/service-common/blob/main/docs/advanced-patterns.md) for detailed documentation on:
- **Provider Abstraction Pattern**: FRED API integration via `ExchangeRateProvider` interface
- **Event-Driven Messaging**: Spring Modulith transactional outbox for guaranteed message delivery
- **Redis Distributed Caching**: High-performance caching for exchange rate queries
- **ShedLock Distributed Locking**: Coordinated scheduled imports across multiple pods
- **Flyway Migrations**: Version-controlled database schema evolution

These patterns are production-proven and reusable across services. Currency service serves as the reference implementation.

## Service-Specific Patterns

### FRED API Integration

**External provider:** Federal Reserve Economic Data (FRED)

**Discovery:**
```bash
# Find FRED client
cat src/main/java/org/budgetanalyzer/currency/client/fred/FredClient.java

# View provider interface
cat src/main/java/org/budgetanalyzer/currency/service/provider/ExchangeRateProvider.java

# Check configuration
cat src/main/resources/application.yml | grep -A 5 "fred"
```

**Key Configuration:**
```yaml
currency-service:
  fred:
    base-url: https://api.stlouisfed.org/fred
    api-key: ${FRED_API_KEY}
```

**Important:** Provider-specific logic is encapsulated in `FredExchangeRateProvider`. Service layer only depends on `ExchangeRateProvider` interface, allowing future providers (ECB, Bloomberg) without service changes.

See [@service-common/docs/advanced-patterns.md#provider-abstraction-pattern](https://github.com/budget-analyzer/service-common/blob/main/docs/advanced-patterns.md#provider-abstraction-pattern)

### Scheduled Exchange Rate Import

**Schedule:** Daily at 11 PM UTC

**Coordination:** ShedLock ensures only one pod executes import in multi-instance deployment

**Discovery:**
```bash
# Find scheduler
cat src/main/java/org/budgetanalyzer/currency/scheduler/ExchangeRateImportScheduler.java

# Check lock configuration
cat src/main/resources/application.yml | grep -A 5 "shedlock"
```

**Lock Configuration:**
- `lockAtMostFor: 15m` - Safety timeout (import takes ~30 seconds)
- `lockAtLeastFor: 1m` - Prevents rapid re-execution

See [@service-common/docs/advanced-patterns.md#distributed-locking-with-shedlock](https://github.com/budget-analyzer/service-common/blob/main/docs/advanced-patterns.md#distributed-locking-with-shedlock)

### Exchange Rate Caching

**Cache:** Redis distributed cache with 1-hour TTL

**Performance:**
- Cache hit: 1-3ms
- Cache miss: 50-200ms (database query)
- Expected hit rate: 80-95%

**Discovery:**
```bash
# Find cache configuration
cat src/main/java/org/budgetanalyzer/currency/config/CacheConfig.java

# View cached service methods
grep -r "@Cacheable\|@CacheEvict" src/main/java/*/service/
```

**Cache Strategy:**
- `@Cacheable` on `getExchangeRates()` - Caches query results
- `@CacheEvict(allEntries = true)` on import - Clears all cache after new data imported
- Key format: `{targetCurrency}:{startDate}:{endDate}`

See [@service-common/docs/advanced-patterns.md#redis-distributed-caching](https://github.com/budget-analyzer/service-common/blob/main/docs/advanced-patterns.md#redis-distributed-caching)

### Event-Driven Architecture

**Pattern:** Transactional outbox ensures guaranteed message delivery

**Event Flow:**
1. Service creates currency series
2. Domain event persisted in database (same transaction)
3. Event listener publishes to RabbitMQ asynchronously
4. Consumer triggers exchange rate import

**Discovery:**
```bash
# Find domain events
cat src/main/java/org/budgetanalyzer/currency/domain/event/CurrencyCreatedEvent.java

# Find event listeners
cat src/main/java/org/budgetanalyzer/currency/messaging/listener/MessagingEventListener.java

# Find message consumers
cat src/main/java/org/budgetanalyzer/currency/messaging/consumer/ExchangeRateImportConsumer.java
```

**Benefits:**
- 100% guaranteed delivery (event survives crashes, network failures)
- Async HTTP responses (fast API response times)
- Automatic retries with Spring Modulith

See [@service-common/docs/advanced-patterns.md#event-driven-messaging-with-transactional-outbox](https://github.com/budget-analyzer/service-common/blob/main/docs/advanced-patterns.md#event-driven-messaging-with-transactional-outbox)

### Domain Model

See [docs/domain-model.md](docs/domain-model.md) for detailed entity relationships and business rules.

**Key Concepts:**
- **CurrencySeries**: Represents exchange rate time series from external providers (ISO 4217 codes)
- **ExchangeRate**: Individual rate observations for a series (date + rate value)

**Discovery:**
```bash
# Find all entities
find src/main/java -type f -path "*/domain/*.java" | grep -v event

# View entity structure
cat src/main/java/org/budgetanalyzer/currency/domain/CurrencySeries.java
cat src/main/java/org/budgetanalyzer/currency/domain/ExchangeRate.java
```

### Package Structure

**Standard Spring Boot layered architecture** - See [@service-common/CLAUDE.md](https://github.com/budget-analyzer/service-common/blob/main/CLAUDE.md)

**Service-specific packages:**
- `client/fred/` - FRED API integration
- `scheduler/` - Background import jobs
- `messaging/` - Event-driven messaging (listener, consumer, publisher)
- `service/provider/` - Provider abstraction interface

**Discovery:**
```bash
# View full package structure
tree src/main/java/org/budgetanalyzer/currency -L 2

# Or without tree command
find src/main/java/org/budgetanalyzer/currency -type d | sort
```

**Critical Architecture Rules:**
- Controllers NEVER import repositories (use services)
- Consumers NEVER import repositories (delegate to services)
- Service layer NEVER imports message publishers (publishes domain events instead)
- Service layer NEVER references FRED directly (uses `ExchangeRateProvider` interface)

## API Documentation

**OpenAPI Specification:** Run service and access Swagger UI:
```bash
./gradlew bootRun
# Visit: http://localhost:8084/swagger-ui.html
```

**Key Endpoints:**
- Currency series CRUD: `/v1/currencies/**`
- Exchange rates: `/v1/exchange-rates/**`

**Gateway Access:**
- Internal: `http://localhost:8084/v1/currencies`
- External (via NGINX): `http://localhost:8080/api/v1/currencies`

## Running Locally

**Prerequisites:**
- JDK 24
- PostgreSQL 15+
- Redis 7+
- Gradle 8.11+
- FRED API key (sign up at https://fred.stlouisfed.org/docs/api/api_key.html)

**Start Infrastructure:**
```bash
cd ../orchestration
docker compose up
```

**Set Environment Variable:**
```bash
export FRED_API_KEY=your_api_key_here
```

**Run Service:**
```bash
./gradlew bootRun
```

**Access:**
- Service: http://localhost:8084
- Swagger UI: http://localhost:8084/swagger-ui.html
- Health Check: http://localhost:8084/actuator/health

## Discovery Commands

```bash
# Find all REST endpoints
grep -r "@GetMapping\|@PostMapping\|@PutMapping\|@DeleteMapping" src/main/java/*/api/

# Find provider implementations
find src/main/java -type f -name "*Provider.java"

# Find domain events
find src/main/java -type f -path "*/domain/event/*.java"

# Check scheduled tasks
grep -r "@Scheduled" src/main/java/

# View application configuration
cat src/main/resources/application.yml
```

## Build and Test

**Format code:**
```bash
./gradlew clean spotlessApply
```

**Build and test:**
```bash
./gradlew clean build
```

The build includes:
- Spotless code formatting checks
- Checkstyle rule enforcement
- All unit and integration tests
- JAR file creation

**Troubleshooting:**

If encountering "cannot resolve" errors for service-common classes:
```bash
cd ../service-common
./gradlew clean build publishToMavenLocal
cd ../currency-service
./gradlew clean build
```

## Testing

See [@service-common/docs/testing-patterns.md](https://github.com/budget-analyzer/service-common/blob/main/docs/testing-patterns.md) for testing conventions.

**Current state**: Limited coverage, opportunity for improvement (provider abstraction, caching, messaging, scheduling)

## Deployment

**Environment variables**: Standard Spring Boot + PostgreSQL + Redis + `FRED_API_KEY` (required)

**Health checks**: `/actuator/health/readiness`, `/actuator/health/liveness`

**Discovery:**
```bash
# View all env vars
cat src/main/resources/application.yml | grep '\${' | sort -u
```

## Notes for Claude Code

**General guidance**: See [@service-common/CLAUDE.md](https://github.com/budget-analyzer/service-common/blob/main/CLAUDE.md) for code quality standards and build commands.

**Service-specific reminders**:
- Service layer uses `ExchangeRateProvider` interface, NEVER references FRED directly
- Consumers delegate to services, NEVER import repositories
- Services publish domain events, listeners bridge to external messages
- Use `@Cacheable` for queries, `@CacheEvict(allEntries=true)` after imports
- Use `@SchedulerLock` for scheduled tasks (multi-pod coordination)
