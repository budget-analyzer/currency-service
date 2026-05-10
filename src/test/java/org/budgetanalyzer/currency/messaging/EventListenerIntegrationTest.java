package org.budgetanalyzer.currency.messaging;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import org.budgetanalyzer.currency.base.AbstractWireMockTest;
import org.budgetanalyzer.currency.fixture.CurrencySeriesTestBuilder;
import org.budgetanalyzer.currency.fixture.FredApiStubs;
import org.budgetanalyzer.currency.fixture.TestConstants;
import org.budgetanalyzer.currency.messaging.publisher.ExchangeRateImportMessagePublisher;
import org.budgetanalyzer.currency.repository.ExchangeRateRepository;
import org.budgetanalyzer.currency.service.CurrencyService;

/**
 * Integration tests for {@link
 * org.budgetanalyzer.currency.messaging.listener.MessagingEventListener}.
 *
 * <p><b>Focus:</b> Verifies import-triggering event publication and message publishing behavior.
 *
 * <p><b>Test Coverage:</b>
 *
 * <ul>
 *   <li>Publishing messages for enabled currencies
 *   <li>No event or message for disabled currencies
 *   <li>No event or message when disabling currencies
 * </ul>
 *
 * <p><b>Key Improvements Over Original Tests:</b>
 *
 * <ul>
 *   <li>Verifies the publisher is called only for enabled currencies
 *   <li>Uses exact assertions (not {@code > 0})
 *   <li>Tests listener filtering logic in isolation
 * </ul>
 */
class EventListenerIntegrationTest extends AbstractWireMockTest {

  private static final int WAIT_TIME = 1;

  @Autowired private CurrencyService currencyService;

  @Autowired private ExchangeRateRepository exchangeRateRepository;

  @MockitoSpyBean private ExchangeRateImportMessagePublisher exchangeRateImportMessagePublisher;

  @BeforeEach
  void cleanup() {
    super.resetDatabaseAndWireMock();
  }

  /**
   * Verifies that MessagingEventListener publishes external message to RabbitMQ when currency is
   * enabled.
   *
   * <p>This is the happy path test - enabled currency triggers full flow including external message
   * publishing.
   */
  @Test
  void shouldPublishMessageForEnabledCurrency() {
    // Arrange
    FredApiStubs.stubSeriesExistsSuccess(TestConstants.FRED_SERIES_EUR);
    FredApiStubs.stubSuccessWithSampleData(TestConstants.FRED_SERIES_EUR);
    var currencySeries = CurrencySeriesTestBuilder.defaultEur().enabled(true).build();

    // Act
    var created = currencyService.create(currencySeries);

    // Assert - Event processed and message published
    await()
        .atMost(WAIT_TIME, SECONDS)
        .untilAsserted(
            () -> {
              // Verify event completed
              Long completedEvents = countCompletedEvents();
              assertEquals(1, completedEvents, "Should have exactly 1 completed event");

              // Verify message was published (and consumed, triggering import)
              Long importedRates = exchangeRateRepository.countByCurrencySeries(created);
              assertEquals(
                  8, importedRates, "Should import exactly 8 exchange rates from FRED stub data");
            });

    // Verify publisher was called exactly once
    verify(exchangeRateImportMessagePublisher, times(1)).publishExchangeRateImportRequested(any());
  }

  /**
   * Verifies that disabled currency creation does not publish an import-triggering domain event.
   *
   * <p>The service filters disabled currencies before publishing to Spring Modulith, avoiding an
   * event-processing hop whose only result would be no external message.
   */
  @Test
  void shouldNotPublishEventOrMessageForDisabledCurrency() {
    // Arrange
    FredApiStubs.stubSeriesExistsSuccess(TestConstants.FRED_SERIES_EUR);
    FredApiStubs.stubSuccessWithSampleData(TestConstants.FRED_SERIES_EUR);
    var currencySeries = CurrencySeriesTestBuilder.defaultEur().enabled(false).build();

    // Act
    var created = currencyService.create(currencySeries);

    // Assert - No event or message published
    await()
        .atMost(WAIT_TIME, SECONDS)
        .untilAsserted(
            () -> {
              Long completedEvents = countCompletedEvents();
              assertEquals(0, completedEvents, "No import-triggering event should be published");

              Long importedRates = exchangeRateRepository.countByCurrencySeries(created);
              assertEquals(0, importedRates, "Should NOT import rates for disabled currency");
            });

    verify(exchangeRateImportMessagePublisher, times(0)).publishExchangeRateImportRequested(any());
  }

  /**
   * Verifies that CurrencyService only publishes import-triggering events for enabled currencies.
   *
   * <p>The enabled check happens before publishing to Spring Modulith, not in the listener or
   * publisher.
   */
  @Test
  void shouldOnlyPublishImportEventForEnabledCurrency() {
    // Arrange
    FredApiStubs.stubSeriesExistsSuccess(TestConstants.FRED_SERIES_EUR);
    FredApiStubs.stubSuccessWithSampleData(TestConstants.FRED_SERIES_EUR);
    FredApiStubs.stubSeriesExistsSuccess(TestConstants.FRED_SERIES_GBP);
    FredApiStubs.stubSuccessWithSampleData(TestConstants.FRED_SERIES_GBP);

    var enabledCurrency = CurrencySeriesTestBuilder.defaultEur().enabled(true).build();
    var disabledCurrency = CurrencySeriesTestBuilder.defaultGbp().enabled(false).build();

    // Act - Create both currencies
    var createdEnabled = currencyService.create(enabledCurrency);
    var createdDisabled = currencyService.create(disabledCurrency);

    // Assert - Only enabled currency triggers import
    await()
        .atMost(WAIT_TIME, SECONDS)
        .untilAsserted(
            () -> {
              Long completedEvents = countCompletedEvents();
              assertEquals(1, completedEvents, "Should have exactly 1 completed event");

              // Only enabled currency should have imported rates
              Long enabledRates = exchangeRateRepository.countByCurrencySeries(createdEnabled);
              assertEquals(
                  8, enabledRates, "Enabled currency should import exactly 8 exchange rates");

              Long disabledRates = exchangeRateRepository.countByCurrencySeries(createdDisabled);
              assertEquals(0, disabledRates, "Disabled currency should NOT import any rates");
            });

    // Verify publisher called exactly once (only for enabled currency)
    verify(exchangeRateImportMessagePublisher, times(1)).publishExchangeRateImportRequested(any());
  }

  /**
   * Verifies that disabling an enabled currency updates the database without publishing an import
   * event or external import request.
   */
  @Test
  void shouldNotPublishEventOrMessageWhenCurrencyDisabled() {
    // Arrange
    FredApiStubs.stubSeriesExistsSuccess(TestConstants.FRED_SERIES_EUR);
    FredApiStubs.stubSuccessWithSampleData(TestConstants.FRED_SERIES_EUR);
    var currencySeries = CurrencySeriesTestBuilder.defaultEur().enabled(true).build();
    var created = currencyService.create(currencySeries);

    await()
        .atMost(WAIT_TIME, SECONDS)
        .untilAsserted(
            () -> {
              Long completedEvents = countCompletedEvents();
              assertEquals(1, completedEvents, "Create should publish one import event");

              Long importedRates = exchangeRateRepository.countByCurrencySeries(created);
              assertEquals(
                  8, importedRates, "Enabled currency should import exactly 8 exchange rates");
            });

    reset(exchangeRateImportMessagePublisher);

    // Act
    currencyService.update(created.getId(), false);

    // Assert
    await()
        .atMost(WAIT_TIME, SECONDS)
        .untilAsserted(
            () -> {
              Long completedEvents = countCompletedEvents();
              assertEquals(1, completedEvents, "Disable should not publish another event");
            });

    verify(exchangeRateImportMessagePublisher, times(0)).publishExchangeRateImportRequested(any());
  }

  // ===========================================================================================
  // Helper Methods
  // ===========================================================================================

  /**
   * Counts completed events in event_publication table.
   *
   * @return Number of events with non-null completion_date
   */
  private Long countCompletedEvents() {
    return testDatabaseHelper.countCompletedEvents();
  }
}
