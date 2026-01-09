package com.vcc.gateway;

import com.vcc.gateway.config.GwProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(GwProperties.class)
public class Application {
  public static void main(String[] args) {
    SpringApplication.run(Application.class, args);
  }
}
