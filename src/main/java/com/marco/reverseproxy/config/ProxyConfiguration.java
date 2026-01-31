package com.marco.reverseproxy.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Data
@Configuration
@ConfigurationProperties(prefix = "proxy")
public class ProxyConfiguration {
    
    private ListenConfig listen;
    private List<ServiceConfig> services;
    
    @Data
    public static class ListenConfig {
        private String address;
        private int port;
    }
    
    @Data
    public static class ServiceConfig {
        private String name;
        private String domain;
        private List<HostConfig> hosts;
    }
    
    @Data
    public static class HostConfig {
        private String address;
        private int port;
        private boolean healthy = false;
        private long lastHealthCheck = 0;
    }
}
