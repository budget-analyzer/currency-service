package org.budgetanalyzer.currency.scheduler;

import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ScheduledFuture;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.Trigger;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.budgetanalyzer.currency.base.AbstractWireMockTest;
import org.budgetanalyzer.currency.config.CurrencyServiceProperties;
import org.budgetanalyzer.currency.fixture.CurrencySeriesTestBuilder;
import org.budgetanalyzer.currency.fixture.FredApiStubs;
import org.budgetanalyzer.currency.fixture.TestConstants;
import org.budgetanalyzer.currency.repository.CurrencySeriesRepository;
import org.budgetanalyzer.currency.repository.ExchangeRateRepository;
import org.budgetanalyzer.currency.service.ExchangeRateImportService;

/** Integration tests for {@link ExchangeRateImportScheduler}. */
class ExchangeRateImportSchedulerIntegrationTest extends AbstractWireMockTest {

  private static final int DEFAULT_MAX_ATTEMPTS = 3;
  private static final long DEFAULT_DELAY_MINUTES = 1;

  @Autowired private ExchangeRateImportService exchangeRateImportService;

  @Autowired private CurrencySeriesRepository currencySeriesRepository;

  @Autowired private ExchangeRateRepository exchangeRateRepository;

  private RecordingTaskScheduler recordingTaskScheduler;
  private MeterRegistry meterRegistry;
  private CurrencyServiceProperties currencyServiceProperties;
  private ExchangeRateImportScheduler exchangeRateImportScheduler;

  @BeforeEach
  void setUp() {
    super.resetDatabaseAndWireMock();

    recordingTaskScheduler = new RecordingTaskScheduler();
    meterRegistry = new SimpleMeterRegistry();
    currencyServiceProperties = schedulerProperties(DEFAULT_MAX_ATTEMPTS, DEFAULT_DELAY_MINUTES);
    exchangeRateImportScheduler =
        new ExchangeRateImportScheduler(
            recordingTaskScheduler,
            meterRegistry,
            currencyServiceProperties,
            exchangeRateImportService);
  }

  @Test
  void shouldImportRatesAndRecordSuccessWithoutRetry() {
    persistEnabledEurSeries();
    FredApiStubs.stubSuccessWithSampleData(TestConstants.FRED_SERIES_EUR);

    exchangeRateImportScheduler.importDailyRates();

    assertThat(exchangeRateRepository.count()).isEqualTo(8);
    assertThat(recordingTaskScheduler.scheduledTaskCount()).isZero();
    assertThat(recordingTaskScheduler.hasPendingTask()).isFalse();
    wireMockServer.verify(
        1, getRequestedFor(urlPathEqualTo(TestConstants.FRED_API_PATH_OBSERVATIONS)));

    assertThat(
            meterRegistry
                .find("exchange.rate.import.duration")
                .tag("status", "success")
                .tag("attempt", "1")
                .timer())
        .isNotNull()
        .extracting(timer -> timer.count())
        .isEqualTo(1L);
    assertThat(
            meterRegistry
                .find("exchange.rate.import.executions")
                .tag("status", "success")
                .tag("attempt", "1")
                .counter())
        .isNotNull()
        .extracting(counter -> counter.count())
        .isEqualTo(1.0);
    assertThat(meterRegistry.find("exchange.rate.import.retry.scheduled").counter()).isNull();
    assertThat(meterRegistry.find("exchange.rate.import.exhausted").counter()).isNull();
  }

  @Test
  void shouldRecoverOnFinalAttemptAndPersistRates() {
    persistEnabledEurSeries();
    FredApiStubs.stubRecoveryScenario(TestConstants.FRED_SERIES_EUR);

    var firstAttemptStarted = Instant.now();
    exchangeRateImportScheduler.importDailyRates();

    assertRetryScheduledAt(0, firstAttemptStarted, Duration.ofMinutes(DEFAULT_DELAY_MINUTES));
    assertFailureMetric(1);

    var secondAttemptStarted = Instant.now();
    recordingTaskScheduler.runNext();

    assertRetryScheduledAt(1, secondAttemptStarted, Duration.ofMinutes(DEFAULT_DELAY_MINUTES));
    assertFailureMetric(2);

    recordingTaskScheduler.runNext();

    assertThat(recordingTaskScheduler.scheduledTaskCount()).isEqualTo(2);
    assertThat(recordingTaskScheduler.hasPendingTask()).isFalse();
    assertThat(exchangeRateRepository.count()).isEqualTo(8);
    wireMockServer.verify(
        DEFAULT_MAX_ATTEMPTS,
        getRequestedFor(urlPathEqualTo(TestConstants.FRED_API_PATH_OBSERVATIONS)));

    assertThat(
            meterRegistry
                .find("exchange.rate.import.executions")
                .tag("status", "success")
                .tag("attempt", "3")
                .counter())
        .isNotNull()
        .extracting(counter -> counter.count())
        .isEqualTo(1.0);
    assertThat(
            meterRegistry
                .find("exchange.rate.import.retry.scheduled")
                .tag("attempt", "2")
                .counter())
        .isNotNull()
        .extracting(counter -> counter.count())
        .isEqualTo(1.0);
    assertThat(
            meterRegistry
                .find("exchange.rate.import.retry.scheduled")
                .tag("attempt", "3")
                .counter())
        .isNotNull()
        .extracting(counter -> counter.count())
        .isEqualTo(1.0);
    assertThat(meterRegistry.find("exchange.rate.import.exhausted").counter()).isNull();
  }

  @Test
  void shouldStopAtConfiguredMaximumAndRecordExhaustion() {
    var maximumAttempts = 2;
    var delayMinutes = 5L;
    currencyServiceProperties
        .getExchangeRateImport()
        .setRetry(retry(maximumAttempts, delayMinutes));
    persistEnabledEurSeries();
    FredApiStubs.stubServerErrorForAll();

    var firstAttemptStarted = Instant.now();
    exchangeRateImportScheduler.importDailyRates();

    assertRetryScheduledAt(0, firstAttemptStarted, Duration.ofMinutes(delayMinutes));
    assertFailureMetric(1);

    recordingTaskScheduler.runNext();

    assertThat(recordingTaskScheduler.scheduledTaskCount()).isOne();
    assertThat(recordingTaskScheduler.hasPendingTask()).isFalse();
    assertThat(exchangeRateRepository.count()).isZero();
    wireMockServer.verify(
        maximumAttempts, getRequestedFor(urlPathEqualTo(TestConstants.FRED_API_PATH_OBSERVATIONS)));
    assertFailureMetric(2);
    assertThat(meterRegistry.find("exchange.rate.import.exhausted").counter())
        .isNotNull()
        .extracting(counter -> counter.count())
        .isEqualTo(1.0);
  }

  private void persistEnabledEurSeries() {
    var currencySeries = CurrencySeriesTestBuilder.defaultEur().build();
    currencySeriesRepository.save(currencySeries);
  }

  private void assertRetryScheduledAt(int index, Instant attemptStarted, Duration delay) {
    assertThat(recordingTaskScheduler.scheduledTime(index))
        .isBetween(attemptStarted.plus(delay), Instant.now().plus(delay));
  }

  private void assertFailureMetric(int attemptNumber) {
    assertThat(
            meterRegistry
                .find("exchange.rate.import.duration")
                .tag("status", "failure")
                .tag("attempt", String.valueOf(attemptNumber))
                .tag("error", "ClientException")
                .timer())
        .isNotNull()
        .extracting(timer -> timer.count())
        .isEqualTo(1L);
    assertThat(
            meterRegistry
                .find("exchange.rate.import.executions")
                .tag("status", "failure")
                .tag("attempt", String.valueOf(attemptNumber))
                .tag("error", "ClientException")
                .counter())
        .isNotNull()
        .extracting(counter -> counter.count())
        .isEqualTo(1.0);
  }

  private static CurrencyServiceProperties schedulerProperties(
      int maximumAttempts, long delayMinutes) {
    var currencyServiceProperties = new CurrencyServiceProperties();
    var exchangeRateImport = new CurrencyServiceProperties.ExchangeRateImport();
    exchangeRateImport.setRetry(retry(maximumAttempts, delayMinutes));
    currencyServiceProperties.setExchangeRateImport(exchangeRateImport);
    return currencyServiceProperties;
  }

  private static CurrencyServiceProperties.ExchangeRateImport.Retry retry(
      int maximumAttempts, long delayMinutes) {
    var retry = new CurrencyServiceProperties.ExchangeRateImport.Retry();
    retry.setMaxAttempts(maximumAttempts);
    retry.setDelayMinutes(delayMinutes);
    return retry;
  }

  /** Records one-time scheduled tasks so tests can execute them without waiting. */
  private static final class RecordingTaskScheduler implements TaskScheduler {

    private final Queue<Runnable> pendingTasks = new ArrayDeque<>();
    private final List<Instant> scheduledTimes = new ArrayList<>();

    @Override
    public ScheduledFuture<?> schedule(Runnable task, Instant startTime) {
      pendingTasks.add(task);
      scheduledTimes.add(startTime);
      return null;
    }

    @Override
    public ScheduledFuture<?> schedule(Runnable task, Trigger trigger) {
      throw unsupportedScheduling();
    }

    @Override
    public ScheduledFuture<?> scheduleAtFixedRate(
        Runnable task, Instant startTime, Duration period) {
      throw unsupportedScheduling();
    }

    @Override
    public ScheduledFuture<?> scheduleAtFixedRate(Runnable task, Duration period) {
      throw unsupportedScheduling();
    }

    @Override
    public ScheduledFuture<?> scheduleWithFixedDelay(
        Runnable task, Instant startTime, Duration delay) {
      throw unsupportedScheduling();
    }

    @Override
    public ScheduledFuture<?> scheduleWithFixedDelay(Runnable task, Duration delay) {
      throw unsupportedScheduling();
    }

    int scheduledTaskCount() {
      return scheduledTimes.size();
    }

    boolean hasPendingTask() {
      return !pendingTasks.isEmpty();
    }

    Instant scheduledTime(int index) {
      return scheduledTimes.get(index);
    }

    void runNext() {
      pendingTasks.remove().run();
    }

    private UnsupportedOperationException unsupportedScheduling() {
      return new UnsupportedOperationException("Only one-time Instant scheduling is supported");
    }
  }
}
