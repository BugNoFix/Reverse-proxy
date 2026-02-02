package com.marco.reverseproxy.cache;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CacheServiceTest {

    private CacheService cacheService;
    private static final String HOST = "example.com";
    private static final String URI = "/api/users";

    @BeforeEach
    void setUp() {
        cacheService = new CacheService();
    }

    @Test
    void get_shouldReturnNullForNonCachedRequest() {
        HttpHeaders requestHeaders = new HttpHeaders();
        
        CachedResponse response = cacheService.get(HttpMethod.GET, HOST, URI, requestHeaders);

        assertNull(response);
    }

    @Test
    void get_shouldReturnNullForPostRequest() {
        HttpHeaders requestHeaders = new HttpHeaders();
        
        CachedResponse response = cacheService.get(HttpMethod.POST, HOST, URI, requestHeaders);

        assertNull(response);
    }

    @Test
    void put_shouldCacheGetRequestWithMaxAge() {
        HttpHeaders requestHeaders = new HttpHeaders();
        HttpHeaders responseHeaders = new HttpHeaders();
        responseHeaders.add("Cache-Control", "public, max-age=60");
        byte[] body = "test response".getBytes();

        cacheService.put(HttpMethod.GET, HOST, URI, requestHeaders, 
                        HttpStatus.OK, responseHeaders, body);

        CachedResponse cached = cacheService.get(HttpMethod.GET, HOST, URI, requestHeaders);
        
        assertNotNull(cached);
        assertArrayEquals(body, cached.getBody());
        assertEquals(60L, cached.getMaxAgeSeconds());
        assertTrue(cached.isPublic());
        assertTrue(cached.isFresh());
    }

    @Test
    void put_shouldNotCacheNon200Status() {
        HttpHeaders requestHeaders = new HttpHeaders();
        HttpHeaders responseHeaders = new HttpHeaders();
        responseHeaders.add("Cache-Control", "public, max-age=60");
        byte[] body = "error".getBytes();

        cacheService.put(HttpMethod.GET, HOST, URI, requestHeaders, 
                        HttpStatus.NOT_FOUND, responseHeaders, body);

        CachedResponse cached = cacheService.get(HttpMethod.GET, HOST, URI, requestHeaders);
        
        assertNull(cached);
    }

    @Test
    void put_shouldNotCacheNoStoreDirective() {
        HttpHeaders requestHeaders = new HttpHeaders();
        HttpHeaders responseHeaders = new HttpHeaders();
        responseHeaders.add("Cache-Control", "no-store");
        byte[] body = "test response".getBytes();

        cacheService.put(HttpMethod.GET, HOST, URI, requestHeaders, 
                        HttpStatus.OK, responseHeaders, body);

        CachedResponse cached = cacheService.get(HttpMethod.GET, HOST, URI, requestHeaders);
        
        assertNull(cached);
    }

    @Test
    void put_shouldNotCachePrivateDirective() {
        HttpHeaders requestHeaders = new HttpHeaders();
        HttpHeaders responseHeaders = new HttpHeaders();
        responseHeaders.add("Cache-Control", "private, max-age=60");
        byte[] body = "test response".getBytes();

        cacheService.put(HttpMethod.GET, HOST, URI, requestHeaders, 
                        HttpStatus.OK, responseHeaders, body);

        CachedResponse cached = cacheService.get(HttpMethod.GET, HOST, URI, requestHeaders);
        
        assertNull(cached);
    }

    @Test
    void put_shouldCacheWithETag() {
        HttpHeaders requestHeaders = new HttpHeaders();
        HttpHeaders responseHeaders = new HttpHeaders();
        responseHeaders.add("Cache-Control", "public, max-age=60");
        responseHeaders.add("ETag", "\"abc123\"");
        byte[] body = "test response".getBytes();

        cacheService.put(HttpMethod.GET, HOST, URI, requestHeaders, 
                        HttpStatus.OK, responseHeaders, body);

        CachedResponse cached = cacheService.get(HttpMethod.GET, HOST, URI, requestHeaders);
        
        assertNotNull(cached);
        assertEquals("\"abc123\"", cached.getEtag());
    }

    @Test
    void put_shouldCacheWithLastModified() {
        HttpHeaders requestHeaders = new HttpHeaders();
        HttpHeaders responseHeaders = new HttpHeaders();
        responseHeaders.add("Cache-Control", "public, max-age=60");
        String lastModified = DateTimeFormatter.RFC_1123_DATE_TIME
                .format(Instant.now().atZone(ZoneId.of("GMT")));
        responseHeaders.add("Last-Modified", lastModified);
        byte[] body = "test response".getBytes();

        cacheService.put(HttpMethod.GET, HOST, URI, requestHeaders, 
                        HttpStatus.OK, responseHeaders, body);

        CachedResponse cached = cacheService.get(HttpMethod.GET, HOST, URI, requestHeaders);
        
        assertNotNull(cached);
        assertNotNull(cached.getLastModified());
    }

    @Test
    void put_shouldHandleVaryHeader() {
        HttpHeaders requestHeaders1 = new HttpHeaders();
        requestHeaders1.add("Accept-Encoding", "gzip");
        
        HttpHeaders requestHeaders2 = new HttpHeaders();
        requestHeaders2.add("Accept-Encoding", "deflate");
        
        HttpHeaders responseHeaders = new HttpHeaders();
        responseHeaders.add("Cache-Control", "public, max-age=60");
        responseHeaders.add("Vary", "Accept-Encoding");
        byte[] body1 = "gzip response".getBytes();
        byte[] body2 = "deflate response".getBytes();

        // Cache first variant
        cacheService.put(HttpMethod.GET, HOST, URI, requestHeaders1, 
                        HttpStatus.OK, responseHeaders, body1);
        
        // Cache second variant
        cacheService.put(HttpMethod.GET, HOST, URI, requestHeaders2, 
                        HttpStatus.OK, responseHeaders, body2);

        // Retrieve both variants
        CachedResponse cached1 = cacheService.get(HttpMethod.GET, HOST, URI, requestHeaders1);
        CachedResponse cached2 = cacheService.get(HttpMethod.GET, HOST, URI, requestHeaders2);
        
        assertNotNull(cached1);
        assertNotNull(cached2);
        assertArrayEquals(body1, cached1.getBody());
        assertArrayEquals(body2, cached2.getBody());
    }

    @Test
    void put_shouldNotCacheWithVaryWildcard() {
        HttpHeaders requestHeaders = new HttpHeaders();
        HttpHeaders responseHeaders = new HttpHeaders();
        responseHeaders.add("Cache-Control", "public, max-age=60");
        responseHeaders.add("Vary", "*");
        byte[] body = "test response".getBytes();

        cacheService.put(HttpMethod.GET, HOST, URI, requestHeaders, 
                        HttpStatus.OK, responseHeaders, body);

        CachedResponse cached = cacheService.get(HttpMethod.GET, HOST, URI, requestHeaders);
        
        assertNull(cached);
    }

    @Test
    void put_shouldPreferSMaxAge() {
        HttpHeaders requestHeaders = new HttpHeaders();
        HttpHeaders responseHeaders = new HttpHeaders();
        responseHeaders.add("Cache-Control", "max-age=30, s-maxage=120");
        byte[] body = "test response".getBytes();

        cacheService.put(HttpMethod.GET, HOST, URI, requestHeaders, 
                        HttpStatus.OK, responseHeaders, body);

        CachedResponse cached = cacheService.get(HttpMethod.GET, HOST, URI, requestHeaders);
        
        assertNotNull(cached);
        assertEquals(30L, cached.getMaxAgeSeconds());
        assertEquals(120L, cached.getSMaxAgeSeconds());

        cached.setCachedAt(Instant.now().minusSeconds(40));
        assertTrue(cached.isFresh());
    }

    @Test
    void updateAfterRevalidation() throws InterruptedException {
        // Cache initial response
        HttpHeaders requestHeaders = new HttpHeaders();
        HttpHeaders responseHeaders = new HttpHeaders();
        responseHeaders.add("Cache-Control", "public, max-age=60");
        responseHeaders.add("ETag", "\"v1\"");
        byte[] body = "test response".getBytes();

        cacheService.put(HttpMethod.GET, HOST, URI, requestHeaders, 
                        HttpStatus.OK, responseHeaders, body);

        CachedResponse cached1 = cacheService.get(HttpMethod.GET, HOST, URI, requestHeaders);


        // Simulate 304 revalidation
        HttpHeaders revalidationHeaders = new HttpHeaders();
        revalidationHeaders.add("ETag", "\"v1\"");
        revalidationHeaders.add("Cache-Control", "public, max-age=120");

        cacheService.updateAfterRevalidation(HttpMethod.GET, HOST, URI, 
                                            requestHeaders, revalidationHeaders);

        CachedResponse cached2 = cacheService.get(HttpMethod.GET, HOST, URI, requestHeaders);
        
        assertNotNull(cached2);
        assertEquals(120L, cached2.getMaxAgeSeconds());
    }

    @Test
    void invalidateUnsafe_shouldRemoveCachedEntry() {
        // Cache GET request
        HttpHeaders requestHeaders = new HttpHeaders();
        HttpHeaders responseHeaders = new HttpHeaders();
        responseHeaders.add("Cache-Control", "public, max-age=60");
        byte[] body = "test response".getBytes();

        cacheService.put(HttpMethod.GET, HOST, URI, requestHeaders, 
                        HttpStatus.OK, responseHeaders, body);

        assertNotNull(cacheService.get(HttpMethod.GET, HOST, URI, requestHeaders));

        // Invalidate
        cacheService.invalidateUnsafe(HOST, URI);

        assertNull(cacheService.get(HttpMethod.GET, HOST, URI, requestHeaders));
    }

    @Test
    void clear_shouldRemoveAllEntries() {
        HttpHeaders requestHeaders = new HttpHeaders();
        HttpHeaders responseHeaders = new HttpHeaders();
        responseHeaders.add("Cache-Control", "public, max-age=60");
        byte[] body = "test response".getBytes();

        // Cache multiple entries
        cacheService.put(HttpMethod.GET, HOST, "/api/users", requestHeaders, 
                        HttpStatus.OK, responseHeaders, body);
        cacheService.put(HttpMethod.GET, HOST, "/api/products", requestHeaders, 
                        HttpStatus.OK, responseHeaders, body);

        cacheService.clear();

        assertNull(cacheService.get(HttpMethod.GET, HOST, "/api/users", requestHeaders));
        assertNull(cacheService.get(HttpMethod.GET, HOST, "/api/products", requestHeaders));
    }

    @Test
    void getStats_shouldReturnCacheStatistics() {
        Map<String, Object> stats = cacheService.getStats();

        assertNotNull(stats);
        assertEquals(0, stats.get("size"));
        assertEquals(10000, stats.get("maxSize"));
    }

    @Test
    void put_shouldParseMustRevalidate() {
        HttpHeaders requestHeaders = new HttpHeaders();
        HttpHeaders responseHeaders = new HttpHeaders();
        responseHeaders.add("Cache-Control", "public, max-age=60, must-revalidate");
        byte[] body = "test response".getBytes();

        cacheService.put(HttpMethod.GET, HOST, URI, requestHeaders, 
                        HttpStatus.OK, responseHeaders, body);

        CachedResponse cached = cacheService.get(HttpMethod.GET, HOST, URI, requestHeaders);
        
        assertNotNull(cached);
        assertTrue(cached.isMustRevalidate());
    }

    @Test
    void put_shouldParseProxyRevalidate() {
        HttpHeaders requestHeaders = new HttpHeaders();
        HttpHeaders responseHeaders = new HttpHeaders();
        responseHeaders.add("Cache-Control", "public, max-age=60, proxy-revalidate");
        byte[] body = "test response".getBytes();

        cacheService.put(HttpMethod.GET, HOST, URI, requestHeaders, 
                        HttpStatus.OK, responseHeaders, body);

        CachedResponse cached = cacheService.get(HttpMethod.GET, HOST, URI, requestHeaders);
        
        assertNotNull(cached);
        assertTrue(cached.isProxyRevalidate());
    }

    @Test
    void put_shouldParseNoCache() {
        HttpHeaders requestHeaders = new HttpHeaders();
        HttpHeaders responseHeaders = new HttpHeaders();
        responseHeaders.add("Cache-Control", "public, max-age=60, no-cache");
        byte[] body = "test response".getBytes();

        cacheService.put(HttpMethod.GET, HOST, URI, requestHeaders, 
                        HttpStatus.OK, responseHeaders, body);

        CachedResponse cached = cacheService.get(HttpMethod.GET, HOST, URI, requestHeaders);
        
        assertNotNull(cached);
        assertTrue(cached.isNoCache());
        assertTrue(cached.requiresRevalidation());
    }
}
