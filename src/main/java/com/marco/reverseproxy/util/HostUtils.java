package com.marco.reverseproxy.util;

public final class HostUtils {

    private HostUtils() {
    }

    public static String normalizeHost(String hostHeader) {
        if (hostHeader == null) {
            return null;
        }

        String normalized = hostHeader.toLowerCase().trim();
        int colonIndex = normalized.indexOf(':');
        if (colonIndex > -1) {
            normalized = normalized.substring(0, colonIndex);
        }
        return normalized;
    }
}
