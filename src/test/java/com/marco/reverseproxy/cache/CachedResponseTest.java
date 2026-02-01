package com.marco.reverseproxy.cache;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class CachedResponseTest {

    @Test
    void isFresh_shouldReturnTrueWhenWithinMaxAge() {
        CachedResponse response = CachedResponse.builder()
                .cachedAt(Instant.now().minusSeconds(10))
                .maxAgeSeconds(60L)
                .noStore(false)
                .isPrivate(false)
                .build();

        assertTrue(response.isFresh());
    }

    @Test
    void isFresh_shouldReturnFalseWhenExpired() {
        CachedResponse response = CachedResponse.builder()
                .cachedAt(Instant.now().minusSeconds(120))
                .maxAgeSeconds(60L)
                .noStore(false)
                .isPrivate(false)
                .build();

        assertFalse(response.isFresh());
    }

    @Test
    void isFresh_shouldPreferSMaxAge() {
        CachedResponse response = CachedResponse.builder()
                .cachedAt(Instant.now().minusSeconds(50))
                .maxAgeSeconds(30L)
                .sMaxAgeSeconds(120L)
                .noStore(false)
                .isPrivate(false)
                .build();

        assertTrue(response.isFresh());
    }

    @Test
    void isFresh_shouldReturnFalseForNoStore() {
        CachedResponse response = CachedResponse.builder()
                .cachedAt(Instant.now())
                .maxAgeSeconds(60L)
                .noStore(true)
                .isPrivate(false)
                .build();

        assertFalse(response.isFresh());
    }

    @Test
    void isFresh_shouldReturnFalseForPrivate() {
        CachedResponse response = CachedResponse.builder()
                .cachedAt(Instant.now())
                .maxAgeSeconds(60L)
                .noStore(false)
                .isPrivate(true)
                .build();

        assertFalse(response.isFresh());
    }

    @Test
    void isFresh_shouldReturnFalseWhenNoMaxAge() {
        CachedResponse response = CachedResponse.builder()
                .cachedAt(Instant.now())
                .noStore(false)
                .isPrivate(false)
                .build();

        assertFalse(response.isFresh());
    }

    @Test
    void requiresRevalidation_shouldReturnTrueForNoCache() {
        CachedResponse response = CachedResponse.builder()
                .cachedAt(Instant.now())
                .maxAgeSeconds(60L)
                .noCache(true)
                .build();

        assertTrue(response.requiresRevalidation());
    }

    @Test
    void requiresRevalidation_shouldReturnTrueForMustRevalidate() {
        CachedResponse response = CachedResponse.builder()
                .cachedAt(Instant.now())
                .maxAgeSeconds(60L)
                .mustRevalidate(true)
                .build();

        assertTrue(response.requiresRevalidation());
    }

    @Test
    void requiresRevalidation_shouldReturnTrueForProxyRevalidate() {
        CachedResponse response = CachedResponse.builder()
                .cachedAt(Instant.now())
                .maxAgeSeconds(60L)
                .proxyRevalidate(true)
                .build();

        assertTrue(response.requiresRevalidation());
    }

    @Test
    void requiresRevalidation_shouldReturnTrueWhenStale() {
        CachedResponse response = CachedResponse.builder()
                .cachedAt(Instant.now().minusSeconds(120))
                .maxAgeSeconds(60L)
                .noStore(false)
                .isPrivate(false)
                .build();

        assertTrue(response.requiresRevalidation());
    }

    @Test
    void isCacheable_shouldReturnTrueForPublic() {
        CachedResponse response = CachedResponse.builder()
                .isPublic(true)
                .noStore(false)
                .isPrivate(false)
                .build();

        assertTrue(response.isCacheable());
    }

    @Test
    void isCacheable_shouldReturnTrueWithMaxAge() {
        CachedResponse response = CachedResponse.builder()
                .maxAgeSeconds(60L)
                .noStore(false)
                .isPrivate(false)
                .build();

        assertTrue(response.isCacheable());
    }

    @Test
    void isCacheable_shouldReturnFalseForNoStore() {
        CachedResponse response = CachedResponse.builder()
                .maxAgeSeconds(60L)
                .noStore(true)
                .isPrivate(false)
                .build();

        assertFalse(response.isCacheable());
    }

    @Test
    void isCacheable_shouldReturnFalseForPrivate() {
        CachedResponse response = CachedResponse.builder()
                .maxAgeSeconds(60L)
                .noStore(false)
                .isPrivate(true)
                .build();

        assertFalse(response.isCacheable());
    }

    @Test
    void hasValidationMetadata_shouldReturnTrueWithETag() {
        CachedResponse response = CachedResponse.builder()
                .etag("\"abc123\"")
                .build();

        assertTrue(response.hasValidationMetadata());
    }

    @Test
    void hasValidationMetadata_shouldReturnTrueWithLastModified() {
        CachedResponse response = CachedResponse.builder()
                .lastModified(Instant.now())
                .build();

        assertTrue(response.hasValidationMetadata());
    }

    @Test
    void hasValidationMetadata_shouldReturnFalseWithoutMetadata() {
        CachedResponse response = CachedResponse.builder()
                .build();

        assertFalse(response.hasValidationMetadata());
    }

    @Test
    void getAgeSeconds_shouldReturnCorrectAge() {
        Instant cachedAt = Instant.now().minusSeconds(30);
        CachedResponse response = CachedResponse.builder()
                .cachedAt(cachedAt)
                .build();

        long age = response.getAgeSeconds();
        assertTrue(age >= 30 && age <= 31); // Allow 1 second tolerance
    }

    @Test
    void builder_shouldCreateCompleteResponse() {
        HttpHeaders headers = new HttpHeaders();
        headers.add("Content-Type", "application/json");
        
        CachedResponse response = CachedResponse.builder()
                .body("test".getBytes())
                .headers(headers)
                .statusCode(HttpStatus.OK)
                .cachedAt(Instant.now())
                .maxAgeSeconds(60L)
                .etag("\"abc123\"")
                .lastModified(Instant.now())
                .noCache(false)
                .noStore(false)
                .mustRevalidate(true)
                .isPublic(true)
                .build();

        assertNotNull(response.getBody());
        assertNotNull(response.getHeaders());
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getCachedAt());
        assertEquals(60L, response.getMaxAgeSeconds());
        assertEquals("\"abc123\"", response.getEtag());
        assertNotNull(response.getLastModified());
        assertFalse(response.isNoCache());
        assertFalse(response.isNoStore());
        assertTrue(response.isMustRevalidate());
        assertTrue(response.isPublic());
    }
}
