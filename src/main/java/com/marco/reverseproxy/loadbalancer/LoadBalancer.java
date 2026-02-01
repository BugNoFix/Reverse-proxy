package com.marco.reverseproxy.loadbalancer;

import com.marco.reverseproxy.config.ProxyConfiguration;

import java.util.List;

/**
 * Interface for load balancing strategies
 */
public interface LoadBalancer {
    
    /**
     * Select a host from the available hosts
     * 
     * @param hosts List of available hosts
     * @param serviceConfig Service configuration
     * @return Selected host or null if no hosts available
     */
    ProxyConfiguration.HostConfig selectHost(
            List<ProxyConfiguration.HostConfig> hosts, 
            ProxyConfiguration.ServiceConfig serviceConfig
    );
    
    /**
     * Get the strategy name
     * 
     * @return Strategy name
     */
    String getStrategyName();
}
