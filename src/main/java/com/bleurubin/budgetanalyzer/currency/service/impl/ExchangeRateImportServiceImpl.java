package com.bleurubin.budgetanalyzer.currency.service.impl;

import java.io.InputStream;
import java.time.LocalDate;
import java.util.Currency;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bleurubin.budgetanalyzer.currency.client.FredClient;
import com.bleurubin.budgetanalyzer.currency.domain.ExchangeRate;
import com.bleurubin.budgetanalyzer.currency.repository.ExchangeRateRepository;
import com.bleurubin.budgetanalyzer.currency.service.CurrencyServiceError;
import com.bleurubin.budgetanalyzer.currency.service.ExchangeRateImportService;
import com.bleurubin.budgetanalyzer.currency.service.ExchangeRateService;
import com.bleurubin.core.csv.CsvData;
import com.bleurubin.core.csv.CsvParser;
import com.bleurubin.service.exception.BusinessException;
import com.bleurubin.service.exception.ServiceException;
import com.bleurubin.service.exception.ServiceUnavailableException;

@Service
public class ExchangeRateImportServiceImpl implements ExchangeRateImportService {

  private static final Logger log = LoggerFactory.getLogger(ExchangeRateImportServiceImpl.class);

  private static final String FRED_FORMAT = "FRED";

  // The base currency will always be USD but i wanted to keep the column in the database
  // in case the design needs to change in the future.
  private static final Currency BASE_CURRENCY = Currency.getInstance("USD");

  private static final Currency THB = Currency.getInstance("THB");

  // if we decide to support multiple currencies we'll create a map of currency -> seriesId
  private static final String THB_SERIES_ID = "DEXTHUS";

  private final ExchangeRateService exchangeRateService;

  private final ExchangeRateRepository exchangeRateRepository;

  private final CsvParser csvParser;

  private final FredClient fredClient;

  private final CsvExchangeRateMapper exchangeRateMapper = new CsvExchangeRateMapper();

  public ExchangeRateImportServiceImpl(
      ExchangeRateService exchangeRateService,
      ExchangeRateRepository exchangeRateRepository,
      CsvParser csvParser,
      FredClient fredClient) {
    this.exchangeRateService = exchangeRateService;
    this.exchangeRateRepository = exchangeRateRepository;
    this.csvParser = csvParser;
    this.fredClient = fredClient;
  }

  @Override
  public boolean hasExchangeRateData() {
    return exchangeRateRepository.count() > 0;
  }

  @Override
  @Transactional
  public List<ExchangeRate> importExchangeRates(
      InputStream inputStream, String fileName, Currency targetCurrency) {
    try {
      log.info("Importing csv file: {} targetCurrency: {}", fileName, targetCurrency);

      var csvData = csvParser.parseCsvInputStream(inputStream, fileName, FRED_FORMAT);
      var importedExchangeRates = createExchangeRates(csvData, targetCurrency);

      log.info(
          "Successfully imported: {} total exchangeRates fileName: {} targetCurrency {}",
          importedExchangeRates.size(),
          fileName,
          targetCurrency);

      return importedExchangeRates;
    } catch (BusinessException businessException) {
      throw businessException;
    } catch (Exception e) {
      throw new BusinessException(
          "Failed to import exchange rates: " + e.getMessage(),
          CurrencyServiceError.CSV_PARSING_ERROR.name(),
          e);
    }
  }

  @Override
  @Transactional
  public List<ExchangeRate> importLatestExchangeRates() {
    var startDate = determineStartDate();
    log.info("Importing exchange rates starting from: {}", startDate);

    try {
      var resource = fredClient.getExchangeRateDataAsResource(THB_SERIES_ID, startDate);
      return importExchangeRatesFromResource(resource, THB);
    } catch (ServiceException se) {
      throw se;
    } catch (Exception e) {
      throw new ServiceUnavailableException("Error importing exchange rates: " + e.getMessage(), e);
    }
  }

  private List<ExchangeRate> createExchangeRates(CsvData csvData, Currency targetCurrency) {
    // some rows in the FRED files have dates and no rates, mapper returns optional so we can filter
    // those out
    var exchangeRates =
        csvData.rows().stream()
            .map(csvRow -> exchangeRateMapper.map(csvRow, BASE_CURRENCY, targetCurrency))
            .flatMap(Optional::stream)
            .toList();

    return exchangeRateService.createExchangeRates(exchangeRates);
  }

  private LocalDate determineStartDate() {
    // Get the most recent exchange rate date from database
    var mostRecent = exchangeRateRepository.findTopByOrderByDateDesc();

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
}
