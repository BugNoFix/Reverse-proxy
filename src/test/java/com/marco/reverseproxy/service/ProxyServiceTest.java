package com.marco.reverseproxy.service;

import com.marco.reverseproxy.cache.CacheService;
import com.marco.reverseproxy.cache.CachedResponse;
import com.marco.reverseproxy.config.ProxyConfiguration;
import com.marco.reverseproxy.loadbalancer.LoadBalancer;
import com.marco.reverseproxy.loadbalancer.LoadBalancerFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ProxyServiceTest {

    private ProxyService proxyService;
    private ServiceRegistry serviceRegistry;
    private ProxyConfiguration proxyConfiguration;
    
    @Mock
    private LoadBalancerFactory loadBalancerFactory;
    
    @Mock
    private CacheService cacheService;
    
    @Mock
    private WebClient webClient;
    
    @Mock
    private WebClient.RequestBodyUriSpec requestBodyUriSpec;
    
    @Mock
    private WebClient.RequestBodySpec requestBodySpec;
    
    @Mock
    private WebClient.RequestHeadersSpec requestHeadersSpec;
    
    @Mock
    private WebClient.ResponseSpec responseSpec;

    @BeforeEach
    void setUp() {
        // Setup configuration
        proxyConfiguration = new ProxyConfiguration();
        List<ProxyConfiguration.ServiceConfig> services = new ArrayList<>();
        
        ProxyConfiguration.ServiceConfig service = new ProxyConfiguration.ServiceConfig();
        service.setName("test-service");
        service.setDomain("test.example.com");
        service.setStrategy("round-robin");
        
        List<ProxyConfiguration.HostConfig> hosts = new ArrayList<>();
        ProxyConfiguration.HostConfig host = new ProxyConfiguration.HostConfig();
        host.setAddress("localhost");
        host.setPort(9090);
        hosts.add(host);
        
        service.setHosts(hosts);
        services.add(service);
        
        proxyConfiguration.setServices(services);
        
        // Setup mocks
        serviceRegistry = spy(new ServiceRegistry(proxyConfiguration));
        
        LoadBalancer mockLoadBalancer = mock(LoadBalancer.class);
        lenient().when(mockLoadBalancer.selectHost(any(ProxyConfiguration.ServiceConfig.class)))
                .thenAnswer(invocation -> {
                    ProxyConfiguration.ServiceConfig svc = invocation.getArgument(0);
                    List<ProxyConfiguration.HostConfig> hostList = svc.getHosts();
                    return (hostList == null || hostList.isEmpty()) ? null : hostList.get(0);
                });
        
        lenient().when(loadBalancerFactory.getLoadBalancer(anyString())).thenReturn(mockLoadBalancer);
        
        WebClient.Builder webClientBuilder = mock(WebClient.Builder.class);
        lenient().when(webClientBuilder.build()).thenReturn(webClient);
        
        proxyService = new ProxyService(serviceRegistry, loadBalancerFactory, 
                                       webClientBuilder, cacheService);
    }

    @Test
    void forwardRequest_shouldReturnBadRequestForMissingHostHeader() {
        ServerHttpRequest request = MockServerHttpRequest
                .get("/api/test")
                .build();

        ResponseEntity<byte[]> response = proxyService.forwardRequest(request, "").block();

        assertNotNull(response);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    }

    @Test
    void forwardRequest_shouldReturnNotFoundForUnknownDomain() {
        ServerHttpRequest request = MockServerHttpRequest
                .get("/api/test")
                .header("Host", "unknown.example.com")
                .build();

        ResponseEntity<byte[]> response = proxyService.forwardRequest(request, "").block();

        assertNotNull(response);
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    @Test
    void forwardRequest_shouldForwardToDownstreamService() {
        // Setup mock WebClient
        HttpHeaders responseHeaders = new HttpHeaders();
        responseHeaders.add("Content-Type", "application/json");
        ResponseEntity<byte[]> mockResponse = ResponseEntity.ok()
                .headers(responseHeaders)
                .body("{\"message\": \"success\"}".getBytes());
        
        when(webClient.method(any(HttpMethod.class))).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.uri(anyString())).thenReturn(requestBodySpec);
        when(requestBodySpec.headers(any())).thenReturn(requestBodySpec);
        when(requestBodySpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.toEntity(byte[].class)).thenReturn(Mono.just(mockResponse));

        ServerHttpRequest request = MockServerHttpRequest
                .get("/api/test")
                .header("Host", "test.example.com")
                .remoteAddress(new InetSocketAddress("127.0.0.1", 12345))
                .build();

        ResponseEntity<byte[]> response = proxyService.forwardRequest(request, "").block();

        assertNotNull(response);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertTrue(new String(response.getBody()).contains("success"));
        
        verify(webClient).method(HttpMethod.GET);
        verify(requestBodyUriSpec).uri(contains("/api/test"));
    }

    @Test
    void forwardRequest_shouldAddXForwardedHeaders() {
        HttpHeaders responseHeaders = new HttpHeaders();
        ResponseEntity<byte[]> mockResponse = ResponseEntity.ok().headers(responseHeaders).build();
        
        when(webClient.method(any(HttpMethod.class))).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.uri(anyString())).thenReturn(requestBodySpec);
        when(requestBodySpec.headers(any())).thenReturn(requestBodySpec);
        when(requestBodySpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.toEntity(byte[].class)).thenReturn(Mono.just(mockResponse));

        ServerHttpRequest request = MockServerHttpRequest
                .get("http://test.example.com/api/test")
                .header("Host", "test.example.com")
                .remoteAddress(new InetSocketAddress("192.168.1.100", 12345))
                .build();

        proxyService.forwardRequest(request, "").block();

        ArgumentCaptor<Consumer<HttpHeaders>> headersCaptor = ArgumentCaptor.forClass(Consumer.class);
        verify(requestBodySpec).headers(headersCaptor.capture());

        HttpHeaders forwarded = new HttpHeaders();
        headersCaptor.getValue().accept(forwarded);

        assertEquals("192.168.1.100", forwarded.getFirst("X-Forwarded-For"));
        assertEquals("http", forwarded.getFirst("X-Forwarded-Proto"));
        assertEquals("test.example.com", forwarded.getFirst("X-Forwarded-Host"));
    }

    @Test
    void forwardRequest_shouldHandleQueryParameters() {
        HttpHeaders responseHeaders = new HttpHeaders();
        ResponseEntity<byte[]> mockResponse = ResponseEntity.ok().headers(responseHeaders).build();
        
        when(webClient.method(any(HttpMethod.class))).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.uri(anyString())).thenReturn(requestBodySpec);
        when(requestBodySpec.headers(any())).thenReturn(requestBodySpec);
        when(requestBodySpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.toEntity(byte[].class)).thenReturn(Mono.just(mockResponse));

        ServerHttpRequest request = MockServerHttpRequest
                .get("/api/test?param1=value1&param2=value2")
                .header("Host", "test.example.com")
                .build();

        proxyService.forwardRequest(request, "").block();

        verify(requestBodyUriSpec).uri(contains("param1=value1"));
        verify(requestBodyUriSpec).uri(contains("param2=value2"));
    }

    @Test
    void forwardRequest_shouldForwardPostWithBody() {
        HttpHeaders responseHeaders = new HttpHeaders();
        ResponseEntity<byte[]> mockResponse = ResponseEntity.ok().headers(responseHeaders).build();
        
        when(webClient.method(any(HttpMethod.class))).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.uri(anyString())).thenReturn(requestBodySpec);
        when(requestBodySpec.headers(any())).thenReturn(requestBodySpec);
        when(requestBodySpec.body(any())).thenReturn(requestHeadersSpec);
        when(requestHeadersSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.toEntity(byte[].class)).thenReturn(Mono.just(mockResponse));

        String requestBody = "{\"name\": \"test\"}";
        ServerHttpRequest request = MockServerHttpRequest
                .post("/api/create")
                .header("Host", "test.example.com")
                .header("Content-Type", "application/json")
                .build();

        proxyService.forwardRequest(request, requestBody).block();

        verify(webClient).method(HttpMethod.POST);
        verify(requestBodySpec).body(any());
    }

    @Test
    void forwardRequest_shouldCacheGetResponse() {
        HttpHeaders responseHeaders = new HttpHeaders();
        responseHeaders.add("Cache-Control", "public, max-age=60");
        ResponseEntity<byte[]> mockResponse = ResponseEntity.ok()
                .headers(responseHeaders)
                .body("{\"data\": \"test\"}".getBytes());
        
        when(webClient.method(any(HttpMethod.class))).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.uri(anyString())).thenReturn(requestBodySpec);
        when(requestBodySpec.headers(any())).thenReturn(requestBodySpec);
        when(requestBodySpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.toEntity(byte[].class)).thenReturn(Mono.just(mockResponse));

        ServerHttpRequest request = MockServerHttpRequest
                .get("/api/cached")
                .header("Host", "test.example.com")
                .build();

        proxyService.forwardRequest(request, "").block();

        verify(cacheService).put(eq(HttpMethod.GET), eq("test.example.com"), 
                               eq("/api/cached"), any(HttpHeaders.class), 
                               eq(HttpStatus.OK), any(HttpHeaders.class), any(byte[].class));
    }

    @Test
    void forwardRequest_shouldReturnCachedResponseWhenFresh() {
        CachedResponse cachedResponse = CachedResponse.builder()
                .body("{\"cached\": true}".getBytes())
                .headers(new HttpHeaders())
                .statusCode(HttpStatus.OK)
                .cachedAt(Instant.now())
                .maxAgeSeconds(60L)
                .isPublic(true)
                .build();

        when(cacheService.get(eq(HttpMethod.GET), eq("test.example.com"), 
                            eq("/api/cached"), any(HttpHeaders.class)))
                .thenReturn(cachedResponse);

        ServerHttpRequest request = MockServerHttpRequest
                .get("/api/cached")
                .header("Host", "test.example.com")
                .build();

        ResponseEntity<byte[]> response = proxyService.forwardRequest(request, "").block();

        assertNotNull(response);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertArrayEquals("{\"cached\": true}".getBytes(), response.getBody());
        
        // Should not make downstream request
        verify(webClient, never()).method(any(HttpMethod.class));
    }

    @Test
    void forwardRequest_shouldInvalidateCacheOnPostRequest() {
        HttpHeaders responseHeaders = new HttpHeaders();
        ResponseEntity<byte[]> mockResponse = ResponseEntity.status(201)
                .headers(responseHeaders).build();
        
        when(webClient.method(any(HttpMethod.class))).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.uri(anyString())).thenReturn(requestBodySpec);
        when(requestBodySpec.headers(any())).thenReturn(requestBodySpec);
        when(requestBodySpec.body(any())).thenReturn(requestHeadersSpec);
        when(requestHeadersSpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.toEntity(byte[].class)).thenReturn(Mono.just(mockResponse));

        ServerHttpRequest request = MockServerHttpRequest
                .post("/api/resource")
                .header("Host", "test.example.com")
                .build();

        proxyService.forwardRequest(request, "{\"data\": \"test\"}").block();

        verify(cacheService).invalidateUnsafe("test.example.com", "/api/resource");
    }

    @Test
    void forwardRequest_shouldHandleDownstreamError() {
        HttpHeaders responseHeaders = new HttpHeaders();
        ResponseEntity<byte[]> mockResponse = ResponseEntity.status(500)
                .headers(responseHeaders).build();
        
        when(webClient.method(any(HttpMethod.class))).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.uri(anyString())).thenReturn(requestBodySpec);
        when(requestBodySpec.headers(any())).thenReturn(requestBodySpec);
        when(requestBodySpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.toEntity(byte[].class)).thenReturn(Mono.just(mockResponse));

        ServerHttpRequest request = MockServerHttpRequest
                .get("/api/test")
                .header("Host", "test.example.com")
                .build();

        ResponseEntity<byte[]> response = proxyService.forwardRequest(request, "").block();

        assertNotNull(response);
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
    }

    @Test
    void forwardRequest_shouldReturnBadGatewayOnConnectionError() {
        when(webClient.method(any(HttpMethod.class))).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.uri(anyString())).thenReturn(requestBodySpec);
        when(requestBodySpec.headers(any())).thenReturn(requestBodySpec);
        when(requestBodySpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.toEntity(byte[].class))
                .thenReturn(Mono.error(new RuntimeException("Connection refused")));

        ServerHttpRequest request = MockServerHttpRequest
                .get("/api/test")
                .header("Host", "test.example.com")
                .build();

        ResponseEntity<byte[]> response = proxyService.forwardRequest(request, "").block();

        assertNotNull(response);
        assertEquals(HttpStatus.BAD_GATEWAY, response.getStatusCode());
    }

    @Test
    void forwardRequest_shouldHandleRevalidationWith304() {
        CachedResponse cachedResponse = CachedResponse.builder()
                .body("{\"cached\": true}".getBytes())
                .headers(new HttpHeaders())
                .statusCode(HttpStatus.OK)
                .cachedAt(Instant.now().minusSeconds(100))
                .maxAgeSeconds(30L)
                .etag("\"v1\"")
                .isPublic(true)
                .build();

        when(cacheService.get(eq(HttpMethod.GET), eq("test.example.com"), 
                            eq("/api/cached"), any(HttpHeaders.class)))
                .thenReturn(cachedResponse);

        HttpHeaders responseHeaders = new HttpHeaders();
        responseHeaders.add("ETag", "\"v1\"");
        ResponseEntity<byte[]> mockResponse = ResponseEntity.status(304)
                .headers(responseHeaders).build();
        
        when(webClient.method(any(HttpMethod.class))).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.uri(anyString())).thenReturn(requestBodySpec);
        when(requestBodySpec.headers(any())).thenReturn(requestBodySpec);
        when(requestBodySpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.toEntity(byte[].class)).thenReturn(Mono.just(mockResponse));

        ServerHttpRequest request = MockServerHttpRequest
                .get("/api/cached")
                .header("Host", "test.example.com")
                .build();

        ResponseEntity<byte[]> response = proxyService.forwardRequest(request, "").block();

        assertNotNull(response);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertArrayEquals("{\"cached\": true}".getBytes(), response.getBody());
        
        verify(cacheService).updateAfterRevalidation(eq(HttpMethod.GET), 
                eq("test.example.com"), eq("/api/cached"), 
                any(HttpHeaders.class), any(HttpHeaders.class));
    }

    @Test
    void forwardRequest_shouldRemoveHopByHopHeaders() {
        HttpHeaders responseHeaders = new HttpHeaders();
        ResponseEntity<byte[]> mockResponse = ResponseEntity.ok()
                .headers(responseHeaders).build();
        
        when(webClient.method(any(HttpMethod.class))).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.uri(anyString())).thenReturn(requestBodySpec);
        when(requestBodySpec.headers(any())).thenReturn(requestBodySpec);
        when(requestBodySpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.toEntity(byte[].class)).thenReturn(Mono.just(mockResponse));

        ServerHttpRequest request = MockServerHttpRequest
                .get("/api/test")
                .header("Host", "test.example.com")
                .header("Connection", "keep-alive")
                .header("Keep-Alive", "timeout=5")
                .header("Transfer-Encoding", "chunked")
                .header("X-Custom-Header", "value")
                .build();

        proxyService.forwardRequest(request, "").block();

        ArgumentCaptor<Consumer<HttpHeaders>> headersCaptor = ArgumentCaptor.forClass(Consumer.class);
        verify(requestBodySpec).headers(headersCaptor.capture());

        HttpHeaders forwarded = new HttpHeaders();
        headersCaptor.getValue().accept(forwarded);

        assertNull(forwarded.getFirst("Connection"));
        assertNull(forwarded.getFirst("Keep-Alive"));
        assertNull(forwarded.getFirst("Transfer-Encoding"));
        assertNull(forwarded.getFirst("Host"));
        assertEquals("value", forwarded.getFirst("X-Custom-Header"));
    }
}
