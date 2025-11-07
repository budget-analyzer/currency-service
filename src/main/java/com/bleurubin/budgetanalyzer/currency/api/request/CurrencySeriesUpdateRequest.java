package com.bleurubin.budgetanalyzer.currency.api.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import io.swagger.v3.oas.annotations.media.Schema;

import com.bleurubin.budgetanalyzer.currency.domain.CurrencySeries;

/** Request DTO for updating an existing currency series. */
@Schema(description = "Request to update an existing currency series (currency code is immutable)")
public record CurrencySeriesUpdateRequest(
    @Schema(
            description = "Exchange rate provider series identifier for this currency",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "DEXUSEU")
        @NotBlank(message = "Provider series ID is required")
        @Size(max = 50, message = "Provider series ID must not exceed 50 characters")
        String providerSeriesId,
    @Schema(
            description = "Whether this currency is enabled for exchange rate imports",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "true")
        boolean enabled) {

  /**
   * Update an existing entity with values from this request.
   *
   * @param entity The entity to update
   */
  public void updateEntity(CurrencySeries entity) {
    entity.setProviderSeriesId(providerSeriesId);
    entity.setEnabled(enabled);
  }
}
