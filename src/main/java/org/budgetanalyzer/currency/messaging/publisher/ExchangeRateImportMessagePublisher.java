package org.budgetanalyzer.currency.messaging.publisher;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.stereotype.Component;

import org.budgetanalyzer.currency.messaging.message.ExchangeRateImportRequestedMessage;

/**
 * Publisher for exchange rate import messages.
 *
 * <p>Encapsulates Spring Cloud Stream message publishing, keeping service layer decoupled from
 * messaging infrastructure.
 */
@Component
public class ExchangeRateImportMessagePublisher {

  private static final Logger logger =
      LoggerFactory.getLogger(ExchangeRateImportMessagePublisher.class);
  private static final String EXCHANGE_RATE_IMPORT_REQUESTED_BINDING =
      "exchangeRateImportRequested-out-0";

  private final StreamBridge streamBridge;

  public ExchangeRateImportMessagePublisher(StreamBridge streamBridge) {
    this.streamBridge = streamBridge;
  }

  /**
   * Publish a message requesting exchange rate import for an enabled currency series.
   *
   * @param message The exchange rate import requested message
   */
  public void publishExchangeRateImportRequested(ExchangeRateImportRequestedMessage message) {
    logger.info(
        "Publishing exchange rate import requested message: binding={}, currencySeriesId={}, "
            + "currencyCode={}",
        EXCHANGE_RATE_IMPORT_REQUESTED_BINDING,
        message.currencySeriesId(),
        message.currencyCode());

    streamBridge.send(EXCHANGE_RATE_IMPORT_REQUESTED_BINDING, message);
  }
}
