package com.bleurubin.budgetanalyzer.currency.config;

import java.util.Map;

import jakarta.validation.Valid;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties(prefix = "budget-analyzer")
@Validated
public record CurrencyServiceProperties(@Valid Map<String, CsvConfig> csvConfigMap) {}
