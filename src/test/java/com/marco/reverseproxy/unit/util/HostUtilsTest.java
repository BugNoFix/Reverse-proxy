package com.marco.reverseproxy.unit.util;

import com.marco.reverseproxy.util.HostUtils;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class HostUtilsTest {

    @Test
    void normalizeHost_shouldRemovePort() {
        assertEquals("example.com", HostUtils.normalizeHost("example.com:8080"));
        assertEquals("localhost", HostUtils.normalizeHost("localhost:3000"));
    }

    @Test
    void normalizeHost_shouldConvertToLowerCase() {
        assertEquals("example.com", HostUtils.normalizeHost("Example.COM"));
        assertEquals("api.service.com", HostUtils.normalizeHost("API.Service.COM"));
    }

    @Test
    void normalizeHost_shouldTrimWhitespace() {
        assertEquals("example.com", HostUtils.normalizeHost("  example.com  "));
        assertEquals("localhost", HostUtils.normalizeHost(" localhost "));
    }

    @Test
    void normalizeHost_shouldHandleComplexScenarios() {
        assertEquals("example.com", HostUtils.normalizeHost("  Example.COM:8080  "));
    }

    @Test
    void normalizeHost_shouldReturnNullForNullInput() {
        assertNull(HostUtils.normalizeHost(null));
    }

    @Test
    void normalizeHost_shouldHandleEmptyString() {
        assertEquals("", HostUtils.normalizeHost(""));
    }

    @Test
    void normalizeHost_shouldHandleHostWithoutPort() {
        assertEquals("example.com", HostUtils.normalizeHost("example.com"));
        assertEquals("localhost", HostUtils.normalizeHost("localhost"));
    }
}
