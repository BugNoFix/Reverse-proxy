package com.marco.reverseproxy.cache;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;

import static org.junit.jupiter.api.Assertions.*;

class CacheKeyTest {

    @Test
    void createSimple_shouldCreateKeyWithoutVaryHeaders() {
        CacheKey key = CacheKey.createSimple(HttpMethod.GET, "example.com", "/api/users");

        assertEquals(HttpMethod.GET, key.getMethod());
        assertEquals("example.com", key.getHost());
        assertEquals("/api/users", key.getUri());
        assertTrue(key.getVaryHeaders().isEmpty());
    }

    @Test
    void create_shouldIncludeVaryHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Accept-Encoding", "gzip");
        headers.set("Accept-Language", "en-US");

        CacheKey key = CacheKey.create(HttpMethod.GET, "example.com", "/api/users", 
                                       headers, "Accept-Encoding, Accept-Language");

        assertEquals(2, key.getVaryHeaders().size());
        assertEquals("gzip", key.getVaryHeaders().get("accept-encoding"));
        assertEquals("en-US", key.getVaryHeaders().get("accept-language"));
    }

    @Test
    void create_shouldHandleNullVaryHeader() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Accept-Encoding", "gzip");

        CacheKey key = CacheKey.create(HttpMethod.GET, "example.com", "/api/users", 
                                       headers, null);

        assertTrue(key.getVaryHeaders().isEmpty());
    }

    @Test
    void create_shouldIgnoreWildcardVary() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Accept-Encoding", "gzip");

        CacheKey key = CacheKey.create(HttpMethod.GET, "example.com", "/api/users", 
                                       headers, "*");

        assertTrue(key.getVaryHeaders().isEmpty());
    }

    @Test
    void create_shouldIgnoreMissingVaryHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Accept-Encoding", "gzip");

        CacheKey key = CacheKey.create(HttpMethod.GET, "example.com", "/api/users", 
                                       headers, "Accept-Encoding, Accept-Language");

        assertEquals(1, key.getVaryHeaders().size());
        assertEquals("gzip", key.getVaryHeaders().get("accept-encoding"));
        assertNull(key.getVaryHeaders().get("accept-language"));
    }

    @Test
    void equals_shouldReturnTrueForIdenticalKeys() {
        CacheKey key1 = CacheKey.createSimple(HttpMethod.GET, "example.com", "/api/users");
        CacheKey key2 = CacheKey.createSimple(HttpMethod.GET, "example.com", "/api/users");

        assertEquals(key1, key2);
        assertEquals(key1.hashCode(), key2.hashCode());
    }

    @Test
    void equals_shouldReturnFalseForDifferentMethods() {
        CacheKey key1 = CacheKey.createSimple(HttpMethod.GET, "example.com", "/api/users");
        CacheKey key2 = CacheKey.createSimple(HttpMethod.POST, "example.com", "/api/users");

        assertNotEquals(key1, key2);
    }

    @Test
    void equals_shouldReturnFalseForDifferentHosts() {
        CacheKey key1 = CacheKey.createSimple(HttpMethod.GET, "example.com", "/api/users");
        CacheKey key2 = CacheKey.createSimple(HttpMethod.GET, "other.com", "/api/users");

        assertNotEquals(key1, key2);
    }

    @Test
    void equals_shouldReturnFalseForDifferentUris() {
        CacheKey key1 = CacheKey.createSimple(HttpMethod.GET, "example.com", "/api/users");
        CacheKey key2 = CacheKey.createSimple(HttpMethod.GET, "example.com", "/api/products");

        assertNotEquals(key1, key2);
    }

    @Test
    void equals_shouldConsiderVaryHeaders() {
        HttpHeaders headers1 = new HttpHeaders();
        headers1.set("Accept-Encoding", "gzip");
        
        HttpHeaders headers2 = new HttpHeaders();
        headers2.set("Accept-Encoding", "deflate");

        CacheKey key1 = CacheKey.create(HttpMethod.GET, "example.com", "/api/users", 
                                        headers1, "Accept-Encoding");
        CacheKey key2 = CacheKey.create(HttpMethod.GET, "example.com", "/api/users", 
                                        headers2, "Accept-Encoding");

        assertNotEquals(key1, key2);
    }
}
