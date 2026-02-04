package com.marco.reverseproxy.unit.controller;

import com.marco.reverseproxy.controller.ProxyController;
import com.marco.reverseproxy.service.ProxyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ProxyControllerTest {

    private ProxyController proxyController;
    private ProxyService proxyService;
    private DefaultDataBufferFactory bufferFactory;

    @BeforeEach
    void setUp() {
        proxyService = mock(ProxyService.class);
        proxyController = new ProxyController(proxyService);
        bufferFactory = new DefaultDataBufferFactory();
    }

    @Test
    void handleRequest_shouldHandleUnknownRemoteAddress() {
        ServerHttpRequest request = MockServerHttpRequest
                .get("/api/test")
                .header("Host", "test.example.com")
                .build();

        ResponseEntity<byte[]> expectedResponse = ResponseEntity.ok().build();
        when(proxyService.forwardRequest(any(ServerHttpRequest.class), anyString()))
                .thenReturn(Mono.just(expectedResponse));

        Mono<ResponseEntity<byte[]>> result = proxyController.handleRequest(request);

        StepVerifier.create(result)
                .assertNext(response -> {
                    assertEquals(HttpStatus.OK, response.getStatusCode());
                })
                .verifyComplete();
    }

    @Test
    void handleRequest_shouldHandleServiceError() {
        ServerHttpRequest request = MockServerHttpRequest
                .get("/api/test")
                .header("Host", "test.example.com")
                .remoteAddress(new InetSocketAddress("127.0.0.1", 12345))
                .build();

        when(proxyService.forwardRequest(any(ServerHttpRequest.class), anyString()))
                .thenReturn(Mono.error(new RuntimeException("Service error")));

        Mono<ResponseEntity<byte[]>> result = proxyController.handleRequest(request);

        StepVerifier.create(result)
                .assertNext(response -> {
                    assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
                })
                .verifyComplete();
    }

    @Test
    void handleRequest_shouldRejectBodyExceedingMaxSize() {
        // Create a body exceeding the limit (10MB)
        byte[] tooLargeContent = new byte[11 * 1024 * 1024]; // 11MB
        DataBuffer dataBuffer = bufferFactory.wrap(tooLargeContent);
        
        ServerHttpRequest request = MockServerHttpRequest
                .post("/api/upload")
                .header("Host", "test.example.com")
                .remoteAddress(new InetSocketAddress("127.0.0.1", 12345))
                .body(Flux.just(dataBuffer));

        Mono<ResponseEntity<byte[]>> result = proxyController.handleRequest(request);

        StepVerifier.create(result)
                .assertNext(response -> {
                    assertEquals(413, response.getStatusCode().value());
                    assertTrue(new String(response.getBody()).contains("Request body too large"));
                })
                .verifyComplete();
    }


    @Test
    void handleRequest_shouldPreserveResponseHeaders() {
        ServerHttpRequest request = MockServerHttpRequest
                .get("/api/test")
                .header("Host", "test.example.com")
                .remoteAddress(new InetSocketAddress("127.0.0.1", 12345))
                .build();

        ResponseEntity<byte[]> expectedResponse = ResponseEntity.ok()
                .header("X-Custom-Header", "custom-value")
                .header("Content-Type", "application/json")
                .body("test".getBytes());
        
        when(proxyService.forwardRequest(any(ServerHttpRequest.class), anyString()))
                .thenReturn(Mono.just(expectedResponse));

        Mono<ResponseEntity<byte[]>> result = proxyController.handleRequest(request);

        StepVerifier.create(result)
                .assertNext(response -> {
                    assertEquals(HttpStatus.OK, response.getStatusCode());
                    assertEquals("custom-value", response.getHeaders().getFirst("X-Custom-Header"));
                    assertEquals("application/json", response.getHeaders().getFirst("Content-Type"));
                })
                .verifyComplete();
    }

}
