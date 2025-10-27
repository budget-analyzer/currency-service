package com.bleurubin.budgetanalyzer.currency;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Import;

import com.bleurubin.budgetanalyzer.currency.config.CurrencyServiceProperties;
import com.bleurubin.service.api.DefaultApiExceptionHandler;

@SpringBootApplication
@EnableConfigurationProperties(CurrencyServiceProperties.class)
@Import(DefaultApiExceptionHandler.class)
public class CurrencyServiceApplication {

  public static void main(String[] args) {
    SpringApplication.run(CurrencyServiceApplication.class, args);
  }
}
