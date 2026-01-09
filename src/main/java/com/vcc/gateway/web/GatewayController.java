package com.vcc.gateway.web;

import com.vcc.gateway.model.TenantContext;
import com.vcc.gateway.service.RateLimiter;
import com.vcc.gateway.service.TenantService;
import com.vcc.gateway.service.UpstreamClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
public class GatewayController {
  private static final Logger log = LoggerFactory.getLogger(GatewayController.class);

  private final TenantService tenantService;
  private final RateLimiter rateLimiter;
  private final UpstreamClient upstreamClient;
  private final ObjectMapper objectMapper;

  public GatewayController(
      TenantService tenantService, RateLimiter rateLimiter, UpstreamClient upstreamClient, ObjectMapper objectMapper) {
    this.tenantService = tenantService;
    this.rateLimiter = rateLimiter;
    this.upstreamClient = upstreamClient;
    this.objectMapper = objectMapper;
  }

  @PostMapping(path = "/anthropic/v1/messages", consumes = MediaType.APPLICATION_JSON_VALUE)
  public Mono<Void> proxy(
      ServerHttpRequest request, ServerHttpResponse response, @RequestBody byte[] body) {
    String requestId = UUID.randomUUID().toString();
    TenantContext tenant = tenantService.resolve(request.getHeaders());
    boolean stream = isStream(body);

    if (!rateLimiter.tryConsume(tenant.getTenantId())) {
      throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Rate limit exceeded");
    }

    return upstreamClient
        .forward(body, stream)
        .flatMap(
            clientResponse -> {
              response.setStatusCode(clientResponse.statusCode());
              response.getHeaders().putAll(clientResponse.headers().asHttpHeaders());
              if (stream) {
                response.getHeaders().set(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_EVENT_STREAM_VALUE);
              }
              Flux<DataBuffer> bodyFlux = clientResponse.bodyToFlux(DataBuffer.class);
              return response.writeWith(bodyFlux);
            })
        .doOnTerminate(
            () ->
                log.info(
                    "requestId={} tenantId={} stream={} status={}",
                    requestId,
                    tenant.getTenantId(),
                    stream,
                    response.getStatusCode() != null ? response.getStatusCode().value() : "n/a"))
        .contextWrite(ctx -> ctx.put(TenantContextKeys.TENANT_CTX, tenant));
  }

  private boolean isStream(byte[] body) {
    try {
      JsonNode root = objectMapper.readTree(body);
      JsonNode streamNode = root.get("stream");
      return streamNode != null && streamNode.isBoolean() && streamNode.booleanValue();
    } catch (IOException ex) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid JSON body", ex);
    }
  }
}
