package com.bleurubin.budgetanalyzer.currency.service.provider;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

import com.bleurubin.budgetanalyzer.currency.domain.CurrencySeries;

/** Provider interface for fetching exchange rates from external data sources. */
public interface ExchangeRateProvider {

  /**
   * Retrieves exchange rates from the data source.
   *
   * @param currencySeries The currency series containing currency code and provider series ID
   * @param startDate The start date for the exchange rate data (null = fetch all)
   * @return Map of dates to exchange rates
   */
  Map<LocalDate, BigDecimal> getExchangeRates(CurrencySeries currencySeries, LocalDate startDate);
}
