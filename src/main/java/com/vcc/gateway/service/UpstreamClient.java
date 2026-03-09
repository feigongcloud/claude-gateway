package com.vcc.gateway.service;

import com.vcc.gateway.config.GwProperties;
import com.vcc.gateway.model.UsageContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Set;

@Service
public class UpstreamClient {
    private static final Logger log = LoggerFactory.getLogger(UpstreamClient.class);

    private final WebClient webClient;
    private final GwProperties properties;
    private final KeyPool keyPool;
    private final UsageTracker usageTracker;

    public UpstreamClient(WebClient.Builder builder, GwProperties properties, KeyPool keyPool,
                          UsageTracker usageTracker) {
        // Disable connection pooling to avoid stale connections
        reactor.netty.http.client.HttpClient httpClient = reactor.netty.http.client.HttpClient.create()
                .keepAlive(false);

        // Configure larger buffer size for WebClient to handle large upstream responses
        // Fixes: 500 Exceeded limit on max bytes to buffer : 262144
        ExchangeStrategies strategies = ExchangeStrategies.builder()
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(10 * 1024 * 1024)) // 10MB
                .build();

        this.webClient = builder
                .baseUrl(properties.getUpstreamBaseUrl())
                .clientConnector(new org.springframework.http.client.reactive.ReactorClientHttpConnector(httpClient))
                .exchangeStrategies(strategies)
                .build();
        this.properties = properties;
        this.keyPool = keyPool;
        this.usageTracker = usageTracker;
        log.info("UpstreamClient initialized with baseUrl={}, maxBufferSize=10MB", properties.getUpstreamBaseUrl());
    }

    /**
     * Forward request to upstream and write response directly to ServerHttpResponse.
     * Extracts usage data for tracking without blocking the response stream.
     */
    public Mono<Void> forwardAndWrite(byte[] body, boolean stream, ServerHttpResponse response,
                                       UsageContext usageContext) {
        String apiKey = keyPool.nextKey();
        String upstreamKeyId = maskKeyForId(apiKey);
        usageContext.setUpstreamKeyId(upstreamKeyId);

        log.info("[UPSTREAM] forwardAndWrite called: requestId={}, stream={}",
                usageContext.getRequestId(), stream);

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

                    // Capture status code for usage tracking
                    usageContext.setStatusCode(status.value());

                    // Set response status
                    response.setStatusCode(status);

                    HttpHeaders out = response.getHeaders();

                    // Copy headers (except hop-by-hop)
                    headers.forEach((name, values) -> {
                        if (isHopByHop(name)) return;
                        out.put(name, new ArrayList<>(values));
                    });

                    if (stream) {
                        log.info("[UPSTREAM] Handling STREAMING response: requestId={}",
                                usageContext.getRequestId());
                        response.getHeaders().set(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_EVENT_STREAM_VALUE);
                        response.getHeaders().set(HttpHeaders.CACHE_CONTROL, "no-cache");
                        response.getHeaders().set("X-Accel-Buffering", "no");

                        // Streaming: side-tap SSE events for usage extraction
                        return handleStreamingResponse(clientResponse, response, usageContext);
                    } else {
                        log.info("[UPSTREAM] Handling NON-STREAMING response: requestId={}, status={}",
                                usageContext.getRequestId(), status.value());
                        // Non-streaming: intercept body for usage extraction
                        return handleNonStreamingResponse(clientResponse, response, usageContext);
                    }
                })
                .doOnSubscribe(s -> log.info("[UPSTREAM] Request subscribed: requestId={}",
                        usageContext.getRequestId()))
                .doOnSuccess(v -> log.info("[UPSTREAM] ✓ forwardAndWrite COMPLETED: requestId={}",
                        usageContext.getRequestId()))
                .doOnError(e -> log.error("[UPSTREAM] ✗ forwardAndWrite ERROR: requestId={}, error={}",
                        usageContext.getRequestId(), e.getMessage(), e))
                .doFinally(signal -> log.info("[UPSTREAM] forwardAndWrite FINALLY: requestId={}, signal={}",
                        usageContext.getRequestId(), signal));
    }

    /**
     * Handle non-streaming response: collect body, extract usage, then write to client.
     */
    private Mono<Void> handleNonStreamingResponse(ClientResponse clientResponse,
                                                   ServerHttpResponse response,
                                                   UsageContext usageContext) {
        return clientResponse.bodyToMono(byte[].class)
            .doOnNext(bodyBytes -> log.info("[UPSTREAM] Received body: requestId={}, size={} bytes",
                    usageContext.getRequestId(), bodyBytes.length))
            .flatMap(bodyBytes -> {
                // Extract usage from response body
                UsageTracker.UsageData usage = usageTracker.parseNonStreamingUsage(bodyBytes);
                if (usage != null) {
                    log.info("[UPSTREAM] Extracted usage: requestId={}, msgId={}, input={}, output={}, total={}",
                            usageContext.getRequestId(), usage.msgId(), usage.inputTokens(), usage.outputTokens(),
                            usage.totalTokens());
                    usageContext.addInputTokens(usage.inputTokens());
                    usageContext.addOutputTokens(usage.outputTokens());
                    usageContext.setCacheCreationInputTokens(usage.cacheCreationInputTokens());
                    usageContext.setCacheReadInputTokens(usage.cacheReadInputTokens());
                    usageContext.setRawUsageJson(usage.rawJson());
                    usageContext.setMsgId(usage.msgId());
                } else {
                    log.warn("[UPSTREAM] No usage data found in response: requestId={}",
                            usageContext.getRequestId());
                }

                // Write body to client
                DataBuffer buffer = response.bufferFactory().wrap(bodyBytes);
                return response.writeWith(Mono.just(buffer))
                        .doOnSuccess(v -> log.info("[UPSTREAM] ✓ Response written to client: requestId={}",
                                usageContext.getRequestId()));
            });
    }

    /**
     * Handle streaming response: side-tap SSE events while passing through to client.
     * Uses a tee pattern to extract usage without blocking.
     */
    private Mono<Void> handleStreamingResponse(ClientResponse clientResponse,
                                                ServerHttpResponse response,
                                                UsageContext usageContext) {
        // SSE line buffer for parsing (not thread-safe, but only accessed in single stream)
        StringBuilder lineBuffer = new StringBuilder();

        Flux<DataBuffer> bodyFlux = clientResponse.bodyToFlux(DataBuffer.class)
            .doOnNext(buf -> {
                // Side-tap: extract text and look for usage events
                byte[] bytes = new byte[buf.readableByteCount()];
                buf.read(bytes);
                buf.readPosition(0);  // Reset position for downstream

                String chunk = new String(bytes, StandardCharsets.UTF_8);
                extractUsageFromSseChunk(chunk, lineBuffer, usageContext);
            })
            .doOnComplete(() -> log.debug("Upstream body completed"))
            .doOnError(e -> log.error("Upstream body error: {}", e.getMessage()));

        // Streaming: flush each chunk immediately
        return response.writeAndFlushWith(bodyFlux.map(Mono::just));
    }

    /**
     * Extract usage from SSE chunk, handling partial lines across chunks.
     */
    private void extractUsageFromSseChunk(String chunk, StringBuilder lineBuffer,
                                           UsageContext usageContext) {
        lineBuffer.append(chunk);
        String content = lineBuffer.toString();

        // Process complete lines
        int lastNewline = content.lastIndexOf('\n');
        if (lastNewline >= 0) {
            String completeLines = content.substring(0, lastNewline);
            lineBuffer.setLength(0);
            lineBuffer.append(content.substring(lastNewline + 1));

            for (String line : completeLines.split("\n")) {
                String sseData = usageTracker.extractSseData(line);
                if (sseData == null) continue;

                // Try to parse message_start for input tokens and message ID
                UsageTracker.UsageData startUsage = usageTracker.parseMessageStartUsage(sseData);
                if (startUsage != null) {
                    usageContext.addInputTokens(startUsage.inputTokens());
                    usageContext.setCacheCreationInputTokens(startUsage.cacheCreationInputTokens());
                    usageContext.setCacheReadInputTokens(startUsage.cacheReadInputTokens());
                    usageContext.setMsgId(startUsage.msgId());
                    log.trace("Extracted msgId={}, input_tokens={} from message_start",
                        startUsage.msgId(), startUsage.inputTokens());
                    continue;
                }

                // Try to parse message_delta for output tokens
                UsageTracker.UsageData deltaUsage = usageTracker.parseMessageDeltaUsage(sseData);
                if (deltaUsage != null) {
                    usageContext.addOutputTokens(deltaUsage.outputTokens());
                    usageContext.setRawUsageJson(deltaUsage.rawJson());
                    log.trace("Extracted output_tokens={} from message_delta",
                        deltaUsage.outputTokens());
                }
            }
        }
    }

    private String maskKeyForId(String key) {
        if (key == null || key.length() < 12) return "unknown";
        return key.substring(0, 8) + "..." + key.substring(key.length() - 4);
    }

    private static final Set<String> HOP_BY_HOP = Set.of(
            "connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
            "te", "trailer", "transfer-encoding", "upgrade"
    );

    private static boolean isHopByHop(String name) {
        return HOP_BY_HOP.contains(name.toLowerCase(Locale.ROOT));
    }

}
