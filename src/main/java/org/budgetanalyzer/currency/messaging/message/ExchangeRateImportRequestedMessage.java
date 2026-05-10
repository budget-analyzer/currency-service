package org.budgetanalyzer.currency.messaging.message;

/**
 * Message published when exchange rate import is requested for a currency series.
 *
 * <p>This message is published only for enabled currency series. It may be triggered by creating an
 * enabled currency or by enabling an existing disabled currency.
 *
 * @param currencySeriesId The ID of the currency series
 * @param currencyCode The ISO 4217 currency code
 * @param correlationId The correlation ID for distributed tracing
 */
public record ExchangeRateImportRequestedMessage(
    Long currencySeriesId, String currencyCode, String correlationId) {}
