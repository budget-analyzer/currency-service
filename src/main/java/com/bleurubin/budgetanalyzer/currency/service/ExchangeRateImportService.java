package com.bleurubin.budgetanalyzer.currency.service;

import java.io.IOException;
import java.io.InputStream;
import java.util.Currency;
import java.util.List;

import org.springframework.core.io.Resource;
import org.springframework.web.multipart.MultipartFile;

import com.bleurubin.budgetanalyzer.currency.domain.ExchangeRate;
import com.bleurubin.service.exception.BusinessException;

public interface ExchangeRateImportService {

  boolean hasExchangeRateData();

  List<ExchangeRate> importExchangeRates(
      InputStream inputStream, String fileName, Currency targetCurrency);

  List<ExchangeRate> importLatestExchangeRates();

  default List<ExchangeRate> importExchangeRates(MultipartFile file, Currency targetCurrency) {
    try (InputStream inputStream = file.getInputStream()) {
      return importExchangeRates(inputStream, file.getOriginalFilename(), targetCurrency);
    } catch (IOException e) {
      throw new BusinessException(
          "Failed to import exchange rates: " + e.getMessage(),
          CurrencyServiceError.CSV_PARSING_ERROR.name(),
          e);
    }
  }

  default List<ExchangeRate> importExchangeRatesFromResource(
      Resource resource, Currency targetCurrency) {
    try (InputStream inputStream = resource.getInputStream()) {
      return importExchangeRates(inputStream, resource.getFilename(), targetCurrency);
    } catch (IOException e) {
      throw new BusinessException(
          "Failed to import exchange rates: " + e.getMessage(),
          CurrencyServiceError.CSV_PARSING_ERROR.name(),
          e);
    }
  }
}
