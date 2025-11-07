package com.bleurubin.budgetanalyzer.currency.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Currency;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bleurubin.budgetanalyzer.currency.config.CacheConfig;
import com.bleurubin.budgetanalyzer.currency.domain.CurrencySeries;
import com.bleurubin.budgetanalyzer.currency.domain.ExchangeRate;
import com.bleurubin.budgetanalyzer.currency.dto.ExchangeRateImportResult;
import com.bleurubin.budgetanalyzer.currency.repository.CurrencySeriesRepository;
import com.bleurubin.budgetanalyzer.currency.repository.ExchangeRateRepository;
import com.bleurubin.budgetanalyzer.currency.service.provider.ExchangeRateProvider;
import com.bleurubin.service.exception.ServiceException;

/**
 * Service for importing exchange rates from external data providers.
 *
 * <p>Handles fetching exchange rates from providers, deduplication, and persisting to the database
 * with cache invalidation.
 */
@Service
public class ExchangeRateImportService {

  private static final Logger log = LoggerFactory.getLogger(ExchangeRateImportService.class);

  // The base currency will always be USD but i wanted to keep the column in the database
  // in case the design needs to change in the future.
  private static final Currency BASE_CURRENCY = Currency.getInstance("USD");

  private final ExchangeRateProvider exchangeRateProvider;
  private final ExchangeRateRepository exchangeRateRepository;
  private final CurrencySeriesRepository currencySeriesRepository;

  /**
   * Constructs a new ExchangeRateImportService.
   *
   * @param exchangeRateProvider The provider to fetch exchange rates from
   * @param exchangeRateRepository The exchange rate data access repository
   * @param currencySeriesRepository The currency series data access repository
   */
  public ExchangeRateImportService(
      ExchangeRateProvider exchangeRateProvider,
      ExchangeRateRepository exchangeRateRepository,
      CurrencySeriesRepository currencySeriesRepository) {
    this.exchangeRateProvider = exchangeRateProvider;
    this.exchangeRateRepository = exchangeRateRepository;
    this.currencySeriesRepository = currencySeriesRepository;
  }

  /**
   * Checks if any exchange rate data exists in the database.
   *
   * @return true if exchange rate data exists, false otherwise
   */
  public boolean hasExchangeRateData() {
    // TODO: verify that the rates are for an enabled currency
    return exchangeRateRepository.count() > 0;
  }

  /**
   * Imports the latest exchange rates from the external provider for all enabled currencies.
   *
   * <p>After successful import, evicts all cached exchange rate queries to ensure immediate
   * consistency across all application instances.
   *
   * @return import result with combined counts of new, updated, and skipped rates across all
   *     currencies
   */
  @Transactional
  @CacheEvict(cacheNames = CacheConfig.EXCHANGE_RATES_CACHE, allEntries = true)
  public ExchangeRateImportResult importLatestExchangeRates() {
    var enabledCurrencies = currencySeriesRepository.findByEnabledTrue();

    if (enabledCurrencies.isEmpty()) {
      log.warn("No enabled currency series found - skipping import");
      return new ExchangeRateImportResult(0, 0, 0, null, null);
    }

    log.info("Found {} enabled currency series for import", enabledCurrencies.size());

    var totalNew = 0;
    var totalUpdated = 0;
    var totalSkipped = 0;
    LocalDate earliestDate = null;
    LocalDate latestDate = null;

    for (var currencySeries : enabledCurrencies) {
      var targetCurrency = Currency.getInstance(currencySeries.getCurrencyCode());
      var startDate = determineStartDate(targetCurrency);
      var result = importExchangeRates(currencySeries, startDate);

      totalNew += result.newRecords();
      totalUpdated += result.updatedRecords();
      totalSkipped += result.skippedRecords();

      if (result.earliestExchangeRateDate() != null) {
        if (earliestDate == null || result.earliestExchangeRateDate().isBefore(earliestDate)) {
          earliestDate = result.earliestExchangeRateDate();
        }
      }

      if (result.latestExchangeRateDate() != null) {
        if (latestDate == null || result.latestExchangeRateDate().isAfter(latestDate)) {
          latestDate = result.latestExchangeRateDate();
        }
      }
    }

    log.info(
        "Import complete for all currencies: {} new, {} updated, {} skipped, earliest date: {},"
            + " latest date: {}",
        totalNew,
        totalUpdated,
        totalSkipped,
        earliestDate,
        latestDate);

    return new ExchangeRateImportResult(
        totalNew, totalUpdated, totalSkipped, earliestDate, latestDate);
  }

  private ExchangeRateImportResult importExchangeRates(
      CurrencySeries currencySeries, LocalDate startDate) {
    // TODO: Exchange Rates need a relationship to CurrencySeries
    var targetCurrency = Currency.getInstance(currencySeries.getCurrencyCode());

    try {
      log.info(
          "Importing exchange rates for {} (series: {}) startDate: {}",
          currencySeries.getCurrencyCode(),
          currencySeries.getProviderSeriesId(),
          startDate);

      var dateRateMap = exchangeRateProvider.getExchangeRates(currencySeries, startDate);

      if (dateRateMap.isEmpty()) {
        log.warn("No exchange rates provided for {}", currencySeries.getCurrencyCode());
        return new ExchangeRateImportResult(0, 0, 0, null, null);
      }

      var exchangeRates = buildExchangeRates(dateRateMap, targetCurrency);
      return saveExchangeRates(exchangeRates, targetCurrency);
    } catch (ServiceException serviceException) {
      throw serviceException;
    } catch (Exception e) {
      throw new ServiceException(
          "Failed to import exchange rates for "
              + currencySeries.getCurrencyCode()
              + ": "
              + e.getMessage(),
          e);
    }
  }

  private LocalDate determineStartDate(Currency targetCurrency) {
    // Get the most recent exchange rate date from database
    var mostRecent =
        exchangeRateRepository.findTopByBaseCurrencyAndTargetCurrencyOrderByDateDesc(
            BASE_CURRENCY, targetCurrency);

    if (mostRecent.isEmpty()) {
      // Edge case 1: No data exists - import everything
      log.info("No existing exchange rates found - importing full history");
      return null;
    }

    // Normal case: Start from day after last stored rate
    var lastDate = mostRecent.get().getDate();
    var nextDate = lastDate.plusDays(1);

    log.info("Last exchange rate date: {}, starting import from: {}", lastDate, nextDate);

    return nextDate;
  }

  private List<ExchangeRate> buildExchangeRates(
      Map<LocalDate, BigDecimal> dateRateMap, Currency targetCurrency) {
    return dateRateMap.entrySet().stream()
        .map(rate -> buildExchangeRate(rate.getKey(), rate.getValue(), targetCurrency))
        .toList();
  }

  private ExchangeRate buildExchangeRate(LocalDate date, BigDecimal rate, Currency targetCurrency) {
    var rv = new ExchangeRate();
    rv.setBaseCurrency(BASE_CURRENCY);
    rv.setTargetCurrency(targetCurrency);
    rv.setDate(date);
    rv.setRate(rate);

    return rv;
  }

  private ExchangeRateImportResult saveExchangeRates(
      List<ExchangeRate> rates, Currency targetCurrency) {
    var newCount = 0;
    var updatedCount = 0;
    var skippedCount = 0;

    // Get the most recent exchange rate date from database, if empty this is an initial import
    var isInitialImport =
        exchangeRateRepository
            .findTopByBaseCurrencyAndTargetCurrencyOrderByDateDesc(BASE_CURRENCY, targetCurrency)
            .isEmpty();

    // if empty database just saveAll nothing to compare to
    if (isInitialImport) {
      exchangeRateRepository.saveAll(rates);
      newCount = rates.size();
    } else {
      for (ExchangeRate rate : rates) {
        var existing =
            exchangeRateRepository.findByBaseCurrencyAndTargetCurrencyAndDate(
                rate.getBaseCurrency(), rate.getTargetCurrency(), rate.getDate());

        if (existing.isEmpty()) {
          exchangeRateRepository.save(rate);
          newCount++;
        } else if (existing.get().getRate().compareTo(rate.getRate()) != 0) {
          var exchangeRate = existing.get();
          log.warn(
              "Warning, rate changed. Updating rate for date: {} old rate: {} new rate: {}",
              exchangeRate.getDate(),
              exchangeRate.getRate(),
              rate.getRate());

          exchangeRate.setRate(rate.getRate());
          exchangeRateRepository.save(exchangeRate);
          updatedCount++;
        } else {
          skippedCount++;
        }
      }
    }

    // Track earliest and latest dates
    var earliestDate =
        rates.stream().map(ExchangeRate::getDate).min(LocalDate::compareTo).orElse(null);
    var latestDate =
        rates.stream().map(ExchangeRate::getDate).max(LocalDate::compareTo).orElse(null);

    log.info(
        "Save complete: {} new, {} updated, {} skipped, earliest date: {}, latest date: {}",
        newCount,
        updatedCount,
        skippedCount,
        earliestDate,
        latestDate);

    return new ExchangeRateImportResult(
        newCount, updatedCount, skippedCount, earliestDate, latestDate);
  }
}
