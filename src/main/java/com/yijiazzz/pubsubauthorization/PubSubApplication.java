package com.yijiazzz.pubsubauthorization;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.web.filter.CommonsRequestLoggingFilter;

@SpringBootApplication
public class PubSubApplication {

  @Bean
  public CommonsRequestLoggingFilter logFilter() {
    CommonsRequestLoggingFilter filter = new CommonsRequestLoggingFilter();
    filter.setIncludeQueryString(true);
    filter.setIncludePayload(true);
    filter.setMaxPayloadLength(10000);
    filter.setIncludeHeaders(true);
    filter.setAfterMessagePrefix("REQUEST DATA : ");
    return filter;
  }

  public static void main(String[] args) {
    System.out.println("PubSubApplication starting...");
    String port = System.getenv("PORT");
    if (port == null) {
      port = "8080";
    }
    System.setProperty("server.port", port);
    SpringApplication.run(PubSubApplication.class, args);
  }
}
