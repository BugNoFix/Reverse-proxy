package com.marco.reverseproxy.cache;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;

import java.util.*;

/**
 * Cache key that includes HTTP method, URI, and Vary headers
 * Supports RFC 9110 Vary header for content negotiation
 */
@Getter
@EqualsAndHashCode
@ToString
public class CacheKey {
    
    private final HttpMethod method;
    private final String host;
    private final String uri;
    private final Map<String, String> varyHeaders;

    private CacheKey(HttpMethod method, String host, String uri, Map<String, String> varyHeaders) {
        this.method = method;
        this.host = host;
        this.uri = uri;
        this.varyHeaders = varyHeaders;
    }

    /**
     * Create cache key with Vary header support
     */
    public static CacheKey create(HttpMethod method, String host, String uri, HttpHeaders requestHeaders, String varyHeaderValue) {
        Map<String, String> varyHeaders = new HashMap<>();
        
        // If cached response has Vary header, include those headers in key
        if (varyHeaderValue != null && !varyHeaderValue.equals("*")) {
            String[] varyHeaderNames = varyHeaderValue.split(",");
            for (String headerName : varyHeaderNames) {
                String trimmedName = headerName.trim().toLowerCase();
                String headerValue = requestHeaders.getFirst(trimmedName);
                if (headerValue != null) {
                    varyHeaders.put(trimmedName, headerValue);
                }
            }
        }
        
        return new CacheKey(method, host, uri, varyHeaders);
    }

    /**
     * Create simple cache key without Vary support
     */
    public static CacheKey createSimple(HttpMethod method, String host, String uri) {
        return new CacheKey(method, host, uri, Collections.emptyMap());
    }
}
