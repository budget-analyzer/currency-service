package com.bleurubin.budgetanalyzer.currency.service.provider;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Currency;
import java.util.Map;

public interface ExchangeRateProvider {

  Map<LocalDate, BigDecimal> getExchangeRates(
      Currency baseCurrency, Currency targetCurrency, LocalDate startDate);
}
