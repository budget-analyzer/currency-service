package org.budgetanalyzer.currency.messaging;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import org.budgetanalyzer.currency.base.AbstractWireMockTest;
import org.budgetanalyzer.currency.fixture.CurrencySeriesTestBuilder;
import org.budgetanalyzer.currency.fixture.FredApiStubs;
import org.budgetanalyzer.currency.fixture.TestConstants;
import org.budgetanalyzer.currency.repository.ExchangeRateRepository;
import org.budgetanalyzer.currency.service.CurrencyService;

/**
 * Integration tests for {@link
 * org.budgetanalyzer.currency.messaging.listener.MessagingEventListener}.
 *
 * <p><b>Focus:</b> Verifies import-triggering events through completed outbox publications and
 * broker-driven imports.
 *
 * <p><b>Test Coverage:</b>
 *
 * <ul>
 *   <li>Only enabled currencies produce completed import events and persisted rates
 *   <li>Disabling a currency does not produce another import event
 * </ul>
 */
class EventListenerIntegrationTest extends AbstractWireMockTest {

  private static final int WAIT_TIME = 1;

  @Autowired private CurrencyService currencyService;

  @Autowired private ExchangeRateRepository exchangeRateRepository;

  @BeforeEach
  void cleanup() {
    super.resetDatabaseAndWireMock();
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
              var completedEvents = countCompletedEvents();
              assertEquals(1, completedEvents, "Should have exactly 1 completed event");

              // Only enabled currency should have imported rates
              var enabledRates = exchangeRateRepository.countByCurrencySeries(createdEnabled);
              assertEquals(
                  8, enabledRates, "Enabled currency should import exactly 8 exchange rates");

              var disabledRates = exchangeRateRepository.countByCurrencySeries(createdDisabled);
              assertEquals(0, disabledRates, "Disabled currency should NOT import any rates");
            });
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
              var completedEvents = countCompletedEvents();
              assertEquals(1, completedEvents, "Create should publish one import event");

              var importedRates = exchangeRateRepository.countByCurrencySeries(created);
              assertEquals(
                  8, importedRates, "Enabled currency should import exactly 8 exchange rates");
            });

    // Act
    var disabled = currencyService.update(created.getId(), false);

    // Assert - Allow time for an unexpected asynchronous event to become observable.
    await()
        .pollDelay(Duration.ofMillis(500))
        .atMost(WAIT_TIME, SECONDS)
        .untilAsserted(
            () -> {
              var completedEvents = countCompletedEvents();
              assertEquals(1, completedEvents, "Disable should not publish another event");

              var importedRates = exchangeRateRepository.countByCurrencySeries(disabled);
              assertEquals(8, importedRates, "Disable should not trigger another import");
            });
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
