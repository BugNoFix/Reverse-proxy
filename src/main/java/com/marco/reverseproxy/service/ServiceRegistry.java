package com.marco.reverseproxy.service;

import com.marco.reverseproxy.config.ProxyConfiguration;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Slf4j
@Service
public class ServiceRegistry {

    @Getter
    private final ProxyConfiguration proxyConfiguration;
    
    private final Map<String, ProxyConfiguration.ServiceConfig> serviceByDomain;

    public ServiceRegistry(ProxyConfiguration proxyConfiguration) {
        this.proxyConfiguration = proxyConfiguration;
        this.serviceByDomain = new ConcurrentHashMap<>();
        initializeRegistry();
    }

    private void initializeRegistry() {
        if (proxyConfiguration.getServices() != null) {
            proxyConfiguration.getServices().forEach(service -> {
                serviceByDomain.put(service.getDomain().toLowerCase(), service);
                log.info("Registered service: {} for domain: {}", service.getName(), service.getDomain());
            });
        }
    }

    public ProxyConfiguration.ServiceConfig getServiceByDomain(String domain) {
        if (domain == null) {
            return null;
        }
        return serviceByDomain.get(domain.toLowerCase());
    }

    public List<ProxyConfiguration.HostConfig> getHealthyHosts(ProxyConfiguration.ServiceConfig service) {
        return service.getHosts().stream()
                .filter(ProxyConfiguration.HostConfig::isHealthy)
                .collect(Collectors.toList());
    }

    public void markHostUnhealthy(ProxyConfiguration.ServiceConfig service, ProxyConfiguration.HostConfig host) {
        host.setHealthy(false);
        host.setLastHealthCheck(System.currentTimeMillis());
        log.warn("Marked host {}:{} as unhealthy for service {}", 
                host.getAddress(), host.getPort(), service.getName());
    }

    public void markHostHealthy(ProxyConfiguration.ServiceConfig service, ProxyConfiguration.HostConfig host) {
        host.setHealthy(true);
        host.setLastHealthCheck(System.currentTimeMillis());
        log.info("Marked host {}:{} as healthy for service {}", 
                host.getAddress(), host.getPort(), service.getName());
    }
}
