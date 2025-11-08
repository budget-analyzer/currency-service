package com.bleurubin.budgetanalyzer.currency.messaging.consumer;

import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.bleurubin.budgetanalyzer.currency.messaging.message.CurrencyCreatedMessage;
import com.bleurubin.budgetanalyzer.currency.service.ExchangeRateImportService;
import com.bleurubin.service.http.CorrelationIdFilter;

/**
 * Consumer for currency-related messages.
 *
 * <p>Processes currency created messages by triggering asynchronous exchange rate imports.
 */
@Configuration
public class ExchangeRateImportConsumer {

  private static final Logger log = LoggerFactory.getLogger(ExchangeRateImportConsumer.class);

  private final ExchangeRateImportService exchangeRateImportService;

  public ExchangeRateImportConsumer(ExchangeRateImportService exchangeRateImportService) {
    this.exchangeRateImportService = exchangeRateImportService;
  }

  /**
   * Consumer function for currency created messages.
   *
   * <p>Bean name "importExchangeRates" creates binding "importExchangeRates-in-0". Errors are
   * logged but not thrown - Spring Cloud Stream will retry via RabbitMQ.
   *
   * <p>Uses MDC (Mapped Diagnostic Context) for distributed tracing - all log statements within
   * this consumer will automatically include the correlation ID and event type.
   *
   * @return Consumer function processing CurrencyCreatedMessage
   */
  @Bean
  public Consumer<CurrencyCreatedMessage> importExchangeRates() {
    return message -> {
      // Set correlation ID and event type in MDC for distributed tracing
      MDC.put(CorrelationIdFilter.CORRELATION_ID_MDC_KEY, message.correlationId());
      MDC.put("eventType", "currency_created");

      try {
        log.info(
            "Received currency created message: currencySeriesId={}, currencyCode={}",
            message.currencySeriesId(),
            message.currencyCode());

        var result =
            exchangeRateImportService.importExchangeRatesForSeries(message.currencySeriesId());

        log.info(
            "Exchange rate import completed: currencyCode={}, new={}, updated={}, skipped={}",
            message.currencyCode(),
            result.newRecords(),
            result.updatedRecords(),
            result.skippedRecords());
      } catch (Exception e) {
        log.error(
            "Failed to import exchange rates for currency series: {}",
            message.currencySeriesId(),
            e);
        // Don't throw - let Spring Cloud Stream retry mechanism handle it
      } finally {
        // Clean up MDC to prevent memory leaks in thread pool
        MDC.clear();
      }
    };
  }
}
