package com.marco.reverseproxy.loadbalancer;

import com.marco.reverseproxy.config.ProxyConfiguration;

import java.util.List;

/**
 * Interface for load balancing strategies
 */
public interface LoadBalancer {
    
    /**
     * Select a host from the service configuration
     * 
     * @param serviceConfig Service configuration containing hosts
     * @return Selected host or null if no hosts available
     */
    ProxyConfiguration.HostConfig selectHost(
            ProxyConfiguration.ServiceConfig serviceConfig
    );
    
    /**
     * Get the strategy name
     *
     * @return Strategy name
     */
    String getStrategyName();
}
