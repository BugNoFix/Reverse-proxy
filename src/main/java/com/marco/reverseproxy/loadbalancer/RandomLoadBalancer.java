package com.marco.reverseproxy.loadbalancer;

import com.marco.reverseproxy.config.ProxyConfiguration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Random;

/**
 * Random load balancing strategy
 * Selects a random host from the available hosts
 */
@Slf4j
@Component
public class RandomLoadBalancer implements LoadBalancer {
    
    private final Random random = new Random();

    @Override
    public ProxyConfiguration.HostConfig selectHost(
            ProxyConfiguration.ServiceConfig serviceConfig
    ) {
        List<ProxyConfiguration.HostConfig> hosts = serviceConfig.getHosts();
        
        if (hosts == null || hosts.isEmpty()) {
            log.warn("No hosts available for service: {}", serviceConfig.getName());
            return null;
        }

        int index = random.nextInt(hosts.size());
        ProxyConfiguration.HostConfig selectedHost = hosts.get(index);
        
        log.debug("Selected host {}:{} for service {} using random strategy", 
                selectedHost.getAddress(), selectedHost.getPort(), serviceConfig.getName());
        
        return selectedHost;
    }

    @Override
    public String getStrategyName() {
        return "random";
    }
}
