package com.marco.reverseproxy.cache;

import lombok.Builder;
import lombok.Data;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;

import java.time.Instant;

/**
 * Cached HTTP response with validation metadata
 * Supports ETag and Last-Modified validation per RFC (9111)
 */
@Data
@Builder
public class CachedResponse {
    
    // Response data
    private byte[] body;
    private HttpHeaders headers;
    private HttpStatusCode statusCode;
    
    // Cache metadata
    private Instant cachedAt;
    private Long maxAgeSeconds;      // From Cache-Control max-age
    private Long sMaxAgeSeconds;     // From Cache-Control s-maxage (proxy priority)
    
    // Validation metadata (optional)
    private String etag;             // From ETag header
    private Instant lastModified;    // From Last-Modified header
    
    // Cache directives
    private boolean noCache;         // Cache-Control: no-cache (must revalidate)
    private boolean noStore;         // Cache-Control: no-store (don't cache)
    private boolean mustRevalidate;  // Cache-Control: must-revalidate
    private boolean proxyRevalidate; // Cache-Control: proxy-revalidate
    private boolean isPrivate;       // Cache-Control: private (don't cache in proxy)
    private boolean isPublic;        // Cache-Control: public (cacheable)
    
    /**
     * Check if cache entry is fresh (within TTL)
     */
    public boolean isFresh() {
        
        Instant now = Instant.now();
        long ageSeconds = now.getEpochSecond() - cachedAt.getEpochSecond();
        
        // s-maxage takes priority for shared caches (proxy)
        Long effectiveMaxAge = sMaxAgeSeconds != null ? sMaxAgeSeconds : maxAgeSeconds;
        
        if (effectiveMaxAge == null) {
            return false; // No TTL specified
        }
        
        return ageSeconds < effectiveMaxAge;
    }

    /**
     * Check if entry requires revalidation
     */
    public boolean requiresRevalidation() {
        if (proxyRevalidate)
            return noCache || (proxyRevalidate && !isFresh());
        return noCache || (mustRevalidate && !isFresh());
    }

    /**
     * Check if entry is cacheable
     */
    public boolean isCacheable() {
        return !noStore && !isPrivate && (isPublic || maxAgeSeconds != null || sMaxAgeSeconds != null);
    }

    /**
     * Check if entry has validation metadata (ETag or Last-Modified)
     */
    public boolean hasValidationMetadata() {
        return etag != null || lastModified != null;
    }

    /**
     * Get age of cached entry in seconds
     */
    public long getAgeSeconds() {
        return Instant.now().getEpochSecond() - cachedAt.getEpochSecond();
    }
}
