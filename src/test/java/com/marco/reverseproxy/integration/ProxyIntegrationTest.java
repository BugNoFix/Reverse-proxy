package com.marco.reverseproxy.integration;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for the reverse proxy.
 * Tests the complete request flow from client to backend servers.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class ProxyIntegrationTest {

    @LocalServerPort
    private int port;

    private WebTestClient webTestClient;

    private MockWebServer mockBackend1;
    private MockWebServer mockBackend2;

    @Value("${proxy.services[0].hosts[0].port}")
    private int backend1Port;

    @Value("${proxy.services[0].hosts[1].port}")
    private int backend2Port;

    @BeforeEach
    void setup() {
        try {
            mockBackend1 = new MockWebServer();
            mockBackend1.start(backend1Port);

            mockBackend2 = new MockWebServer();
            mockBackend2.start(backend2Port);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to start mock backends", e);
        }

        // Initialize WebTestClient with timeout
        webTestClient = WebTestClient
                .bindToServer()
                .baseUrl("http://localhost:" + port)
                .responseTimeout(java.time.Duration.ofSeconds(5))
                .build();
    }

    @AfterEach
    void shutdownBackends() throws Exception {
        if (mockBackend1 != null) {
                mockBackend1.shutdown();
                mockBackend1 = null;
        }
        if (mockBackend2 != null) {
                mockBackend2.shutdown();
                mockBackend2 = null;
        }
    }

    @Test
    void verifyProxyForwardsGetRequestSuccessfully() {
        // Setup mock response
        mockBackend1.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody("{\"status\":\"ok\"}")
                .addHeader("Content-Type", "application/json"));

        // Make request through proxy
        webTestClient.get()
                .uri("/api/test")
                .header("Host", "test.example.com")
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .isEqualTo("{\"status\":\"ok\"}");
    }


    @Test
    void verifyLoadBalancerDistributesTrafficInRoundRobin() {
        // Setup mock responses for both backends
        mockBackend1.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody("{\"server\":\"backend1\"}"));
        
        mockBackend2.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody("{\"server\":\"backend2\"}"));
        
        mockBackend1.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody("{\"server\":\"backend1\"}"));

        // Make three requests
        webTestClient.get()
                .uri("/api/roundrobin/1")
                .header("Host", "test.example.com")
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class).isEqualTo("{\"server\":\"backend1\"}");

        webTestClient.get()
                .uri("/api/roundrobin/2")
                .header("Host", "test.example.com")
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class).isEqualTo("{\"server\":\"backend2\"}");

        webTestClient.get()
                .uri("/api/roundrobin/3")
                .header("Host", "test.example.com")
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class).isEqualTo("{\"server\":\"backend1\"}");

    }

    @Test
    void verifyCacheHitsDoNotInvokeBackend() throws InterruptedException {
        // First request - cache miss
        mockBackend1.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody("{\"data\":\"cached\"}")
                .addHeader("Cache-Control", "max-age=60")
                .addHeader("ETag", "\"abc123\""));

        webTestClient.get()
                .uri("/api/cached")
                .header("Host", "test.example.com")
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .isEqualTo("{\"data\":\"cached\"}");

        // Second request - should be served from cache (backend not called)
        webTestClient.get()
                .uri("/api/cached")
                .header("Host", "test.example.com")
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .isEqualTo("{\"data\":\"cached\"}");

        // Verify backend was only called once
        RecordedRequest firstRequest = mockBackend1.takeRequest(200, TimeUnit.MILLISECONDS);
        assertThat(firstRequest).isNotNull();
        assertThat(mockBackend1.takeRequest(200, TimeUnit.MILLISECONDS)).isNull();
        assertThat(mockBackend2.takeRequest(200, TimeUnit.MILLISECONDS)).isNull();
    }

    @Test
    void verify304WithETag() throws InterruptedException {
        // First request - get fresh response with ETag
        mockBackend1.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody("{\"data\":\"etag-test\"}")
                .addHeader("Cache-Control", "max-age=0; must-revalidate")
                .addHeader("ETag", "\"xyz789\""));

        // Second request - backend returns 304 Not Modified
        mockBackend2.enqueue(new MockResponse()
                .setResponseCode(304)
                .addHeader("ETag", "\"xyz789\"")
                .addHeader("Cache-Control", "max-age=60"));

        webTestClient.get()
                .uri("/api/etag-test")
                .header("Host", "test.example.com")
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .isEqualTo("{\"data\":\"etag-test\"}");

        webTestClient.get()
                .uri("/api/etag-test")
                .header("Host", "test.example.com")
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .isEqualTo("{\"data\":\"etag-test\"}");

        RecordedRequest firstRequest = mockBackend1.takeRequest(200, TimeUnit.MILLISECONDS);
        RecordedRequest secondRequest = mockBackend2.takeRequest(200, TimeUnit.MILLISECONDS);

        assertThat(firstRequest).isNotNull();
        assertThat(secondRequest).isNotNull();
        assertThat(secondRequest.getHeader("If-None-Match")).isEqualTo("\"xyz789\"");
    }

    @Test
    void verifyQueryParametersAreForwardedIntact() throws InterruptedException {
        mockBackend1.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody("{\"status\":\"ok\"}"));

        webTestClient.get()
                .uri("/api/search?q=test&limit=10")
                .header("Host", "test.example.com")
                .exchange()
                .expectStatus().isOk();

        RecordedRequest request = mockBackend1.takeRequest();
        assertThat(request.getPath()).isEqualTo("/api/search?q=test&limit=10");
    }

    @Test
    void verifyCustomHeadersAreForwarded() throws InterruptedException {
        mockBackend1.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody("{}"));

        webTestClient.get()
                .uri("/api/test")
                .header("Host", "test.example.com")
                .header("X-Custom-Header", "custom-value")
                .header("Authorization", "Bearer token123")
                .exchange()
                .expectStatus().isOk();

        RecordedRequest request = mockBackend1.takeRequest();
        assertThat(request.getHeader("X-Custom-Header")).isEqualTo("custom-value");
        assertThat(request.getHeader("Authorization")).isEqualTo("Bearer token123");
    }

    @Test
    void verifyHandleInternalServerError() {
        mockBackend1.enqueue(new MockResponse()
                .setResponseCode(500)
                .setBody("{\"error\":\"Internal Server Error\"}"));

        webTestClient.get()
                .uri("/api/error")
                .header("Host", "test.example.com")
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.BAD_GATEWAY)
                .expectBody(String.class)
                .isEqualTo("Bad Gateway: Downstream service error");

    }
}
