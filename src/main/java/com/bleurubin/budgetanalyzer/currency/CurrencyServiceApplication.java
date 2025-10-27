package com.bleurubin.budgetanalyzer.currency;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

import com.bleurubin.budgetanalyzer.currency.config.CurrencyServiceProperties;

@SpringBootApplication
@EnableConfigurationProperties(CurrencyServiceProperties.class)
public class CurrencyServiceApplication {

  public static void main(String[] args) {
    SpringApplication.run(CurrencyServiceApplication.class, args);
  }
}
