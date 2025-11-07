package com.bleurubin.budgetanalyzer.currency.api.response;

import io.swagger.v3.oas.annotations.media.Schema;

import com.bleurubin.budgetanalyzer.currency.domain.CurrencySeries;

/** Response DTO for currency series operations. */
@Schema(description = "Currency series response with mapping details")
public record CurrencySeriesResponse(
    @Schema(
            description = "Unique identifier",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "1")
        Long id,
    @Schema(
            description = "ISO 4217 three-letter currency code",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "EUR")
        String currencyCode,
    @Schema(
            description = "Exchange rate provider series identifier",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "DEXUSEU")
        String providerSeriesId,
    @Schema(
            description = "Whether this currency is enabled for get exchange rate requests",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "true")
        boolean enabled) {

  /**
   * Create a response DTO from a domain entity.
   *
   * @param entity The currency series entity
   * @return CurrencySeriesResponse
   */
  public static CurrencySeriesResponse from(CurrencySeries entity) {
    return new CurrencySeriesResponse(
        entity.getId(), entity.getCurrencyCode(), entity.getProviderSeriesId(), entity.isEnabled());
  }
}
