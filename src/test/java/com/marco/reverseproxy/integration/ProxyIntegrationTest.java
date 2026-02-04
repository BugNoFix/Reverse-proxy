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
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ProxyIntegrationTest {

    @LocalServerPort
    private int port;

    private WebTestClient webTestClient;

    private static MockWebServer mockBackend1;
    private static MockWebServer mockBackend2;

    @Value("${proxy.services[0].hosts[0].port}")
    private int backend1Port;

    @Value("${proxy.services[0].hosts[1].port}")
    private int backend2Port;

    @BeforeAll
    void setupBackends() {
        try {
            if (mockBackend1 == null) {
                    mockBackend1 = new MockWebServer();
                    mockBackend1.start(backend1Port);
            }
            if (mockBackend2 == null) {
                    mockBackend2 = new MockWebServer();
                    mockBackend2.start(backend2Port);
            }
        } catch (Exception e) {
                throw new IllegalStateException("Failed to start mock backends", e);
        }
    }

    @AfterAll
        static void shutdownBackends() throws Exception {
        if (mockBackend1 != null) {
            mockBackend1.shutdown();
        }
        if (mockBackend2 != null) {
            mockBackend2.shutdown();
        }
    }

    @BeforeEach
    void setup() {
        // Initialize WebTestClient with timeout
        webTestClient = WebTestClient
                .bindToServer()
                .baseUrl("http://localhost:" + port)
                .responseTimeout(java.time.Duration.ofSeconds(5))
                .build();
    }

    @Test
    @Order(1)
    @DisplayName("Should proxy GET request to backend")
    void shouldProxyGetRequest() {
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
    @Order(2)
    @DisplayName("Should proxy POST request with body")
    void shouldProxyPostRequest() throws InterruptedException {
        // Setup mock response
        mockBackend1.enqueue(new MockResponse()
                .setResponseCode(201)
                .setBody("{\"id\":123}")
                .addHeader("Content-Type", "application/json"));

        String requestBody = "{\"name\":\"test\"}";

        // Make request through proxy
        webTestClient.post()
                .uri("/api/users")
                .header("Host", "test.example.com")
                .header("Content-Type", "application/json")
                .bodyValue(requestBody)
                .exchange()
                .expectStatus().isCreated()
                .expectBody(String.class)
                .isEqualTo("{\"id\":123}");

    }

    @Test
    @Order(3)
    @DisplayName("Should distribute requests with round-robin")
    void shouldDistributeWithRoundRobin() {
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
    @Order(4)
    @DisplayName("Should cache GET requests with Cache-Control")
        void shouldCacheGetRequests() throws InterruptedException {
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
    @Order(5)
    @DisplayName("Should handle 304 Not Modified with ETag")
    void shouldHandle304NotModified() throws InterruptedException {
        // First request - get fresh response with ETag
        mockBackend1.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody("{\"data\":\"etag-test\"}")
                .addHeader("Cache-Control", "max-age=0")
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
    @Order(6)
    @DisplayName("Should preserve query parameters")
    void shouldPreserveQueryParameters() throws InterruptedException {
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
    @Order(7)
    @DisplayName("Should preserve custom headers")
    void shouldPreserveCustomHeaders() throws InterruptedException {
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
    @Order(8)
    @DisplayName("Should handle backend errors gracefully")
    void shouldHandleBackendErrors() {
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

    @Test
    @Order(9)
    @DisplayName("Should return 404 for unknown domain")
        void shouldReturn404ForUnknownDomain() throws InterruptedException {
        webTestClient.get()
                .uri("/api/test")
                .header("Host", "unknown.example.com")
                .exchange()
                .expectStatus().isNotFound();

        // Verify no backend was called
        assertThat(mockBackend1.takeRequest(200, TimeUnit.MILLISECONDS)).isNull();
        assertThat(mockBackend2.takeRequest(200, TimeUnit.MILLISECONDS)).isNull();
    }
}
