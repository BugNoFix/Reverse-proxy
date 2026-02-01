package com.marco.reverseproxy.service;

import com.marco.reverseproxy.config.ProxyConfiguration;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import com.marco.reverseproxy.util.HostUtils;

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

    public ProxyConfiguration.ServiceConfig getServiceByDomain(String domain, Boolean normalizeDomain) {
        if (domain == null) {
            return null;
        }
        if (normalizeDomain) {
            return serviceByDomain.get(HostUtils.normalizeHost(domain));
        }
        return serviceByDomain.get(domain);

    }
}
