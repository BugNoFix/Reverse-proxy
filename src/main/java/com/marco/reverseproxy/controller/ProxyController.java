package com.marco.reverseproxy.controller;

import com.marco.reverseproxy.service.ProxyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;

/**
 * Fully reactive controller that receives all incoming requests and forwards them
 * Uses Spring WebFlux with Netty for non-blocking I/O
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class ProxyController {

    private final ProxyService proxyService;
    
    // Maximum request body size: 10MB
    private static final int MAX_BODY_SIZE = 10 * 1024 * 1024;
    private static final DefaultDataBufferFactory bufferFactory = new DefaultDataBufferFactory();

    /**
     * Catch-all endpoint that handles all HTTP methods and paths (fully reactive)
     */
    @RequestMapping("/**")
    public Mono<ResponseEntity<byte[]>> handleRequest(ServerHttpRequest request) {
        // Get client IP (considering X-Forwarded-For if behind another proxy)
        String clientIp = request.getRemoteAddress() != null 
            ? request.getRemoteAddress().getAddress().getHostAddress() 
            : "unknown";
            
        log.info("Received {} request to {} from {}", 
                request.getMethod(), 
                request.getPath(), 
                clientIp);

        // Read request body reactively (non-blocking)
        Flux<DataBuffer> body = request.getBody();
        
        return DataBufferUtils.join(body, MAX_BODY_SIZE)
                .defaultIfEmpty(bufferFactory.allocateBuffer(0))
                .flatMap(dataBuffer -> {
                    try {
                        // Convert DataBuffer to String
                        byte[] bytes = new byte[dataBuffer.readableByteCount()];
                        dataBuffer.read(bytes);
                        DataBufferUtils.release(dataBuffer);
                        String requestBody = new String(bytes, StandardCharsets.UTF_8);
                        
                        // Forward the request
                        return proxyService.forwardRequest(request, requestBody);
                        
                    } catch (Exception e) {
                        log.error("Error processing request body", e);
                        return Mono.just(ResponseEntity.badRequest()
                                .body("Error processing request body".getBytes()));
                    }
                })
                .onErrorResume(error -> {
                    if (error.getMessage() != null && error.getMessage().contains("exceeded")) {
                        log.warn("Request body too large from {}", clientIp);
                        return Mono.just(ResponseEntity.status(413)
                                .body("Request body too large. Max size: 10MB".getBytes()));
                    }
                    log.error("Error handling request", error);
                    return Mono.just(ResponseEntity.internalServerError()
                            .body("Internal server error".getBytes()));
                });
    }
}
