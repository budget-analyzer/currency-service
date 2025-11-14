# Messaging Test Refactor Plan

## Executive Summary

The current `MessagingIntegrationTest.java` contains 25 tests, of which:
- **44%** don't test what they claim to test
- **68%** should be deleted
- **24%** are redundant
- **16%** are worth keeping

This plan refactors the messaging tests into 7 focused test files with properly verified behavior.

## Critical Quality Issues

### 1. Tests That Pretend to Test Things
- `shouldHaveDLQConfigured` - 30+ line comment explaining why it can't test DLQ, then tests normal import
- `shouldRetryConsumerOnImportServiceFailure` - Admits "we can't easily simulate retry" then tests success
- `shouldProcessEventsInOrder` - Doesn't verify order, just checks all imports completed
- `shouldPersistEventsAcrossRestarts` - Doesn't restart anything, just checks database
- `shouldHandleConcurrentCreations` - Creates currencies sequentially, not concurrently

### 2. Redundant Tests
Six tests verify `completion_date IS NOT NULL` with identical assertions:
- `shouldProcessCurrencyCreatedEventAndPublishToRabbitMQ`
- `shouldMarkEventAsCompletedAfterSuccessfulPublishing`
- `shouldRetryEventProcessingUntilSuccess`
- `shouldCompleteFullFlowWithinReasonableTime`
- And others...

### 3. Weak Assertions
Many use `assertThat(count).isGreaterThan(0)` instead of exact values, making them useless for regression detection.

### 4. Misplaced Tests
Configuration verification tests belong in unit tests, not integration tests.

## Refactoring Plan

### Phase 1: Create 7 Focused Test Files

#### 1. EventListenerIntegrationTest.java
**Focus**: `MessagingEventListener` filtering and message publishing behavior

**Tests to migrate/create**:
- `shouldPublishMessageForEnabledCurrency` (from existing shouldNotPublishMessageForDisabledCurrency - invert)
- `shouldNotPublishMessageForDisabledCurrency` ✓ (keep existing)
- `shouldPropagateCorrelationIdToMessage` (new - verify actual message headers)
- `shouldFilterDisabledCurrenciesBeforePublishing` (new - test listener filtering logic)
- `shouldHandlePublisherFailureWithRetry` (new - simulate RabbitMQ failure)

**Key improvements**:
- Verify actual RabbitMQ message headers using RabbitTemplate
- Test listener filtering logic in isolation
- Use exact assertions, not `> 0`

#### 2. MessageConsumerIntegrationTest.java
**Focus**: `ExchangeRateImportConsumer` message consumption and processing

**Tests to migrate/create**:
- `shouldImportExchangeRatesWhenMessageReceived` ✓ (from shouldImportExchangeRatesWhenCurrencyCreatedMessageReceived)
- `shouldHandleMultipleCurrenciesIndependently` ✓ (keep existing)
- `shouldOnlyImportForEnabledCurrency` (improve - use exact count)
- `shouldLogImportResults` (new - verify structured logging)
- `shouldPropagateCorrelationIdToImportService` (new - verify correlation ID in service layer)

**Key improvements**:
- Replace `isGreaterThan(0)` with `assertEquals(8, count)`
- Verify log output contains expected correlation IDs
- Test consumer error handling

#### 3. EndToEndMessagingFlowIntegrationTest.java
**Focus**: Complete flow from service method to exchange rate import

**Tests to migrate/create**:
- `shouldCompleteFullFlowForEnabledCurrency` ✓ (from shouldImportExchangeRatesWhenCurrencyCreatedMessageReceived)
- `shouldSkipImportForDisabledCurrency` ✓ (from shouldNotPublishMessageForDisabledCurrency)
- `shouldHandleMultipleConcurrentCreations` (rewrite - **actual concurrency** with ExecutorService)
- `shouldMaintainCorrelationIdThroughEntireFlow` ✓ (improve existing)
- `shouldCompleteFlowWithinSLA` (new - actual performance test with meaningful timeout)

**Key improvements**:
- **Actually test concurrency**: Use `ExecutorService` with 5 threads, `CountDownLatch`
- Verify end-to-end latency (e.g., < 5 seconds for full flow)
- Add sequence verification for ordering tests

#### 4. RetryAndErrorHandlingIntegrationTest.java ⭐ NEW
**Focus**: Retry logic with **actual failure simulation**

**Tests to create**:
- `shouldRetryOnFredApiTransientFailure` - WireMock returns 500, verify 3 retry attempts
- `shouldUseExponentialBackoff` - Verify delays between retries (1s, 2s, 4s)
- `shouldStopRetryingAfterMaxAttempts` - Verify failure after 3 attempts
- `shouldRetryEventPublishingOnRabbitMQFailure` - Simulate broker connection loss
- `shouldRecoverAfterTransientFailure` - 500, 500, 200 → success on 3rd attempt

**Implementation approach**:
```java
@Test
void shouldRetryOnFredApiTransientFailure() {
    // Setup WireMock to return 500 errors
    fredWireMock.stubFor(get(urlPathEqualTo("/fred/series/observations"))
        .willReturn(aResponse().withStatus(500)));

    // Create currency (triggers import)
    var currency = createEnabledCurrency("EUR");

    // Wait for retries to complete
    await().atMost(15, SECONDS).until(() ->
        fredWireMock.verify(exactly(3), getRequestedFor(urlPathEqualTo("/fred/series/observations")))
    );

    // Verify no rates imported (all retries failed)
    assertEquals(0, exchangeRateRepository.countByCurrencySeriesId(currency.getId()));
}
```

**Key improvements**:
- Use WireMock for reliable failure simulation
- Verify actual retry counts using WireMock verification
- Measure delays between attempts to verify exponential backoff

#### 5. DeadLetterQueueIntegrationTest.java ⭐ NEW
**Focus**: DLQ routing with **actual poison messages**

**Tests to create**:
- `shouldRoutePoisonMessageToDLQ` - Send malformed JSON, verify in DLQ
- `shouldPreserveOriginalMessageInDLQ` - Verify message content preserved
- `shouldIncludeErrorDetailsInDLQHeaders` - Verify exception info in headers
- `shouldContinueProcessingAfterDLQ` - Valid message after poison message still works
- `shouldNotRetryPoisonMessagesIndefinitely` - Verify max attempts respected

**Implementation approach**:
```java
@Test
void shouldRoutePoisonMessageToDLQ() {
    // Send malformed message directly to exchange
    rabbitTemplate.convertAndSend(
        "currency.events.exchange",
        "currency.created.EUR",
        "{invalid json}",
        message -> {
            message.getMessageProperties().setCorrelationId(UUID.randomUUID().toString());
            return message;
        }
    );

    // Wait for DLQ routing
    await().atMost(10, SECONDS).until(() -> {
        Long count = rabbitAdmin.getQueueInfo("currency.events.dlq").getMessageCount();
        return count != null && count > 0;
    });

    // Verify message in DLQ
    Message dlqMessage = rabbitTemplate.receive("currency.events.dlq", 1000);
    assertNotNull(dlqMessage);
    assertTrue(new String(dlqMessage.getBody()).contains("invalid json"));
}
```

**Key improvements**:
- Use `RabbitAdmin` to inspect DLQ contents
- Send actual poison messages to test routing
- Verify error headers and stack traces

#### 6. TransactionalOutboxIntegrationTest.java
**Focus**: Transactional outbox pattern guarantees

**Tests to migrate/create**:
- `shouldCommitEventWithCurrencyInSameTransaction` (improve existing)
- `shouldRollbackEventWhenCurrencyCreationFails` (new - test rollback)
- `shouldPublishAllEventsAfterRestart` (new - **actual** restart simulation)
- `shouldMaintainEventHistoryForAudit` (improve - verify retention policy)

**Key improvements**:
- Test actual rollback scenarios (use `@Transactional(propagation = REQUIRES_NEW)` to force rollback)
- Restart testing: Use `@DirtiesContext` to restart Spring context
- Verify both currency and event are missing after rollback

#### 7. MessagingConfigurationTest.java (Unit Test)
**Focus**: Configuration verification without running full integration

**Tests to create**:
- `shouldHaveDLQConfigured` - Parse application.yml, verify DLQ settings
- `shouldHaveRetryConfigured` - Verify max-attempts=3, multiplier=2.0
- `shouldHaveExchangeBindingsConfigured` - Verify exchange/queue bindings
- `shouldHaveConsumerConcurrencyConfigured` - Verify listener container settings

**Implementation approach**:
```java
@SpringBootTest
@TestPropertySource(locations = "classpath:application.yml")
class MessagingConfigurationTest {

    @Autowired
    private RabbitAdmin rabbitAdmin;

    @Test
    void shouldHaveDLQConfigured() {
        // Verify DLQ exists
        Properties queueProps = rabbitAdmin.getQueueProperties("currency.events.dlq");
        assertNotNull(queueProps, "DLQ queue should be configured");

        // Verify binding to DLQ exchange
        // ...
    }
}
```

### Phase 2: Test Migration Matrix

| Original Test | Fate | New Location |
|--------------|------|--------------|
| shouldProcessCurrencyCreatedEventAndPublishToRabbitMQ | DELETE | Redundant |
| shouldIncludeCorrelationIdInPublishedMessage | IMPROVE | EventListener |
| shouldNotPublishMessageForDisabledCurrency | KEEP | EventListener |
| shouldMarkEventAsCompletedAfterSuccessfulPublishing | DELETE | Redundant |
| shouldImportExchangeRatesWhenCurrencyCreatedMessageReceived | KEEP | EndToEndFlow |
| shouldOnlyImportForEnabledCurrency | IMPROVE | MessageConsumer |
| shouldPropagateCorrelationIdThroughEntireFlow | IMPROVE | EndToEndFlow |
| shouldHandleMultipleCurrenciesIndependently | KEEP | MessageConsumer |
| shouldReturnImportResultWithCounts | RENAME | MessageConsumer |
| shouldCompleteFullFlowWithinReasonableTime | DELETE | Weak test |
| shouldProcessEventsInOrder | REWRITE | EndToEndFlow (with actual ordering verification) |
| shouldHandleConcurrentCreations | REWRITE | EndToEndFlow (with actual concurrency) |
| shouldCommitEventWithCurrencyInSameTransaction | IMPROVE | TransactionalOutbox |
| shouldPersistEventsAcrossRestarts | DELETE | Doesn't test restart |
| shouldRetryConsumerOnImportServiceFailure | DELETE | Doesn't test retry |
| shouldHaveExponentialBackoffConfigured | DELETE | Move to config unit test |
| shouldRetryEventProcessingUntilSuccess | DELETE | Redundant |
| shouldMaintainEventHistoryForAudit | IMPROVE | TransactionalOutbox |
| shouldHaveDLQConfigured | DELETE | Move to config unit test |
| shouldContinueProcessingOtherMessagesAfterDLQ | REWRITE | DeadLetterQueue (with actual DLQ) |
| shouldPreserveDLQConfiguration | DELETE | Move to config unit test |

**Summary**:
- KEEP as-is: 4 tests
- IMPROVE: 6 tests
- REWRITE: 3 tests
- DELETE: 12 tests
- NEW: 15+ tests

### Phase 3: Implementation Steps

#### Step 1: Setup Test Infrastructure
1. Add WireMock dependency to `build.gradle`:
```gradle
testImplementation 'org.wiremock:wiremock-standalone:3.3.1'
```

2. Create base test class with common setup:
```java
@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext
abstract class MessagingIntegrationTestBase {
    @Autowired protected CurrencySeriesRepository currencyRepository;
    @Autowired protected ExchangeRateRepository exchangeRateRepository;
    @Autowired protected RabbitTemplate rabbitTemplate;
    @Autowired protected RabbitAdmin rabbitAdmin;

    @RegisterExtension
    static WireMockExtension fredWireMock = WireMockExtension.newInstance()
        .options(wireMockConfig().port(8089))
        .build();

    protected CurrencySeries createEnabledCurrency(String code) {
        // Common test data creation
    }
}
```

#### Step 2: Create Test Files (in order)
1. `MessagingConfigurationTest.java` - Simplest, no migration needed
2. `EventListenerIntegrationTest.java` - Migrate 3 tests
3. `MessageConsumerIntegrationTest.java` - Migrate 3 tests
4. `EndToEndMessagingFlowIntegrationTest.java` - Migrate 3 tests, add concurrency
5. `TransactionalOutboxIntegrationTest.java` - Migrate 2 tests, add rollback
6. `RetryAndErrorHandlingIntegrationTest.java` - All new with WireMock
7. `DeadLetterQueueIntegrationTest.java` - All new with RabbitAdmin

#### Step 3: Delete Original File
After all migrations complete and tests pass:
```bash
rm src/test/java/org/budgetanalyzer/currency/integration/MessagingIntegrationTest.java
```

#### Step 4: Verify Coverage
```bash
./gradlew test jacocoTestReport
# Verify coverage maintained or improved (currently ~80%, target 85%+)
```

### Phase 4: Quality Gates

Each new test file must pass these quality checks:

#### 1. Assertion Quality
- ❌ `assertThat(count).isGreaterThan(0)`
- ✅ `assertEquals(8, count, "Should import exactly 8 exchange rates for EUR/USD")`

#### 2. Test Naming
- ❌ `shouldRetryConsumerOnImportServiceFailure` (but doesn't test retry)
- ✅ `shouldRetryImportThreeTimesOnFredApiFailure` (tests actual retry with exact count)

#### 3. What vs. How
Tests should verify **what** happened, not **how**:
- ❌ Checking internal implementation details
- ✅ Checking observable behavior (DB state, messages in queues, logs)

#### 4. No "Pretend" Tests
Every test must verify the behavior it claims to test:
- If test name says "retry", must verify multiple attempts
- If test name says "DLQ", must verify message in DLQ
- If test name says "concurrent", must use multiple threads
- If test name says "order", must verify sequence

#### 5. Meaningful Timeouts
- ❌ `await().atMost(1, SECONDS)` for multi-step async flow
- ✅ `await().atMost(10, SECONDS)` with pollInterval(100, MILLISECONDS)

### Expected Outcomes

#### Before Refactor
- 25 tests in 1 file (700+ lines)
- 11 tests (44%) don't test what they claim
- 17 tests (68%) should be deleted
- No retry testing
- No DLQ testing
- Weak assertions (`> 0`)

#### After Refactor
- ~35 tests in 7 files (~150 lines each)
- 100% of tests verify claimed behavior
- Comprehensive retry testing with WireMock
- Comprehensive DLQ testing with RabbitAdmin
- Exact assertions with meaningful failure messages
- Better test organization by concern

#### Coverage Impact
- Current: ~80% line coverage, ~60% branch coverage
- Target: 85%+ line coverage, 75%+ branch coverage
- New coverage: Retry paths, DLQ routing, error handling

## Missing Test Scenarios

The refactor will add these critical missing tests:

1. **Actual Retry Testing** - Currently missing
   - Transient failures → retry → success
   - Permanent failures → exhaust retries → DLQ
   - Exponential backoff verification

2. **Actual DLQ Testing** - Currently missing
   - Poison message routing
   - DLQ message inspection
   - Continued processing after DLQ

3. **Actual Concurrency Testing** - Currently broken
   - Multiple threads creating currencies
   - Race condition handling
   - Thread-safe message processing

4. **Actual Ordering Testing** - Currently missing
   - FIFO queue ordering
   - Timestamp verification
   - Sequence number validation

5. **Error Propagation** - Currently missing
   - Exception handling in consumer
   - Error logging verification
   - Failure metrics

## Implementation Timeline

- **Step 1 (Setup)**: 30 minutes - Add WireMock, create base test class
- **Step 2a (Config test)**: 30 minutes - Create MessagingConfigurationTest
- **Step 2b (Listener test)**: 1 hour - Create EventListenerIntegrationTest
- **Step 2c (Consumer test)**: 1 hour - Create MessageConsumerIntegrationTest
- **Step 2d (E2E test)**: 1.5 hours - Create EndToEndMessagingFlowIntegrationTest
- **Step 2e (Outbox test)**: 1 hour - Create TransactionalOutboxIntegrationTest
- **Step 2f (Retry test)**: 2 hours - Create RetryAndErrorHandlingIntegrationTest (most complex)
- **Step 2g (DLQ test)**: 2 hours - Create DeadLetterQueueIntegrationTest (most complex)
- **Step 3 (Cleanup)**: 15 minutes - Delete old file, verify build
- **Step 4 (Coverage)**: 30 minutes - Run coverage report, document improvements

**Total estimated time**: 10-12 hours

## Success Criteria

✅ All new test files created and passing
✅ Original MessagingIntegrationTest.java deleted
✅ Build passes: `./gradlew clean build`
✅ Coverage maintained or improved (85%+ target)
✅ All retry scenarios tested with WireMock
✅ All DLQ scenarios tested with RabbitAdmin
✅ Concurrency tested with actual threads
✅ No tests with "pretend" behavior
✅ All assertions use exact values, not `> 0`
✅ Test execution time < 60 seconds (currently ~30s)
