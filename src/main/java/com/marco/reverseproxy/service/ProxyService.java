package com.marco.reverseproxy.service;

import com.marco.reverseproxy.cache.CacheService;
import com.marco.reverseproxy.cache.CachedResponse;
import com.marco.reverseproxy.config.ProxyConfiguration;
import com.marco.reverseproxy.loadbalancer.LoadBalancer;
import com.marco.reverseproxy.loadbalancer.LoadBalancerFactory;
import com.marco.reverseproxy.util.HostUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Core proxy service that handles request forwarding (fully reactive)
 */
@Slf4j
@Service
public class ProxyService {

    private static final Set<String> HOP_BY_HOP_HEADERS = Set.of(
            "connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
            "te", "trailers", "transfer-encoding", "upgrade"
    );

    private final ServiceRegistry serviceRegistry;
    private final LoadBalancerFactory loadBalancerFactory;
    private final WebClient webClient;
    private final CacheService cacheService;
    
    public ProxyService(ServiceRegistry serviceRegistry, 
                       LoadBalancerFactory loadBalancerFactory,
                       WebClient.Builder webClientBuilder,
                       CacheService cacheService) {
        this.serviceRegistry = serviceRegistry;
        this.loadBalancerFactory = loadBalancerFactory;
        this.webClient = webClientBuilder.build(); // Reuse single instance for performance
        this.cacheService = cacheService;
    }

    public Mono<ResponseEntity<byte[]>> forwardRequest(
            ServerHttpRequest request,
            String requestBody
    ) {
        // Extract Host header to identify the target service
        String hostHeader = request.getHeaders().getFirst("Host");
        if (hostHeader == null) {
            log.warn("Missing Host header in request");
            return Mono.just(ResponseEntity.badRequest()
                    .body("Missing Host header".getBytes()));
        }

        String normalizedHost = HostUtils.normalizeHost(hostHeader);

        // Find the service configuration based on domain
        ProxyConfiguration.ServiceConfig service = serviceRegistry.getServiceByDomain(normalizedHost);
        if (service == null) {
            log.warn("No service found for domain: {}", normalizedHost);
            return Mono.just(ResponseEntity.notFound().build());
        }

        // Check cache (only for GET/HEAD)
        HttpMethod method = request.getMethod();
        String uri = request.getPath().value();
        String query = request.getURI().getRawQuery();
        if (query != null && !query.isEmpty()) {
            uri = uri + "?" + query;
        }

        if (isUnsafeMethod(method)) {
            cacheService.invalidateUnsafe(normalizedHost, uri);
        }
        CachedResponse cachedResponse = cacheService.get(method, normalizedHost, uri, request.getHeaders());
        
        if (cachedResponse != null && cachedResponse.isFresh()) {
            // Cache hit and fresh - return cached response
            log.info("Cache HIT (fresh): {} {}", method, uri);
            return Mono.just(ResponseEntity.status(cachedResponse.getStatusCode())
                    .headers(cachedResponse.getHeaders())
                    .body(cachedResponse.getBody()));
        }

        // Get healthy hosts
        List<ProxyConfiguration.HostConfig> healthyHosts = serviceRegistry.getHealthyHosts(service);
        if (healthyHosts.isEmpty()) {
            log.error("No healthy hosts available for service: {}", service.getName());
            return Mono.just(ResponseEntity.status(503)
                    .body("Service Unavailable: No healthy hosts".getBytes()));
        }

        // Select a host using configured load balancer strategy
        LoadBalancer loadBalancer = loadBalancerFactory.getLoadBalancer(service.getStrategy());
        ProxyConfiguration.HostConfig selectedHost = loadBalancer.selectHost(healthyHosts, service);

        if (selectedHost == null) {
            log.error("Load balancer returned null for service: {}", service.getName());
            return Mono.just(ResponseEntity.status(503)
                    .body("Service Unavailable".getBytes()));
        }

        // Build target URL
        String targetUrl = buildTargetUrl(selectedHost, request);
        log.info("Forwarding request to: {}", targetUrl);

        // Forward the request (pass cachedResponse for validation)
        return forwardToDownstream(request, requestBody, targetUrl, service, selectedHost, cachedResponse, method, uri, normalizedHost);
    }

    private String buildTargetUrl(ProxyConfiguration.HostConfig host, ServerHttpRequest request) {
        StringBuilder url = new StringBuilder();
        url.append("http://")
           .append(host.getAddress())
           .append(":")
           .append(host.getPort())
           .append(request.getPath().value());

        String queryString = request.getURI().getRawQuery();
        if (queryString != null && !queryString.isEmpty()) {
            url.append("?").append(queryString);
        }

        return url.toString();
    }

    private Mono<ResponseEntity<byte[]>> forwardToDownstream(
            ServerHttpRequest request,
            String requestBody,
            String targetUrl,
            ProxyConfiguration.ServiceConfig service,
            ProxyConfiguration.HostConfig host,
            CachedResponse cachedResponse,
            HttpMethod method,
            String uri,
            String normalizedHost
    ) {
        HttpHeaders headers = prepareHeaders(request);

        // Add validation headers if cache entry exists but stale
        if (cachedResponse != null && cachedResponse.hasValidationMetadata()) {
            if (cachedResponse.getEtag() != null) {
                headers.set("If-None-Match", cachedResponse.getEtag());
                log.debug("Added If-None-Match: {}", cachedResponse.getEtag());
            }
            if (cachedResponse.getLastModified() != null) {
                String lastModifiedStr = DateTimeFormatter.RFC_1123_DATE_TIME
                        .format(cachedResponse.getLastModified().atZone(ZoneId.of("GMT")));
                headers.set("If-Modified-Since", lastModifiedStr);
                log.debug("Added If-Modified-Since: {}", lastModifiedStr);
            }
        }

        // Build the request
        WebClient.RequestBodySpec requestSpec = webClient.method(method)
                .uri(targetUrl)
                .headers(httpHeaders -> httpHeaders.addAll(headers));

        // Add body if present
        WebClient.RequestHeadersSpec<?> headersSpec;
        if (requestBody != null && !requestBody.isEmpty() && 
            (method == HttpMethod.POST || method == HttpMethod.PUT || method == HttpMethod.PATCH)) {
            headersSpec = requestSpec.body(BodyInserters.fromValue(requestBody));
        } else {
            headersSpec = requestSpec;
        }

        // Execute request and handle response
        return headersSpec.retrieve()
                .toEntity(byte[].class)
                .doOnSuccess(response -> log.debug("Successfully forwarded request to {}:{}", 
                        host.getAddress(), host.getPort()))
                .onErrorResume(error -> {
                    log.error("Error forwarding request to {}:{} - {}", 
                            host.getAddress(), host.getPort(), error.getMessage());
                    serviceRegistry.markHostUnhealthy(service, host);
                    return Mono.just(ResponseEntity.status(502)
                            .body("Bad Gateway: Downstream service error".getBytes()));
                })
                .map(response -> {
                    // Handle 304 Not Modified
                    if (response.getStatusCode().value() == 304 && cachedResponse != null) {
                        log.info("304 Not Modified: {} {} - using cached body", method, uri);
                        // Update cache metadata
                        cacheService.updateAfterRevalidation(method, normalizedHost, uri, request.getHeaders(), response.getHeaders());

                        // Merge cached headers with 304 headers (304 can update cache metadata)
                        HttpHeaders mergedHeaders = new HttpHeaders();
                        mergedHeaders.addAll(filterResponseHeaders(cachedResponse.getHeaders()));
                        mergedHeaders.addAll(filterResponseHeaders(response.getHeaders()));

                        // Return cached body with merged headers
                        return ResponseEntity.status(200)
                                .headers(mergedHeaders)
                                .body(cachedResponse.getBody());
                    }
                    
                    // Cache successful response
                    if (response.getStatusCode().value() == 200 && response.getBody() != null) {
                        cacheService.put(method, normalizedHost, uri, request.getHeaders(), 
                                response.getStatusCode(), response.getHeaders(), response.getBody());
                    }
                    
                    return ResponseEntity.status(response.getStatusCode())
                            .headers(filterResponseHeaders(response.getHeaders()))
                            .body(response.getBody());
                });
    }

    private HttpHeaders prepareHeaders(ServerHttpRequest request) {
        HttpHeaders headers = new HttpHeaders();

        Set<String> hopByHop = new HashSet<>(HOP_BY_HOP_HEADERS);
        hopByHop.addAll(parseConnectionHeaderTokens(request.getHeaders()));

        // Copy all headers except hop-by-hop headers and Host (backend has its own host)
        request.getHeaders().forEach((headerName, headerValues) -> {
            if (!isHopByHopHeader(headerName) && !headerName.equalsIgnoreCase("host")
                    && !hopByHop.contains(headerName.toLowerCase())) {
                headerValues.forEach(headerValue -> {
                    headers.add(headerName, headerValue);
                });
            }
        });

        // Add X-Forwarded-For header
        String clientIp = request.getRemoteAddress() != null 
            ? request.getRemoteAddress().getAddress().getHostAddress() 
            : "unknown";
        String existingForwardedFor = request.getHeaders().getFirst("X-Forwarded-For");
        if (existingForwardedFor != null) {
            headers.set("X-Forwarded-For", existingForwardedFor + ", " + clientIp);
        } else {
            headers.set("X-Forwarded-For", clientIp);
        }

        // Add other X-Forwarded headers
        headers.set("X-Forwarded-Proto", request.getURI().getScheme());
        headers.set("X-Forwarded-Host", request.getHeaders().getFirst("Host"));

        return headers;
    }

    private HttpHeaders filterResponseHeaders(HttpHeaders responseHeaders) {
        HttpHeaders filteredHeaders = new HttpHeaders();
        Set<String> hopByHop = new HashSet<>(HOP_BY_HOP_HEADERS);
        hopByHop.addAll(parseConnectionHeaderTokens(responseHeaders));
        responseHeaders.forEach((name, values) -> {
            if (!isHopByHopHeader(name) && !hopByHop.contains(name.toLowerCase())) {
                filteredHeaders.addAll(name, values);
            }
        });
        return filteredHeaders;
    }

    private Set<String> parseConnectionHeaderTokens(HttpHeaders headers) {
        Set<String> tokens = new HashSet<>();
        if (headers == null) {
            return tokens;
        }

        List<String> connectionValues = headers.getOrEmpty("Connection");
        for (String value : connectionValues) {
            if (value == null || value.isBlank()) {
                continue;
            }
            String[] parts = value.split(",");
            for (String part : parts) {
                String token = part.trim().toLowerCase();
                if (!token.isEmpty()) {
                    tokens.add(token);
                }
            }
        }

        return tokens;
    }

    private boolean isHopByHopHeader(String headerName) {
        return HOP_BY_HOP_HEADERS.contains(headerName.toLowerCase());
    }

    private boolean isUnsafeMethod(HttpMethod method) {
        return method == HttpMethod.POST
                || method == HttpMethod.PUT
                || method == HttpMethod.PATCH
                || method == HttpMethod.DELETE;
    }

}
