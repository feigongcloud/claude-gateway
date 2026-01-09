package com.vcc.gateway.service;

import com.vcc.gateway.config.GwProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Service
public class UpstreamClient {
  private static final Logger log = LoggerFactory.getLogger(UpstreamClient.class);

  private final WebClient webClient;
  private final GwProperties properties;
  private final KeyPool keyPool;

  public UpstreamClient(WebClient.Builder builder, GwProperties properties, KeyPool keyPool) {
    this.webClient = builder.baseUrl(properties.getUpstreamBaseUrl()).build();
    this.properties = properties;
    this.keyPool = keyPool;
  }

  public Mono<ClientResponse> forward(byte[] body, boolean stream) {
    String apiKey = keyPool.nextKey();
    // Debug: log key info (remove in production)
    log.info("Using API key: {}... (length={})",
        apiKey.length() > 10 ? apiKey.substring(0, 10) : "SHORT", apiKey.length());
    return webClient
        .post()
        .uri("/v1/messages")
        .header("x-api-key", apiKey)
        .header("anthropic-version", properties.getAnthropicVersion())
        .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
        .header(
            HttpHeaders.ACCEPT, stream ? MediaType.TEXT_EVENT_STREAM_VALUE : MediaType.APPLICATION_JSON_VALUE)
        .body(BodyInserters.fromValue(body))
        .exchangeToMono(clientResponse -> {
          HttpStatusCode status = clientResponse.statusCode();
          if (status.isError()) {
            return clientResponse.bodyToMono(String.class)
                .doOnNext(errorBody -> log.error("Upstream error {}: {}", status.value(), errorBody))
                .then(Mono.just(clientResponse));
          }
          return Mono.just(clientResponse);
        });
  }
}
