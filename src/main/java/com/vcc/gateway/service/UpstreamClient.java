package com.vcc.gateway.service;

import com.vcc.gateway.config.GwProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.Locale;
import java.util.Set;

@Service
public class UpstreamClient {
    private static final Logger log = LoggerFactory.getLogger(UpstreamClient.class);

    private final WebClient webClient;
    private final GwProperties properties;
    private final KeyPool keyPool;

    public UpstreamClient(WebClient.Builder builder, GwProperties properties, KeyPool keyPool) {
        // Disable connection pooling to avoid stale connections
        reactor.netty.http.client.HttpClient httpClient = reactor.netty.http.client.HttpClient.create()
                .keepAlive(false);

        this.webClient = builder
                .baseUrl(properties.getUpstreamBaseUrl())
                .clientConnector(new org.springframework.http.client.reactive.ReactorClientHttpConnector(httpClient))
                .build();
        this.properties = properties;
        this.keyPool = keyPool;
        log.info("UpstreamClient initialized with baseUrl={}", properties.getUpstreamBaseUrl());
    }

    /**
     * Forward request to upstream and write response directly to ServerHttpResponse.
     * This ensures the body stream is consumed within the exchange context.
     */
    public Mono<Void> forwardAndWrite(byte[] body, boolean stream, ServerHttpResponse response) {
        String apiKey = keyPool.nextKey();
        log.debug("Using API key: {}... (length={})",
                apiKey.length() > 10 ? apiKey.substring(0, 10) : "SHORT", apiKey.length());

        return webClient
                .post()
                .uri("/v1/messages")
                .header("x-api-key", apiKey)
                .header("anthropic-version", properties.getAnthropicVersion())
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .header(HttpHeaders.ACCEPT, stream ? MediaType.TEXT_EVENT_STREAM_VALUE : MediaType.APPLICATION_JSON_VALUE)
                .body(BodyInserters.fromValue(body))
                .exchangeToMono(clientResponse -> {
                    HttpStatusCode status = clientResponse.statusCode();
                    HttpHeaders headers = clientResponse.headers().asHttpHeaders();

                    // Set response status
                    response.setStatusCode(status);

                    HttpHeaders out=response.getHeaders();

                    // Copy headers (except transfer-encoding)
                    headers.forEach((name, values) -> {
//                        if (!HttpHeaders.TRANSFER_ENCODING.equalsIgnoreCase(name)) {
//                            response.getHeaders().addAll(name, values);
//                        }

                        if(isHopByHop(name)) return;
                        out.put(name,new ArrayList<>(values));
                    });

                    if (stream) {
                        response.getHeaders().set(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_EVENT_STREAM_VALUE);
                        response.getHeaders().set(HttpHeaders.CACHE_CONTROL, "no-cache");
                        response.getHeaders().set("X-Accel-Buffering", "no");


                    }

                    // Get body flux and write to response within this context
                    Flux<DataBuffer> bodyFlux = clientResponse.bodyToFlux(DataBuffer.class)
                            .doOnNext(buf -> log.trace("Received {} bytes", buf.readableByteCount()))
                            .doOnComplete(() -> log.debug("Upstream body completed"))
                            .doOnError(e -> log.error("Upstream body error: {}", e.getMessage()));

                    if (stream) {
                        // Streaming: flush each chunk immediately
                        return response.writeAndFlushWith(bodyFlux.map(Mono::just));
                    } else {
                        // Non-streaming: write all at once
                        return response.writeWith(bodyFlux);
                    }
                });
    }

    private static final Set<String> HOP_BY_HOP = Set.of(
            "connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
            "te", "trailer", "transfer-encoding", "upgrade"
    );

    private static boolean isHopByHop(String name) {
        return HOP_BY_HOP.contains(name.toLowerCase(Locale.ROOT));
    }

}
