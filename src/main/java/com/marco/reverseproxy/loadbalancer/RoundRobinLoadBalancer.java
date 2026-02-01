package com.marco.reverseproxy.loadbalancer;

import com.marco.reverseproxy.config.ProxyConfiguration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Round-robin load balancer implementation (thread-safe)
 * Each service maintains its own counter for round-robin distribution
 */
@Slf4j
@Component
public class RoundRobinLoadBalancer implements LoadBalancer {

    // Counter per service (thread-safe)
    private final Map<String, AtomicInteger> serviceCounters = new ConcurrentHashMap<>();

    @Override
    public ProxyConfiguration.HostConfig selectHost(
            ProxyConfiguration.ServiceConfig service
    ) {
        List<ProxyConfiguration.HostConfig> hosts = service.getHosts();
        
        if (hosts == null || hosts.isEmpty()) {
            log.warn("No hosts available for service: {}", service.getName());
            return null;
        }

        // Get or create counter for this service
        AtomicInteger counter = serviceCounters.computeIfAbsent(
                service.getName(),
                k -> new AtomicInteger(0)
        );

        // Get next index using modulo (thread-safe increment)
int index = Math.abs(counter.getAndIncrement() % hosts.size());

        ProxyConfiguration.HostConfig selectedHost = hosts.get(index);
        
        log.debug("Round-robin selected {}:{} for service {} (index: {})", 
                selectedHost.getAddress(), 
                selectedHost.getPort(),
                service.getName(),
                index);

        return selectedHost;
    }

    @Override
    public String getStrategyName() {
        return "round-robin";
    }
}
