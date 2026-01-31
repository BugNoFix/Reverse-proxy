package com.marco.reverseproxy.cache;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * HTTP cache service with LRU eviction and RFC 7234 compliance
 * Supports Cache-Control, ETag, Last-Modified, and Vary headers
 */
@Slf4j
@Service
public class CacheService {

    // LRU cache with max 10,000 entries (thread-safe)
    private static final int MAX_CACHE_ENTRIES = 10_000;
    private final Map<CacheKey, CachedResponse> cache = Collections.synchronizedMap(
        new LinkedHashMap<CacheKey, CachedResponse>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<CacheKey, CachedResponse> eldest) {
                boolean shouldRemove = size() > MAX_CACHE_ENTRIES;
                if (shouldRemove) {
                    log.debug("LRU eviction: removing cache entry for {}", eldest.getKey());
                }
                return shouldRemove;
            }
        }
    );

    private static final Pattern MAX_AGE_PATTERN = Pattern.compile("max-age\\s*=\\s*(\\d+)");
    private static final Pattern S_MAXAGE_PATTERN = Pattern.compile("s-maxage\\s*=\\s*(\\d+)");

    /**
     * Get cached response if available and valid
     * 
     * @param method HTTP method
     * @param uri Request URI
     * @param requestHeaders Request headers
     * @return Cached response or null if not cached/invalid
     */
    public CachedResponse get(HttpMethod method, String host, String uri, HttpHeaders requestHeaders) {
        // Only cache GET and HEAD
        if (method != HttpMethod.GET && method != HttpMethod.HEAD) {
            return null;
        }

        // Simple lookup first (without Vary)
        CacheKey simpleKey = CacheKey.createSimple(method, host, uri);
        CachedResponse cachedResponse = cache.get(simpleKey);

        if (cachedResponse == null) {
            // Try with Vary headers if any cached response exists
            String varyHeader = requestHeaders.getFirst("Vary");
            if (varyHeader != null) {
                CacheKey varyKey = CacheKey.create(method, host, uri, requestHeaders, varyHeader);
                cachedResponse = cache.get(varyKey);
            }
        }

        if (cachedResponse == null) {
            log.debug("Cache MISS: {} {}", method, uri);
            return null;
        }

        // Check if cacheable
        if (!cachedResponse.isCacheable()) {
            log.debug("Cache entry not cacheable (no-store or private): {} {}", method, uri);
            cache.remove(simpleKey);
            return null;
        }

        log.debug("Cache HIT: {} {} (age: {}s, fresh: {})", 
                method, uri, cachedResponse.getAgeSeconds(), cachedResponse.isFresh());

        return cachedResponse;
    }

    /**
     * Store response in cache
     * 
     * @param method HTTP method
     * @param uri Request URI
     * @param requestHeaders Request headers
     * @param statusCode Response status code
     * @param responseHeaders Response headers
     * @param body Response body
     */
    public void put(HttpMethod method, String host, String uri, HttpHeaders requestHeaders, 
                   HttpStatusCode statusCode, HttpHeaders responseHeaders, byte[] body) {
        // Only cache GET and HEAD with 200 OK
        if ((method != HttpMethod.GET && method != HttpMethod.HEAD) || statusCode.value() != 200) {
            return;
        }

        // Parse Cache-Control directives
        CachedResponse cachedResponse = parseCacheControl(statusCode, responseHeaders, body);

        if (!cachedResponse.isCacheable()) {
            log.debug("Response not cacheable: {} {}", method, uri);
            return;
        }

        // Create cache key (with Vary support)
        String varyHeader = responseHeaders.getFirst("Vary");
        CacheKey key;
        if (varyHeader != null && !varyHeader.equals("*")) {
            key = CacheKey.create(method, host, uri, requestHeaders, varyHeader);
            log.debug("Caching with Vary: {} {} (Vary: {})", method, uri, varyHeader);
        } else {
            key = CacheKey.createSimple(method, host, uri);
        }

        cache.put(key, cachedResponse);

        log.info("Cached response: {} {} (max-age: {}s, has-etag: {}, has-last-modified: {})",
                method, uri, cachedResponse.getMaxAgeSeconds(), 
                cachedResponse.getEtag() != null, cachedResponse.getLastModified() != null);
    }

    /**
     * Update cached entry after successful 304 revalidation
     */
    public void updateAfterRevalidation(HttpMethod method, String host, String uri, HttpHeaders requestHeaders, 
                                       HttpHeaders responseHeaders) {
        CacheKey key = CacheKey.createSimple(method, host, uri);
        
        CachedResponse cachedResponse = cache.get(key);
        if (cachedResponse != null) {
            // Update cache timestamp and metadata
            cachedResponse.setCachedAt(Instant.now());
                
                // Update ETag if provided
                String etag = responseHeaders.getFirst("ETag");
                if (etag != null) {
                    cachedResponse.setEtag(etag);
                }
                
                // Parse new Cache-Control
                String cacheControl = responseHeaders.getFirst("Cache-Control");
                if (cacheControl != null) {
                    parseCacheControlDirectives(cacheControl, cachedResponse);
                }
                
                log.debug("Updated cache after 304: {} {} (new max-age: {}s)", 
                        method, uri, cachedResponse.getMaxAgeSeconds());
            }
    }

    /**
     * Clear all cache entries
     */
    public void clear() {
        cache.clear();
        log.info("Cache cleared");
    }

    /**
     * Get cache statistics
     */
    public Map<String, Object> getStats() {
        return Map.of(
                "size", cache.size(),
                "maxSize", MAX_CACHE_ENTRIES
        );
    }

    /**
     * Parse Cache-Control header and create CachedResponse
     */
    private CachedResponse parseCacheControl(HttpStatusCode statusCode, HttpHeaders headers, byte[] body) {
        CachedResponse.CachedResponseBuilder builder = CachedResponse.builder()
                .body(body)
                .headers(headers)
                .statusCode(statusCode)
                .cachedAt(Instant.now());

        // Parse ETag
        String etag = headers.getFirst("ETag");
        if (etag != null) {
            builder.etag(etag);
        }

        // Parse Last-Modified
        String lastModifiedStr = headers.getFirst("Last-Modified");
        if (lastModifiedStr != null) {
            try {
                ZonedDateTime lastModified = ZonedDateTime.parse(lastModifiedStr, 
                        DateTimeFormatter.RFC_1123_DATE_TIME);
                builder.lastModified(lastModified.toInstant());
            } catch (Exception e) {
                log.warn("Invalid Last-Modified header: {}", lastModifiedStr);
            }
        }

        // Parse Cache-Control
        String cacheControl = headers.getFirst("Cache-Control");
        if (cacheControl != null) {
            parseCacheControlDirectives(cacheControl, builder);
        }

        return builder.build();
    }

    /**
     * Parse Cache-Control directives into builder
     */
    private void parseCacheControlDirectives(String cacheControl, CachedResponse.CachedResponseBuilder builder) {
        String lowerCacheControl = cacheControl.toLowerCase();

        // Parse max-age
        Matcher maxAgeMatcher = MAX_AGE_PATTERN.matcher(lowerCacheControl);
        if (maxAgeMatcher.find()) {
            builder.maxAgeSeconds(Long.parseLong(maxAgeMatcher.group(1)));
        }

        // Parse s-maxage (proxy priority)
        Matcher sMaxAgeMatcher = S_MAXAGE_PATTERN.matcher(lowerCacheControl);
        if (sMaxAgeMatcher.find()) {
            builder.sMaxAgeSeconds(Long.parseLong(sMaxAgeMatcher.group(1)));
        }

        // Parse directives
        builder.noCache(lowerCacheControl.contains("no-cache"))
               .noStore(lowerCacheControl.contains("no-store"))
               .mustRevalidate(lowerCacheControl.contains("must-revalidate"))
               .proxyRevalidate(lowerCacheControl.contains("proxy-revalidate"))
               .isPrivate(lowerCacheControl.contains("private"))
               .isPublic(lowerCacheControl.contains("public"));
    }

    /**
     * Parse Cache-Control directives into existing CachedResponse
     */
    private void parseCacheControlDirectives(String cacheControl, CachedResponse cachedResponse) {
        String lowerCacheControl = cacheControl.toLowerCase();

        // Parse max-age
        Matcher maxAgeMatcher = MAX_AGE_PATTERN.matcher(lowerCacheControl);
        if (maxAgeMatcher.find()) {
            cachedResponse.setMaxAgeSeconds(Long.parseLong(maxAgeMatcher.group(1)));
        }

        // Parse s-maxage
        Matcher sMaxAgeMatcher = S_MAXAGE_PATTERN.matcher(lowerCacheControl);
        if (sMaxAgeMatcher.find()) {
            cachedResponse.setSMaxAgeSeconds(Long.parseLong(sMaxAgeMatcher.group(1)));
        }

        // Parse directives
        cachedResponse.setNoCache(lowerCacheControl.contains("no-cache"));
        cachedResponse.setNoStore(lowerCacheControl.contains("no-store"));
        cachedResponse.setMustRevalidate(lowerCacheControl.contains("must-revalidate"));
        cachedResponse.setProxyRevalidate(lowerCacheControl.contains("proxy-revalidate"));
        cachedResponse.setPrivate(lowerCacheControl.contains("private"));
        cachedResponse.setPublic(lowerCacheControl.contains("public"));
    }
}
