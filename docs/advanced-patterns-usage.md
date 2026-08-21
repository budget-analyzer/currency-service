# Advanced Patterns Usage Guide

This service implements ALL advanced patterns from service-common. This guide provides implementation details and usage examples specific to the currency service.

For complete pattern documentation, see [service-common/docs/advanced-patterns.md](../../service-common/docs/advanced-patterns.md).

## Provider Abstraction Pattern

### Overview

Service layer depends on `ExchangeRateProvider` interface, never on concrete FRED implementation. This decouples the service from the external data provider and allows switching providers without service layer changes.

### Architecture

```
CurrencyService
    ↓ (depends on interface)
ExchangeRateProvider (interface)
    ↑ (implements)
FredExchangeRateProvider
    ↓ (uses)
FredClient (HTTP communication)
```

### Dependency Rules

✅ **Allowed:**
- `CurrencyService` imports `ExchangeRateProvider` interface
- `FredExchangeRateProvider` implements `ExchangeRateProvider`
- `FredClient` handles HTTP calls to FRED API

❌ **Forbidden:**
- `CurrencyService` importing `FredExchangeRateProvider` directly
- `CurrencyService` importing anything from `client.fred` package
- Service layer code mentioning "FRED" or any provider name

### Implementation Example

**Service Layer (Good):**
```java
@Service
public class CurrencyServiceImpl implements CurrencyService {
    private final ExchangeRateProvider exchangeRateProvider;  // Interface only

    public void importExchangeRates(String currencyCode) {
        List<ExchangeRate> rates = exchangeRateProvider.fetchRates(currencyCode);
        // Process rates...
    }
}
```

**Service Layer (Bad):**
```java
@Service
public class CurrencyServiceImpl implements CurrencyService {
    private final FredExchangeRateProvider fredProvider;  // ❌ Concrete implementation

    public void importExchangeRates(String currencyCode) {
        List<ExchangeRate> rates = fredProvider.fetchRates(currencyCode);  // ❌ Tight coupling
        // Process rates...
    }
}
```

### Adding a New Provider

To add ECB, Bloomberg, or other provider:

1. **Create provider implementation:**
```java
@Component
@Profile("ecb")  // Activate with spring.profiles.active=ecb
public class EcbExchangeRateProvider implements ExchangeRateProvider {
    private final EcbClient ecbClient;

    @Override
    public List<ExchangeRate> fetchRates(String currencyCode) {
        // ECB-specific implementation
    }
}
```

2. **Create client:**
```java
@Component
public class EcbClient {
    private final RestTemplate restTemplate;

    public EcbResponse fetchExchangeRates(String currency) {
        // HTTP calls to ECB API
    }
}
```

3. **Add configuration:**
```yaml
currency-service:
  ecb:
    base-url: https://api.ecb.europa.eu
    api-key: ${ECB_API_KEY}
```

4. **No service layer changes needed** - service already uses interface

### Discovery Commands

```bash
# View provider interface
cat src/main/java/org/budgetanalyzer/currency/service/provider/ExchangeRateProvider.java

# View FRED implementation
cat src/main/java/org/budgetanalyzer/currency/service/provider/FredExchangeRateProvider.java

# View FRED client
cat src/main/java/org/budgetanalyzer/currency/client/fred/FredClient.java

# Verify service uses interface only
grep -r "ExchangeRateProvider" src/main/java/*/service/impl/
```

## ShedLock Distributed Locking

### Overview

Daily scheduled import runs exactly once across all pods using database-backed distributed lock. Prevents duplicate imports in multi-pod Kubernetes deployments.

### Configuration

**Schedule:** Daily at 11 PM UTC

**Lock Parameters:**
- `lockAtMostFor: 15m` - Safety timeout (task takes ~30 seconds)
- `lockAtLeastFor: 1m` - Prevents rapid re-execution

**Lock Storage:** PostgreSQL (table: `shedlock`)

### Implementation Example

```java
@Component
public class ExchangeRateImportScheduler {

    @Scheduled(cron = "0 0 23 * * *")  // 11 PM UTC daily
    @SchedulerLock(
        name = "importExchangeRates",
        lockAtMostFor = "15m",
        lockAtLeastFor = "1m"
    )
    public void importDailyExchangeRates() {
        currencyService.importAllCurrencyRates();
    }
}
```

### How It Works

1. Scheduler triggers on all pods at 11 PM UTC
2. First pod to execute acquires lock in database
3. Other pods see lock exists and skip execution
4. Lock auto-releases after task completes
5. If pod crashes, lock expires after 15 minutes (safety timeout)

### Lock Duration Guidelines

**lockAtMostFor** (maximum lock duration):
- Should be longer than expected task duration
- Safety mechanism for crashed pods
- Current: 15m for 30-second task (30x buffer)

**lockAtLeastFor** (minimum lock duration):
- Prevents rapid re-execution if task completes early
- Should be less than schedule interval
- Current: 1m minimum between executions

### Multi-Pod Behavior

**Expected (one pod acquires lock):**
```
Pod 1: Lock acquired, importing rates...
Pod 2: Lock already held, skipping
Pod 3: Lock already held, skipping
Pod 1: Import complete, lock released
```

**Crash Recovery:**
```
Pod 1: Lock acquired, importing rates...
Pod 1: [CRASHES]
[15 minutes later - lock expires]
Next scheduled run:
Pod 2: Lock expired, acquiring and importing...
```

### Adding New Scheduled Tasks

1. **Create scheduled method:**
```java
@Scheduled(cron = "0 0 2 * * *")  // 2 AM UTC daily
@SchedulerLock(
    name = "cleanupExpiredData",  // Must be unique
    lockAtMostFor = "10m",
    lockAtLeastFor = "30s"
)
public void cleanupExpiredData() {
    // Task implementation
}
```

2. **Choose lock duration:**
- `lockAtMostFor` = expected duration × 10-30
- `lockAtLeastFor` = 10-20% of schedule interval

3. **Test multi-pod:**
```bash
# Start multiple instances
./gradlew bootRun --args='--server.port=8084'
./gradlew bootRun --args='--server.port=8085'

# Verify only one executes (check logs)
```

### Discovery Commands

```bash
# Find scheduled tasks
grep -r "@Scheduled" src/main/java/*/scheduler/

# View lock configuration
cat src/main/resources/application.yml | grep -A 5 "shedlock"

# Check lock table
psql -d budget_analyzer -c "SELECT * FROM shedlock;"
```

## Redis Distributed Caching

### Overview

Exchange rate queries cached with 1-hour TTL. Dramatically improves response times: cache hit 1-3ms vs. cache miss 50-200ms.

### Performance Impact

- **Cache Hit:** 1-3ms (from Redis)
- **Cache Miss:** 50-200ms (PostgreSQL query)
- **Expected Hit Rate:** 80-95% for typical usage
- **Speedup:** 50-200x faster on cache hit

### Implementation Example

**Query Method (Cached):**
```java
@Service
public class CurrencyServiceImpl implements CurrencyService {

    @Cacheable(value = "exchangeRates", key = "#targetCurrency + ':' + #startDate + ':' + #endDate")
    public List<ExchangeRate> getExchangeRates(
        String targetCurrency,
        LocalDate startDate,
        LocalDate endDate
    ) {
        return exchangeRateRepository.findByCurrencySeriesAndDateRange(
            targetCurrency, startDate, endDate
        );
    }
}
```

**Update Method (Cache Eviction):**
```java
@CacheEvict(value = "exchangeRates", allEntries = true)
public void importExchangeRates(String currencyCode) {
    // Import new rates from provider
    // Cache automatically cleared after method completes
}
```

### Cache Key Strategy

**Format:** `{targetCurrency}:{startDate}:{endDate}`

**Examples:**
- `USD:2024-01-01:2024-12-31` - Full year USD rates
- `EUR:2024-11-01:2024-11-15` - Two weeks EUR rates
- `THB:2024-11-15:2024-11-15` - Single day THB rate

**Why this format:**
- Natural key for rate queries
- Avoids cache collision across currencies
- Supports range queries efficiently

### Cache Configuration

```yaml
spring:
  data:
    redis:
      host: ${REDIS_HOST:localhost}
      port: ${REDIS_PORT:6379}
  cache:
    type: redis
    redis:
      time-to-live: 3600000  # 1 hour in milliseconds
```

### When to Cache

✅ **Good candidates:**
- Frequently accessed data (exchange rates queried often)
- Slow to compute/fetch (database queries)
- Changes infrequently (rates imported once daily)
- Same parameters used repeatedly (date ranges)

❌ **Bad candidates:**
- Data changes frequently (real-time data)
- Unique queries (never repeated)
- Large result sets (memory concerns)
- Security-sensitive data (risk of stale permissions)

### Cache Eviction Strategies

**1. Evict all entries (current approach):**
```java
@CacheEvict(value = "exchangeRates", allEntries = true)
public void importExchangeRates(String currencyCode) {
    // Clears entire cache
}
```

**When to use:** Data changes affect many cache entries (our case: new imports invalidate all ranges)

**2. Evict specific key:**
```java
@CacheEvict(value = "exchangeRates", key = "#currencyCode + ':*'")
public void updateCurrency(String currencyCode) {
    // Clears only this currency (requires key pattern matching)
}
```

**When to use:** Targeted updates affecting specific entries only

**3. Time-based expiration (automatic):**
- TTL: 1 hour configured in application.yml
- Redis automatically removes expired entries
- No code changes needed

### Monitoring Cache Performance

**Enable cache statistics:**
```yaml
spring:
  cache:
    cache-names: exchangeRates
    redis:
      enable-statistics: true
```

**Check cache metrics:**
```bash
# Via actuator (if enabled)
curl http://localhost:8084/actuator/metrics/cache.gets?tag=name:exchangeRates
curl http://localhost:8084/actuator/metrics/cache.evictions?tag=name:exchangeRates
```

**Expected metrics:**
- Hit rate: 80-95%
- Miss rate: 5-20%
- Evictions: 1x daily (after import)

### Discovery Commands

```bash
# Find cached methods
grep -r "@Cacheable" src/main/java/

# Find cache eviction points
grep -r "@CacheEvict" src/main/java/

# View cache configuration
cat src/main/resources/application.yml | grep -A 10 "redis"

# Check Redis connection
redis-cli -h localhost -p 6379 ping
```

## Event-Driven Messaging

### Overview

Transactional outbox ensures 100% guaranteed message delivery. Events persisted in database atomically with business data, then published to RabbitMQ asynchronously.

### Why Transactional Outbox

**Problem without outbox:**
```java
// ❌ Unreliable - can lose messages
@Transactional
public void createCurrency(Currency currency) {
    currencyRepository.save(currency);  // Succeeds
    rabbitTemplate.send(event);         // Fails - message lost!
}
```

**Solution with outbox:**
```java
// ✅ Reliable - guaranteed delivery
@Transactional
public void createCurrency(Currency currency) {
    currencyRepository.save(currency);
    applicationEventPublisher.publishEvent(new CurrencyCreatedEvent(currency));
    // Both persisted in same transaction
    // Message delivered asynchronously even if we crash
}
```

### Implementation Example

**1. Define domain event:**
```java
public class CurrencyCreatedEvent {
    private final String currencyCode;
    private final String currencyName;
    // Constructor, getters
}
```

**2. Publish event from service:**
```java
@Service
public class CurrencyServiceImpl implements CurrencyService {
    private final ApplicationEventPublisher eventPublisher;

    @Transactional
    public CurrencyCreated createCurrency(CreateCurrencyRequest request) {
        var currency = currencyRepository.save(newCurrency);

        // Event persisted in event_publication table (same transaction)
        eventPublisher.publishEvent(
            new CurrencyCreatedEvent(currency.getCode(), currency.getName())
        );

        return currency;
    }
}
```

**3. Bridge to external message broker:**
```java
@Component
public class CurrencyEventListener {

    private final StreamBridge streamBridge;

    @ApplicationModuleListener
    void on(CurrencyCreatedEvent event) {
        // Convert domain event to an import request message
        var message = new ExchangeRateImportRequestedMessage(
            event.getCurrencySeriesId(),
            event.getCurrencyCode(),
            event.getCorrelationId()
        );

        // Publish to RabbitMQ through Spring Cloud Stream
        streamBridge.send("exchangeRateImportRequested-out-0", message);
    }
}
```

**4. Consumer:**
```java
@Configuration
public class ExchangeRateImportConsumer {

    @Bean
    public Consumer<ExchangeRateImportRequestedMessage> importExchangeRates() {
        return message ->
            exchangeRateImportService.importExchangeRatesForSeries(message.currencySeriesId());
    }
}
```

### Event Flow

1. **Service publishes domain event** (in transaction)
2. **Spring Modulith persists to `event_publication` table** (same transaction)
3. **Transaction commits** (both business data and event saved)
4. **Spring Modulith polls `event_publication` table** (async)
5. **Listener receives event** and publishes to RabbitMQ
6. **RabbitMQ delivers** to subscribed services
7. **Event marked complete** in `event_publication` table

### Operational Logging

Currency enablement and import-trigger messaging emit info-level breadcrumbs across the flow:

1. `CurrencyService` logs when an update changes a currency from disabled to enabled and is about
   to publish the `CurrencyUpdatedEvent`.
2. `ExchangeRateImportMessagePublisher` logs every outbound
   `exchange-rate.import.requested` message with the binding, currency series ID, and currency
   code.
3. `ExchangeRateImportConsumer` logs the matching inbound consumption with the currency series ID
   and currency code before running the import.

### Guaranteed Delivery

**Scenario 1: Normal operation**
```
1. Save currency + persist event (transaction)
2. Commit transaction
3. Publish to RabbitMQ
4. Mark event complete
✅ Message delivered
```

**Scenario 2: Crash before RabbitMQ publish**
```
1. Save currency + persist event (transaction)
2. Commit transaction
3. [APPLICATION CRASHES]
4. [RESTART]
5. Spring Modulith finds unpublished events
6. Publishes to RabbitMQ
7. Mark event complete
✅ Message delivered after restart
```

**Scenario 3: RabbitMQ publish fails**
```
1. Save currency + persist event (transaction)
2. Commit transaction
3. Publish to RabbitMQ → FAILS
4. Event remains in event_publication
5. Spring Modulith retries automatically
6. Eventually succeeds
✅ Message delivered after retry
```

### RabbitMQ Configuration

**Spring Cloud Stream Bindings:**
```yaml
spring:
  cloud:
    stream:
      bindings:
        exchangeRateImportRequested-out-0:
          destination: exchange-rate.import.requested
        importExchangeRates-in-0:
          destination: exchange-rate.import.requested
          group: exchange-rate-import-service
```

The external message contract is `ExchangeRateImportRequestedMessage`. `CurrencyService` only
publishes import-triggering domain events for enabled creates and disabled-to-enabled updates, so
the listener translates every received domain event into an external import request.

### Adding New Events

1. **Define event class:**
```java
public class CurrencyUpdatedEvent {
    private final String currencyCode;
    private final String newName;
    // Constructor, getters
}
```

2. **Publish from service:**
```java
@Transactional
public void updateCurrency(String code, String newName) {
    var currency = currencyRepository.findById(code)
        .orElseThrow(() -> new ResourceNotFoundException("Currency not found"));

    currency.setName(newName);
    currencyRepository.save(currency);

    eventPublisher.publishEvent(new CurrencyUpdatedEvent(code, newName));
}
```

3. **Add listener:**
```java
@ApplicationModuleListener
void on(CurrencyUpdatedEvent event) {
    var message = new CurrencyMessage(event.getCurrencyCode(), event.getNewName());
    streamBridge.send("currencyUpdated-out-0", message);
}
```

4. **Consumers automatically receive** (if bound to the matching destination)

### Discovery Commands

```bash
# Find domain events
find src/main/java -type f -path "*/domain/event/*.java"

# Find event publishers
grep -r "publishEvent" src/main/java/*/service/

# Find event listeners
grep -r "@ApplicationModuleListener" src/main/java/*/messaging/

# Find Spring Cloud Stream consumers
grep -r "Consumer<" src/main/java/*/messaging/

# View RabbitMQ configuration
cat src/main/resources/application.yml | grep -A 10 "rabbitmq"

# Check event publication table
psql -d budget_analyzer -c "SELECT * FROM event_publication ORDER BY publication_date DESC LIMIT 10;"
```

## Testing Advanced Patterns

Follow the
[canonical service-common testing patterns](https://github.com/budgetanalyzer/service-common/blob/main/docs/testing-patterns.md)
before adding or changing tests. Keep application-owned providers, services, repositories,
listeners, consumers, and schedulers real. Use the infrastructure boundary that matches the
behavior: Testcontainers for PostgreSQL, Redis, and RabbitMQ; WireMock for FRED HTTP responses.

### Provider Abstraction Testing

Run the real provider and FRED client against WireMock. Assert the provider result or mapped error,
not calls between application beans. The shared `AbstractWireMockTest` also supplies the
Testcontainers application context.

```java
class FredExchangeRateProviderIntegrationTest extends AbstractWireMockTest {

    @Autowired
    private ExchangeRateProvider exchangeRateProvider;

    @Test
    void shouldFetchRatesFromFredResponse() {
        FredApiStubs.stubSuccessWithSampleData(TestConstants.FRED_SERIES_EUR);
        var currencySeries = CurrencySeriesTestBuilder.defaultEur().build();

        var rates = exchangeRateProvider.getExchangeRates(currencySeries, null);

        assertThat(rates).hasSize(8);
    }
}
```

See
[`FredExchangeRateProviderIntegrationTest`](../src/test/java/org/budgetanalyzer/currency/service/provider/FredExchangeRateProviderIntegrationTest.java)
for success, transformation, missing-data, and provider-error coverage.

### ShedLock Testing

Do not test ShedLock itself by starting multiple scheduler objects and verifying a service call
count. Test scheduler-owned import and retry outcomes with the real application service,
Testcontainers PostgreSQL, controlled WireMock responses, persisted rates, and metrics. When retry
timing must be deterministic, use a focused recording implementation of Spring's `TaskScheduler`
that exposes the scheduled task and time; do not replace the application import service.

The full application integration context validates that the JDBC lock provider and Flyway-managed
`shedlock` table can start together. Multi-pod lock coordination belongs to deployed-system
verification.

### Redis Caching Testing

Enable the real Redis cache only in cache-specific tests. Drive the mutation through the real API or
service and assert the affected cache entry or returned data.

```java
@TestPropertySource(properties = "spring.cache.type=redis")
class ExchangeRateImportCacheTest extends AbstractControllerTest {

    @Autowired
    private CacheManager cacheManager;

    @Test
    void shouldEvictCacheAfterImport() throws Exception {
        var cache = cacheManager.getCache(CacheConfig.EXCHANGE_RATES_CACHE);
        cache.put("EUR:2024-01-01:2024-01-05", "cached data");
        FredApiStubs.stubSuccessWithObservations(
            TestConstants.FRED_SERIES_EUR,
            List.of(new FredApiStubs.Observation("2024-01-01", "0.8500")));

        performPost("/v1/exchange-rates/import", "").andExpect(status().isOk());

        assertThat(cache.get("EUR:2024-01-01:2024-01-05")).isNull();
    }
}
```

See
[`ExchangeRateImportCacheTest`](../src/test/java/org/budgetanalyzer/currency/api/ExchangeRateImportCacheTest.java)
for the complete persisted setup and authenticated API request.

### Event-Driven Messaging Testing

Run the real listener, publisher, RabbitMQ binding, consumer, import service, and repositories.
Assert completed outbox events and imported database rows instead of publisher invocation counts.

```java
class EventListenerIntegrationTest extends AbstractWireMockTest {

    @Autowired
    private CurrencyService currencyService;

    @Autowired
    private ExchangeRateRepository exchangeRateRepository;

    @Test
    void shouldOnlyPublishImportEventForEnabledCurrency() {
        FredApiStubs.stubSeriesExistsSuccess(TestConstants.FRED_SERIES_EUR);
        FredApiStubs.stubSuccessWithSampleData(TestConstants.FRED_SERIES_EUR);
        var currencySeries = CurrencySeriesTestBuilder.defaultEur().build();

        var created = currencyService.create(currencySeries);

        await().untilAsserted(() -> {
            assertThat(testDatabaseHelper.countCompletedEvents()).isOne();
            assertThat(exchangeRateRepository.countByCurrencySeries(created)).isEqualTo(8);
        });
    }
}
```

See [`EventListenerIntegrationTest`](../src/test/java/org/budgetanalyzer/currency/messaging/EventListenerIntegrationTest.java)
for enabled/disabled event filtering and
[`EndToEndMessagingFlowIntegrationTest`](../src/test/java/org/budgetanalyzer/currency/integration/EndToEndMessagingFlowIntegrationTest.java)
for broker-driven consumption, concurrency, and imported-rate outcomes.

## Common Patterns and Best Practices

### Provider Pattern

✅ **Do:**
- Service depends on interface only
- Provider names never in service layer
- Use Spring profiles for provider selection

❌ **Don't:**
- Service importing concrete provider
- Hardcode provider logic in service
- Mix provider concerns with business logic

### Distributed Locking

✅ **Do:**
- Use database-backed locks (PostgreSQL)
- Set `lockAtMostFor` = task duration × 10-30
- Set `lockAtLeastFor` = 10-20% of interval
- Use unique lock names per task

❌ **Don't:**
- Use in-memory locks (not distributed)
- Set timeout shorter than task duration
- Reuse lock names across different tasks
- Forget to test multi-pod behavior

### Caching

✅ **Do:**
- Cache slow queries with repeated parameters
- Use meaningful cache keys
- Evict cache when data changes
- Monitor cache hit rates

❌ **Don't:**
- Cache fast operations
- Cache frequently changing data
- Forget to evict on updates
- Use cache for security decisions

### Messaging

✅ **Do:**
- Use transactional outbox for reliability
- Publish domain events from service
- Bridge to external broker in listener
- Make consumers idempotent

❌ **Don't:**
- Publish directly to RabbitMQ in transaction
- Put broker logic in service layer
- Assume exactly-once delivery
- Couple services via synchronous calls
